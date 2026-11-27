package ru.dayplanner.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class BackupImagesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun resolve(name: String) = File(folder.root, name)

    @Test fun sharedImageStoredOnceInFirstReferenceOrder() {
        resolve("b").writeBytes(byteArrayOf(1, 2))
        resolve("a").writeBytes(byteArrayOf(3))
        val plan = BackupImages.plan(listOf("b", "a", "b"), ::resolve)
        assertEquals(listOf("b", "a"), plan.map { it.name })
        assertEquals(listOf(2L, 1L), plan.map { it.size })
    }

    @Test fun oneMissingImageRejectsTheWholePlan() {
        resolve("ok").writeBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { BackupImages.plan(listOf("ok", "missing"), ::resolve) }
    }

    @Test fun emptyAndOversizedImagesAreNotSilentlySkipped() {
        folder.newFile("empty")
        assertThrows(IllegalArgumentException::class.java) { BackupImages.plan(listOf("empty"), ::resolve) }
        RandomAccessFile(resolve("large"), "rw").use { it.setLength(BackupImages.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupImages.plan(listOf("large"), ::resolve) }
    }
}
