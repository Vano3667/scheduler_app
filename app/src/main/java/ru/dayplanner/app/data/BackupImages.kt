package ru.dayplanner.app.data

import java.io.File

/** У повторов бывают общие фото: байты хранятся один раз, ссылки сохраняются все. */
internal object BackupImages {
    const val MAX_BYTES = 128L * 1024 * 1024
    data class Image(val name: String, val file: File, val size: Long)

    fun plan(names: List<String>, resolve: (String) -> File): List<Image> = names.distinct().map { name ->
        val file = resolve(name)
        val size = file.length()
        require(file.isFile && size in 1..MAX_BYTES) { "Невозможно включить фотографию в резервную копию" }
        Image(name, file, size)
    }
}
