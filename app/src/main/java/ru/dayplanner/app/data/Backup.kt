package ru.dayplanner.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.dayplanner.app.container
import ru.dayplanner.app.domain.Completion
import ru.dayplanner.app.domain.Photo
import ru.dayplanner.app.domain.RepeatMode
import ru.dayplanner.app.domain.SignalType
import ru.dayplanner.app.domain.SkippedDay
import ru.dayplanner.app.domain.Task
import ru.dayplanner.app.domain.Template
import ru.dayplanner.app.domain.TimeMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Один ZIP: JSON v1 и фото. До проверки всего архива текущие записи не изменяются. */
object Backup {
    private const val FORMAT = "ru.dayplanner.app/1"
    private const val META = "planner-v1.json"
    private const val META_LIMIT = 16L * 1024 * 1024
    private const val TOTAL_LIMIT = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_ROWS = 100_000
    private const val MAX_DAY = 79_999L

    private class Snapshot(
        val tasks: List<Task>, val templates: List<Template>, val photos: List<Photo>,
        val done: List<Completion>, val skipped: List<SkippedDay>,
    )

    private fun row(vararg values: Any?): JSONArray = JSONArray().apply {
        for (value in values) put(value ?: JSONObject.NULL)
    }

    private fun <T> rows(items: List<T>, encode: (T) -> JSONArray): JSONArray {
        require(items.size <= MAX_ROWS)
        return JSONArray().apply { items.forEach { put(encode(it) as Any) } }
    }

    private fun <T> decode(source: JSONObject, key: String, width: Int, parse: (JSONArray) -> T): List<T> {
        val array = source.getJSONArray(key)
        require(array.length() <= MAX_ROWS)
        return List(array.length()) { index ->
            val row = array.getJSONArray(index)
            require(row.length() == width)
            parse(row)
        }
    }

    private fun entryName(index: Int) = "photos/$index"
    private fun day(value: Long): Long = value.also { require(it in 0L..MAX_DAY) }

    private fun copy(input: InputStream, output: OutputStream, limit: Long, expectedCrc: Long? = null): Long {
        val buffer = ByteArray(32 * 1024)
        val crc = CRC32()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit)
            output.write(buffer, 0, read)
            crc.update(buffer, 0, read)
        }
        require(expectedCrc == null || crc.value == expectedCrc)
        return total
    }

    suspend fun export(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            val box = context.container
            val dao = box.db.dao()
            val tmp = File.createTempFile("export-", ".zip", context.cacheDir)
            try {
                val snapshot = box.db.withTransaction {
                    Snapshot(dao.getTasks(), dao.getTemplates(), dao.getAllPhotos(), dao.getCompletions(), dao.getSkipped())
                }
                val images = BackupImages.plan(snapshot.photos.map { it.fileName }, box.photoStore::file)
                require(images.size < MAX_ENTRIES)
                val indexOf = images.mapIndexed { index, image -> image.name to index }.toMap()
                val meta = JSONObject()
                meta.put("format", FORMAT)
                meta.put("images", images.size)
                meta.put("tasks", rows(snapshot.tasks) { t ->
                    row(t.id, t.title, t.description, t.timeMode.name, t.startMinutes, t.endMinutes,
                        t.startDay, t.endDay, t.repeatMode.name, t.weekdaysMask,
                        t.signalStart, t.signalEnd, t.signalType.name, t.createdAt)
                })
                meta.put("templates", rows(snapshot.templates) { t ->
                    row(t.id, t.title, t.description, t.signalStart, t.signalEnd, t.signalType.name)
                })
                meta.put("photos", rows(snapshot.photos) { p -> row(p.taskId, indexOf.getValue(p.fileName), p.position) })
                meta.put("done", rows(snapshot.done) { row(it.taskId, it.day) })
                meta.put("skip", rows(snapshot.skipped) { row(it.taskId, it.day) })

                val bytes = meta.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= META_LIMIT && images.sumOf { it.size } <= TOTAL_LIMIT - bytes.size)
                ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(META))
                    zip.write(bytes)
                    zip.closeEntry()
                    images.forEachIndexed { index, image ->
                        zip.putNextEntry(ZipEntry(entryName(index)))
                        image.file.inputStream().use { input -> require(copy(input, zip, image.size) == image.size) }
                        zip.closeEntry()
                    }
                }
                require(tmp.length() <= TOTAL_LIMIT)
                // Открываем выбранный документ на запись только после сборки полной копии.
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out ->
                    tmp.inputStream().use { input -> input.copyTo(out) }
                }
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun restore(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            val box = context.container
            val dao = box.db.dao()
            val tmp = File.createTempFile("import-", ".zip", context.cacheDir)
            val staged = ArrayList<File>()
            var committed = false
            try {
                val source = requireNotNull(context.contentResolver.openInputStream(uri))
                source.use { input -> tmp.outputStream().use { out -> copy(input, out, TOTAL_LIMIT) } }
                ZipFile(tmp).use { zip ->
                    require(zip.size() in 1..MAX_ENTRIES)
                    val metaEntry = requireNotNull(zip.getEntry(META))
                    require(metaEntry.size in 1..META_LIMIT)
                    val metaBytes = ByteArrayOutputStream()
                    zip.getInputStream(metaEntry).use { input ->
                        require(copy(input, metaBytes, META_LIMIT, metaEntry.crc) == metaEntry.size)
                    }
                    val meta = JSONObject(metaBytes.toString("UTF-8"))
                    require(meta.getString("format") == FORMAT)
                    val imageCount = meta.getInt("images")
                    require(imageCount in 0 until MAX_ENTRIES && zip.size() == imageCount + 1)

                    val tasks = decode(meta, "tasks", 14) { r ->
                        val task = Task(
                            id = r.getLong(0), title = r.getString(1), description = r.getString(2),
                            timeMode = TimeMode.valueOf(r.getString(3)), startMinutes = r.getInt(4), endMinutes = r.getInt(5),
                            startDay = day(r.getLong(6)), endDay = if (r.isNull(7)) null else day(r.getLong(7)),
                            repeatMode = RepeatMode.valueOf(r.getString(8)), weekdaysMask = r.getInt(9),
                            signalStart = r.getBoolean(10), signalEnd = r.getBoolean(11),
                            signalType = SignalType.valueOf(r.getString(12)), createdAt = r.getLong(13),
                        )
                        require(task.id > 0 && task.title.isNotBlank())
                        require(task.startMinutes in 0..1439 && task.endMinutes in 0..1439)
                        require(task.timeMode != TimeMode.RANGE || task.endMinutes > task.startMinutes)
                        require(task.endDay == null || task.endDay >= task.startDay)
                        require(task.repeatMode != RepeatMode.WEEKDAYS || task.weekdaysMask in 1..127)
                        task
                    }
                    val taskIds = HashSet<Long>()
                    for (task in tasks) require(taskIds.add(task.id))
                    val templates = decode(meta, "templates", 6) { r ->
                        Template(id = r.getLong(0), title = r.getString(1), description = r.getString(2),
                            signalStart = r.getBoolean(3), signalEnd = r.getBoolean(4), signalType = SignalType.valueOf(r.getString(5)))
                            .also { require(it.id > 0 && it.title.isNotBlank()) }
                    }
                    require(templates.map { it.id }.toSet().size == templates.size)
                    val done = decode(meta, "done", 2) { r ->
                        Completion(r.getLong(0), day(r.getLong(1))).also { require(it.taskId in taskIds) }
                    }
                    require(done.toSet().size == done.size)
                    val skipped = decode(meta, "skip", 2) { r ->
                        SkippedDay(r.getLong(0), day(r.getLong(1))).also { require(it.taskId in taskIds) }
                    }
                    require(skipped.toSet().size == skipped.size)
                    val refs = decode(meta, "photos", 3) { r ->
                        Triple(r.getLong(0), r.getInt(1), r.getInt(2)).also {
                            require(it.first in taskIds && it.second in 0 until imageCount && it.third >= 0)
                        }
                    }
                    require(refs.map { it.second }.toSet().size == imageCount)
                    require(refs.map { it.first to it.second }.toSet().size == refs.size)

                    // Имена из архива не используются как пути. Старые фото пока не трогаем.
                    var left = TOTAL_LIMIT - metaEntry.size
                    for (index in 0 until imageCount) {
                        val entry = requireNotNull(zip.getEntry(entryName(index)))
                        require(entry.size in 1..minOf(left, BackupImages.MAX_BYTES))
                        val file = File(box.photoStore.dir, "p_${UUID.randomUUID()}.jpg")
                        require(file.createNewFile())
                        staged.add(file)
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { out -> require(copy(input, out, entry.size, entry.crc) == entry.size) }
                        }
                        left -= entry.size
                    }
                    val photos = refs.map { Photo(taskId = it.first, fileName = staged[it.second].name, position = it.third) }
                    val oldFiles = box.db.withTransaction {
                        val names = dao.getAllPhotos().map { it.fileName }.distinct()
                        dao.deleteAllPhotos()
                        dao.deleteAllCompletions()
                        dao.deleteAllSkipped()
                        dao.deleteAllTasks()
                        dao.deleteAllTemplates()
                        dao.insertTasks(tasks)
                        dao.insertTemplates(templates)
                        dao.insertCompletions(done)
                        dao.insertSkippedList(skipped)
                        dao.insertPhotos(photos)
                        names
                    }
                    committed = true
                    for (name in oldFiles) box.photoStore.delete(name)
                }
                box.notifyChanged()
            } finally {
                if (!committed) for (file in staged) file.delete()
                tmp.delete()
            }
        }
    }
}
