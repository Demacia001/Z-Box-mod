package com.github.tvbox.osc.player.engine.iso

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IsoDiskCacheTest {

    private lateinit var dir: File

    @Before
    fun createDirectory() {
        dir = Files.createTempDirectory("iso-cache").toFile()
    }

    @After
    fun deleteDirectory() {
        dir.deleteRecursively()
    }

    @Test
    fun saveThenLoad_roundTrips() {
        val snapshot = sampleSnapshot()
        val bytes = IsoDiskCache.saveTo(dir, KEY, snapshot)
        assertTrue("bytes=$bytes", bytes > 0)
        val loaded = IsoDiskCache.loadFrom(dir, KEY)

        assertNotNull(loaded)
        assertTrue(snapshot.encode().contentEquals(requireNotNull(loaded).encode()))
        assertEquals(1, dir.listFiles()?.count { it.name.endsWith(".snap") })
    }

    @Test
    fun load_missingFile_returnsNull() {
        assertNull(IsoDiskCache.loadFrom(dir, KEY))
    }

    @Test
    fun load_corruptFile_returnsNullAndDeletes() {
        val file = File(dir, KEY.replace(':', '_') + ".snap")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNull(IsoDiskCache.loadFrom(dir, KEY))
        assertTrue(!file.exists())
    }

    @Test
    fun save_oversizeIsRejected() {
        val big = IsoEntry(
            "big.bin",
            false,
            700_000L,
            emptyList(),
            EntryData.Embedded(ByteArray(700_000)),
        )
        val snapshot = IsoVolumeSnapshot(mapOf(ISO_ROOT_PATH to listOf(big, big)))
        assertEquals(0, IsoDiskCache.saveTo(dir, KEY, snapshot))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun save_noTempLeftBehind() {
        IsoDiskCache.saveTo(dir, KEY, sampleSnapshot())
        assertTrue("files=${dir.list()?.toList()}", dir.listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    private fun sampleSnapshot(): IsoVolumeSnapshot {
        val volume = UdfVolume.open(UdfImageBuilder(useMetadataPartition = true).reader(), IMAGE_SIZE)
        return requireNotNull(volume.snapshot())
    }

    private companion object {
        const val IMAGE_SIZE = 400L * SECTOR_SIZE
        const val KEY = "12345:abcdef"
    }
}
