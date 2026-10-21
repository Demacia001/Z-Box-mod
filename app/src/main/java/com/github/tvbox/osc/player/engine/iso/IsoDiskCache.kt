package com.github.tvbox.osc.player.engine.iso

import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.LOG
import java.io.File

internal object IsoDiskCache {

    @Volatile
    private var legacyDropped = false

    fun load(key: String): IsoVolumeSnapshot? {
        val dir = directory()
        if (dir == null) {
            LOG.i("echo-iso-cache-nodir")
            return null
        }
        return loadFrom(dir, key)
    }

    fun save(key: String, snapshot: IsoVolumeSnapshot): Int =
        saveBytes(key, snapshot.encode())

    fun saveBytes(key: String, bytes: ByteArray): Int {
        val dir = directory() ?: return 0
        return writeTo(dir, key, bytes)
    }

    fun evict(key: String) {
        val dir = directory() ?: return
        runCatching { File(dir, nameOf(key)).delete() }
    }

    fun clear() {
        val context = AppContextHolder.context() ?: return
        dropLegacy(context.cacheDir)
        val dir = File(context.filesDir, DIRECTORY)
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    fun bytesOnDisk(): Long {
        val context = AppContextHolder.context() ?: return 0
        return bytesIn(File(context.filesDir, DIRECTORY))
    }

    internal fun bytesIn(dir: File): Long {
        val files = dir.listFiles() ?: return 0
        return files.filter { it.isFile && it.name.endsWith(SUFFIX) }.sumOf { it.length() }
    }

    internal fun loadFrom(dir: File, key: String): IsoVolumeSnapshot? {
        val file = File(dir, nameOf(key))
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) {
            LOG.i("echo-iso-cache-miss:${file.name.takeLast(20)}:${file.length()}")
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            LOG.i("echo-iso-cache-read-fail:${e.javaClass.simpleName}")
            return null
        }
        val snapshot = IsoVolumeSnapshot.decode(bytes)
        if (snapshot == null) {
            runCatching { file.delete() }
            LOG.i("echo-iso-cache-decode-fail:${bytes.size}")
        }
        return snapshot
    }

    internal fun saveTo(dir: File, key: String, snapshot: IsoVolumeSnapshot): Int =
        writeTo(dir, key, snapshot.encode())

    internal fun writeTo(dir: File, key: String, bytes: ByteArray): Int {
        if (!dir.isDirectory && !dir.mkdirs()) {
            LOG.i("echo-iso-cache-nodir")
            return 0
        }
        if (bytes.size > MAX_BYTES) {
            LOG.i("echo-iso-cache-oversize:${bytes.size}")
            return 0
        }
        val name = nameOf(key)
        val target = File(dir, name)
        val temp = File(dir, name + TEMP_SUFFIX)
        return try {
            temp.writeBytes(bytes)
            if (target.exists() && !target.delete()) {
                temp.delete()
                LOG.i("echo-iso-cache-replace-fail")
                return 0
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                LOG.i("echo-iso-cache-rename-fail")
                return 0
            }
            trim(dir)
            bytes.size
        } catch (e: Exception) {
            LOG.i("echo-iso-cache-write-fail:${e.javaClass.simpleName}:${e.message}")
            0
        }
    }

    private fun directory(): File? {
        val context = AppContextHolder.context() ?: return null
        val dir = File(context.filesDir, DIRECTORY)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        dropLegacy(context.cacheDir)
        return dir
    }

    internal fun dropLegacyInto(cacheDir: File): Int {
        val legacy = File(cacheDir, DIRECTORY)
        if (!legacy.exists()) return 0
        val removed = legacy.listFiles()?.size ?: 0
        return if (runCatching { legacy.deleteRecursively() }.getOrDefault(false)) removed else 0
    }

    private fun dropLegacy(cacheDir: File) {
        if (legacyDropped) return
        legacyDropped = true
        val removed = dropLegacyInto(cacheDir)
        if (removed > 0) LOG.i("echo-iso-cache-legacy-drop:$removed")
    }

    private fun nameOf(key: String): String =
        key.replace(':', '_').replace('/', '_') + SUFFIX

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private const val DIRECTORY = "iso-volume"
    private const val SUFFIX = ".snap"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MAX_FILES = 8
    private const val MAX_BYTES = 1024L * 1024L
}
