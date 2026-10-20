package com.github.tvbox.osc.player.engine.iso

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoFileSourceTest {

    @Test
    fun snapshot_roundTrip_preservesSelection() {
        val image = UdfImageBuilder(useMetadataPartition = true).image()
        val volume = UdfVolume.open(counting(image), IMAGE_SIZE)
        val original = IsoLocator(volume).locateSelection()
        val snapshot = requireNotNull(volume.snapshot())
        val decoded = requireNotNull(IsoVolumeSnapshot.decode(snapshot.encode()))
        val cached = CachedVolume(decoded.directories, counting(image))
        val reopened = IsoLocator(cached).locateSelection()

        assertEquals(original, reopened)
        assertEquals("00001.m2ts", reopened.primary.name)
    }

    @Test
    fun snapshot_roundTrip_readsInlinedDataWithoutNetwork() {
        val image = UdfImageBuilder(useMetadataPartition = true).image()
        val volume = UdfVolume.open(counting(image), IMAGE_SIZE)
        val stream = requireNotNull(
            volume.find(requireNotNull(volume.find(volume.root(), "BDMV")), "STREAM"),
        )
        val expected = volume.readEntry(volume.list(stream)[0])
        val snapshot = requireNotNull(volume.snapshot())
        val decoded = requireNotNull(IsoVolumeSnapshot.decode(snapshot.encode()))
        val reads = AtomicInteger()
        val cached = CachedVolume(decoded.directories, counting(image, reads))
        val cachedStream = requireNotNull(
            cached.find(requireNotNull(cached.find(cached.root(), "BDMV")), "STREAM"),
        )

        assertTrue(expected.contentEquals(cached.readEntry(cached.list(cachedStream)[0])))
        assertEquals("reads=${reads.get()}", 0, reads.get())
    }

    @Test
    fun snapshot_keepsMissingSubdirectoriesAbsent() {
        val volume = UdfVolume.open(UdfImageBuilder(useMetadataPartition = false).reader(), IMAGE_SIZE)
        val snapshot = requireNotNull(volume.snapshot())
        val decoded = requireNotNull(IsoVolumeSnapshot.decode(snapshot.encode()))
        val cached = CachedVolume(decoded.directories, RangeBlockReader { _, _ -> error("no reads") })
        val bdmv = requireNotNull(cached.find(cached.root(), "BDMV"))

        assertNull(cached.find(bdmv, "CLIPINF"))
        assertNotNull(cached.find(bdmv, "STREAM"))
    }

    @Test
    fun cachedVolume_readsExtentBackedEntry() {
        val entry = IsoEntry(
            "00016.clpi",
            false,
            16_384L,
            listOf(IsoExtent(0L, 16_384L)),
            EntryData.Extents(listOf(IsoExtent(0L, 16_384L))),
        )
        val snapshot = IsoVolumeSnapshot(mapOf(ISO_ROOT_PATH to listOf(entry)))
        val decoded = requireNotNull(IsoVolumeSnapshot.decode(snapshot.encode()))
        val cached = CachedVolume(decoded.directories, RangeBlockReader { _, length -> ByteArray(length) })
        val restored = cached.list(cached.root()).single()

        assertEquals(16_384L, restored.length)
        assertEquals(16_384, cached.readEntry(restored).size)
    }

    @Test
    fun cachedVolume_unknownDirectory_throws() {
        val cached = CachedVolume(emptyMap(), RangeBlockReader { _, _ -> error("no reads") })
        assertThrows(IsoFormatException::class.java) { cached.list(cached.root()) }
    }

    @Test
    fun decode_rejectsGarbageAndTruncation() {
        assertNull(IsoVolumeSnapshot.decode(byteArrayOf(1, 2, 3)))
        val encoded = IsoVolumeSnapshot(
            mapOf(ISO_ROOT_PATH to emptyList()),
        ).encode()
        assertNull(IsoVolumeSnapshot.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun decode_rejectsStaleSnapshotVersion() {
        val encoded = IsoVolumeSnapshot(
            mapOf(ISO_ROOT_PATH to emptyList()),
        ).encode()
        val stale = encoded.copyOf()
        stale[4] = 0
        stale[5] = 0
        stale[6] = 0
        stale[7] = 1

        assertNull(IsoVolumeSnapshot.decode(stale))
        assertNotNull(IsoVolumeSnapshot.decode(encoded))
    }

    private fun counting(image: ByteArray, reads: AtomicInteger? = null): RangeBlockReader =
        RangeBlockReader { start, length ->
            reads?.incrementAndGet()
            image.copyOfRange(start.toInt(), (start + length).toInt())
        }

    private companion object {
        const val IMAGE_SIZE = 400L * SECTOR_SIZE
    }
}
