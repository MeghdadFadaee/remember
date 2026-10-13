package meghdad.remember

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.nio.file.Files

data class StorageUsage(val total: Long, val available: Long, val appUsed: Long, val recordings: Long, val estimated: Boolean)

/** Called on a worker: Android's storage accounting can take several seconds. */
fun readStorageUsage(context: Context): StorageUsage {
    val manager = context.getSystemService(StorageStatsManager::class.java)
    val volume = context.applicationInfo.storageUuid ?: StorageManager.UUID_DEFAULT
    val filesystem = StatFs(context.dataDir.absolutePath)
    val total = runCatching { manager.getTotalBytes(volume) }.getOrDefault(filesystem.totalBytes)
    val available = runCatching { manager.getFreeBytes(volume) }.getOrDefault(filesystem.availableBytes).coerceIn(0, total)
    val stats = runCatching { manager.queryStatsForPackage(volume, context.packageName, Process.myUserHandle()) }.getOrNull()
    // dataBytes already includes cacheBytes; adding cache separately would count it twice.
    val appUsed = stats?.let { it.appBytes + it.dataBytes } ?: (
        directoryBytes(context.dataDir) + File(context.applicationInfo.sourceDir).length() +
            context.applicationInfo.splitSourceDirs.orEmpty().sumOf { File(it).length() } +
            context.getExternalFilesDirs(null).filterNotNull().sumOf { directoryBytes(it) } +
            context.externalCacheDirs.filterNotNull().sumOf { directoryBytes(it) }
        )
    return StorageUsage(total, available, appUsed, directoryBytes(File(context.filesDir, "recordings")), stats == null)
}

private fun directoryBytes(directory: File): Long = if (!directory.exists()) 0 else directory.walkTopDown()
    .onEnter { !Files.isSymbolicLink(it.toPath()) }
    .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }
