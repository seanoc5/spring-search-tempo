package com.oconeco.remotecrawler.util

import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object DriveDetector {
    private val log = LoggerFactory.getLogger(javaClass)

    data class DriveInfo(
        val path: Path,
        val label: String,
        val totalSpace: Long,
        val usableSpace: Long,
        val isRemovable: Boolean = false
    ) {
        val displayName: String
            get() = if (label.isNotBlank()) "$path ($label)" else path.toString()

        val totalSpaceGb: Double
            get() = totalSpace / (1024.0 * 1024.0 * 1024.0)

        val usableSpaceGb: Double
            get() = usableSpace / (1024.0 * 1024.0 * 1024.0)
    }

    fun detectDrives(): List<DriveInfo> {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> detectWindowsDrives()
            osName.contains("mac") -> detectMacDrives()
            else -> detectLinuxDrives()
        }
    }

    fun getSuggestedRoots(): List<DriveInfo> {
        val osName = System.getProperty("os.name").lowercase()
        return if (osName.contains("win")) {
            detectWindowsDrives()
        } else {
            detectDrives().filter { it.path == Paths.get("/") }
        }
    }

    private fun detectWindowsDrives(): List<DriveInfo> {
        val drives = mutableListOf<DriveInfo>()
        for (root in FileSystems.getDefault().rootDirectories) {
            try {
                val store = Files.getFileStore(root)
                if (store.totalSpace == 0L) continue
                drives.add(
                    DriveInfo(
                        path = root,
                        label = store.name() ?: "",
                        totalSpace = store.totalSpace,
                        usableSpace = store.usableSpace,
                        isRemovable = store.type().contains("Removable", ignoreCase = true)
                    )
                )
            } catch (e: Exception) {
                log.debug("Skipping inaccessible drive: {} - {}", root, e.message)
            }
        }
        return drives.sortedBy { it.path.toString() }
    }

    private fun detectMacDrives(): List<DriveInfo> {
        val roots = mutableListOf<DriveInfo>()
        addRootIfAccessible(Paths.get("/"), roots)
        val volumes = Paths.get("/Volumes")
        if (Files.isDirectory(volumes)) {
            try {
                Files.list(volumes).use { stream ->
                    stream.forEach { volume ->
                        if (!Files.isSymbolicLink(volume)) {
                            addRootIfAccessible(volume, roots)
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("Could not list /Volumes: {}", e.message)
            }
        }
        return roots
    }

    private fun detectLinuxDrives(): List<DriveInfo> {
        val roots = mutableListOf<DriveInfo>()
        addRootIfAccessible(Paths.get("/"), roots)
        val mountPoints = listOf("/mnt", "/media")
        for (mountPoint in mountPoints) {
            val path = Paths.get(mountPoint)
            if (Files.isDirectory(path)) {
                try {
                    Files.list(path).use { stream ->
                        stream.forEach { mount ->
                            if (Files.isDirectory(mount) && !Files.isSymbolicLink(mount)) {
                                addRootIfAccessible(mount, roots)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.debug("Could not list {}: {}", mountPoint, e.message)
                }
            }
        }
        val runMedia = Paths.get("/run/media")
        if (Files.isDirectory(runMedia)) {
            try {
                Files.list(runMedia).use { userDirs ->
                    userDirs.forEach { userDir ->
                        if (Files.isDirectory(userDir)) {
                            Files.list(userDir).use { mounts ->
                                mounts.forEach { mount ->
                                    addRootIfAccessible(mount, roots)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("Could not list /run/media: {}", e.message)
            }
        }
        return roots
    }

    private fun addRootIfAccessible(path: Path, roots: MutableList<DriveInfo>) {
        try {
            if (!Files.isDirectory(path) || !Files.isReadable(path)) return
            val store = Files.getFileStore(path)
            val fsType = store.type().lowercase()
            if (fsType in VIRTUAL_FS_TYPES) {
                log.debug("Skipping virtual filesystem: {} ({})", path, fsType)
                return
            }
            roots.add(
                DriveInfo(
                    path = path,
                    label = store.name() ?: path.fileName?.toString() ?: "",
                    totalSpace = store.totalSpace,
                    usableSpace = store.usableSpace
                )
            )
        } catch (e: Exception) {
            log.debug("Skipping inaccessible path: {} - {}", path, e.message)
        }
    }

    private val VIRTUAL_FS_TYPES = setOf(
        "proc", "sysfs", "devfs", "devtmpfs", "tmpfs",
        "cgroup", "cgroup2", "securityfs", "debugfs",
        "pstore", "bpf", "tracefs", "fusectl", "configfs",
        "hugetlbfs", "mqueue", "overlay", "autofs"
    )
}
