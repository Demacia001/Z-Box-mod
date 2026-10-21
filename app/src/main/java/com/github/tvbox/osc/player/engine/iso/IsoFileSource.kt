package com.github.tvbox.osc.player.engine.iso

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

interface IsoFileSource {
    fun root(): IsoEntry
    fun list(directory: IsoEntry): List<IsoEntry>
    fun find(directory: IsoEntry, name: String): IsoEntry?
    fun readEntry(entry: IsoEntry): ByteArray
}

internal const val ISO_ROOT_PATH = ""
internal const val ISO_BDMV_PATH = "BDMV"
internal const val ISO_STREAM_PATH = "BDMV/STREAM"
internal const val ISO_PLAYLIST_PATH = "BDMV/PLAYLIST"
internal const val ISO_CLIPINF_PATH = "BDMV/CLIPINF"

internal class IsoVolumeSnapshot(val directories: Map<String, List<IsoEntry>>) {

    fun encode(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(directories.size)
            for ((path, children) in directories) {
                out.writeUTF(path)
                out.writeInt(children.size)
                for (child in children) writeEntry(out, child)
            }
        }
        return buffer.toByteArray()
    }

    private fun writeEntry(out: DataOutputStream, entry: IsoEntry) {
        out.writeUTF(entry.name)
        out.writeBoolean(entry.directory)
        out.writeLong(entry.length)
        out.writeInt(entry.extents.size)
        for (extent in entry.extents) {
            out.writeLong(extent.offset)
            out.writeLong(extent.length)
        }
        val inline = (entry.data as? EntryData.Embedded)?.bytes
        if (inline == null || inline.isEmpty()) {
            out.writeInt(NO_INLINE)
        } else {
            out.writeInt(inline.size)
            out.write(inline)
        }
    }

    companion object {

        fun decode(bytes: ByteArray): IsoVolumeSnapshot? =
            runCatching { decodeStrict(bytes) }.getOrNull()

        private fun decodeStrict(bytes: ByteArray): IsoVolumeSnapshot {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                check(input.readInt() == MAGIC)
                check(input.readInt() == VERSION)
                val count = input.readInt()
                check(count in 1..MAX_DIRECTORIES)
                val directories = LinkedHashMap<String, List<IsoEntry>>(count)
                repeat(count) {
                    val path = input.readUTF()
                    val children = input.readInt()
                    check(children in 0..MAX_CHILDREN)
                    val entries = ArrayList<IsoEntry>(children)
                    repeat(children) { entries.add(readEntry(input)) }
                    directories[path] = entries
                }
                check(directories.containsKey(ISO_ROOT_PATH))
                return IsoVolumeSnapshot(directories)
            }
        }

        private fun readEntry(input: DataInputStream): IsoEntry {
            val name = input.readUTF()
            val directory = input.readBoolean()
            val length = input.readLong()
            val extentCount = input.readInt()
            check(extentCount in 0..MAX_EXTENTS)
            val extents = ArrayList<IsoExtent>(extentCount)
            repeat(extentCount) { extents.add(IsoExtent(input.readLong(), input.readLong())) }
            val inlineLength = input.readInt()
            val data = if (inlineLength == NO_INLINE) {
                EntryData.Extents(extents)
            } else {
                check(inlineLength in 1..MAX_INLINE)
                val inline = ByteArray(inlineLength)
                input.readFully(inline)
                EntryData.Embedded(inline)
            }
            return IsoEntry(name, directory, length, extents, data)
        }

        private const val MAGIC = 0x49534F53
        private const val VERSION = 2
        private const val NO_INLINE = -1
        private const val MAX_DIRECTORIES = 8
        private const val MAX_CHILDREN = 4096
        private const val MAX_EXTENTS = 4096
        private const val MAX_INLINE = 64 * 1024
    }
}

internal class CachedVolume(
    private val directories: Map<String, List<IsoEntry>>,
    private val reader: RangeBlockReader,
) : IsoFileSource {

    private val rootEntry =
        IsoEntry(ISO_ROOT_PATH, true, 0L, emptyList(), EntryData.Extents(emptyList()))
    private val paths = HashMap<IsoEntry, String>()

    init {
        paths[rootEntry] = ISO_ROOT_PATH
        for ((path, children) in directories) {
            for (child in children) {
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (directories.containsKey(childPath)) paths[child] = childPath
            }
        }
    }

    override fun root(): IsoEntry = rootEntry

    override fun list(directory: IsoEntry): List<IsoEntry> {
        if (!directory.directory) throw IsoFormatException("not a directory: ${directory.name}")
        val path = paths[directory] ?: throw IsoFormatException("unknown directory: ${directory.name}")
        return directories[path] ?: throw IsoFormatException("unknown directory: $path")
    }

    override fun find(directory: IsoEntry, name: String): IsoEntry? =
        list(directory).firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun readEntry(entry: IsoEntry): ByteArray =
        when (val data = entry.data) {
            is EntryData.Embedded -> data.bytes
            is EntryData.Extents -> readExtents(entry, data.extents)
        }

    private fun readExtents(entry: IsoEntry, extents: List<IsoExtent>): ByteArray {
        val total = extents.sumOf { it.length }
        if (total > MAX_IN_MEMORY_DATA) throw IsoFormatException("entry too large: ${entry.name}")
        val bytes = ByteArray(total.toInt())
        var written = 0
        for (extent in extents) {
            if (extent.length == 0L) continue
            val chunk = reader.read(extent.offset, extent.length.toInt())
            System.arraycopy(chunk, 0, bytes, written, chunk.size)
            written += chunk.size
        }
        return bytes
    }
}

internal fun UdfVolume.snapshot(logger: (String) -> Unit = {}): IsoVolumeSnapshot? {
    val directories = LinkedHashMap<String, List<IsoEntry>>()
    val root = root()
    val rootListing = listAvailable(root)
    val direct = rootListing.entries.firstOrNull { it.name.equals(ISO_BDMV_PATH, ignoreCase = true) }
    val bdmv = direct ?: nestedBdmv(rootListing.entries) ?: return null
    directories[ISO_ROOT_PATH] = if (direct != null) {
        inlineSmall(rootListing.entries)
    } else {
        listOf(directoryAlias(bdmv))
    }
    val bdmvListing = listAvailable(bdmv)
    directories[ISO_BDMV_PATH] = bdmvListing.entries
    val stream = bdmvListing.entries.firstOrNull { it.name.equals("STREAM", ignoreCase = true) }
        ?: return null
    val streamListing = listAvailable(stream)
    if (streamListing.entries.isEmpty() || streamListing.skipped > 0) return null
    directories[ISO_STREAM_PATH] = inlineSmall(streamListing.entries)
    val playlist = bdmvListing.entries.firstOrNull { it.name.equals("PLAYLIST", ignoreCase = true) }
    if (playlist != null && playlist.directory) {
        val playlistListing = listAvailable(playlist)
        if (playlistListing.entries.isEmpty() || playlistListing.skipped > 0) return null
        directories[ISO_PLAYLIST_PATH] = inlineSmall(playlistListing.entries)
    }
    val clipinf = bdmvListing.entries.firstOrNull { it.name.equals("CLIPINF", ignoreCase = true) }
    if (clipinf != null && clipinf.directory) {
        val clipinfListing = listAvailable(clipinf)
        if (clipinfListing.entries.isEmpty() || clipinfListing.skipped > 0) return null
        directories[ISO_CLIPINF_PATH] = inlineSmall(clipinfListing.entries)
    }
    if (directories.size < REQUIRED_DIRECTORIES) return null
    return IsoVolumeSnapshot(directories)
}

private fun UdfVolume.inlineSmall(entries: List<IsoEntry>): List<IsoEntry> =
    if (entries.none { it.needsInline() }) entries else entries.map { inline(it) }

private fun UdfVolume.inline(entry: IsoEntry): IsoEntry {
    if (!entry.needsInline()) return entry
    val bytes = try {
        readEntry(entry)
    } catch (e: IsoFormatException) {
        return entry
    }
    return IsoEntry(entry.name, false, entry.length, entry.extents, EntryData.Embedded(bytes))
}

private fun UdfVolume.nestedBdmv(children: List<IsoEntry>): IsoEntry? {
    for (child in children) {
        if (!child.directory) continue
        val nested = try {
            find(child, ISO_BDMV_PATH)
        } catch (e: IsoFormatException) {
            continue
        }
        if (nested != null) return nested
    }
    return null
}

private fun directoryAlias(entry: IsoEntry): IsoEntry =
    IsoEntry(ISO_BDMV_PATH, true, entry.length, entry.extents, EntryData.Extents(entry.extents))

private fun IsoEntry.needsInline(): Boolean =
    !directory && length in 1..INLINE_ENTRY_LIMIT && data !is EntryData.Embedded

private const val REQUIRED_DIRECTORIES = 3
private const val INLINE_ENTRY_LIMIT = 8L * 1024L
