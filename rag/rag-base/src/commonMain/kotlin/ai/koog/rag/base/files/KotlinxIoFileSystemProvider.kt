@file:Suppress("MissingKDocForPublicAPI")

package ai.koog.rag.base.files

import ai.koog.rag.base.files.FileMetadata.FileContentType
import ai.koog.rag.base.files.FileMetadata.FileType
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * File system provider implemented with kotlinx-io, so the same implementation works on JVM, Android and native
 * targets (unlike [JVMFileSystemProvider], which is bound to `java.nio.file.Path`).
 *
 * Semantics mirror [JVMFileSystemProvider]: absolute path strings, directory listings sorted by name, and
 * text/binary detection by probing the file head. Blocking file operations run on the caller's dispatcher.
 */
public object KotlinxIoFileSystemProvider {
    public object ReadOnly : FileSystemProvider.ReadOnly<Path> {
        override fun toAbsolutePathString(path: Path): String = path.toString()

        override fun fromAbsolutePathString(path: String): Path {
            val resolvedPath = Path(path)
            require(resolvedPath.isAbsolute) { "Resolved path must be absolute: $path" }
            return resolvedPath
        }

        override fun joinPath(base: Path, vararg parts: String): Path {
            parts.forEach { part ->
                require(!Path(part).isAbsolute) { "Path must be relative, but was absolute: $part" }
            }
            return Path(base, *parts)
        }

        override fun name(path: Path): String = path.name

        override fun extension(path: Path): String = path.name.substringAfterLast('.', "")

        override suspend fun metadata(path: Path): FileMetadata? = metadataOrNull(path)

        override suspend fun getFileContentType(path: Path): FileContentType {
            require(SystemFileSystem.exists(path)) { "Path must exist" }
            require(metadataOrNull(path)?.type == FileType.File) { "Path must be a regular file" }
            return if (isHeadTextBased(path)) FileContentType.Text else FileContentType.Binary
        }

        override suspend fun list(directory: Path): List<Path> {
            require(SystemFileSystem.exists(directory)) { "Path must exist" }
            require(metadataOrNull(directory)?.type == FileType.Directory) { "Path must be a directory" }
            return SystemFileSystem.list(directory).sortedBy { it.name }
        }

        override fun parent(path: Path): Path? = path.parent

        override fun relativize(root: Path, path: Path): String? {
            val rootString = root.toString().trimEnd(PATH_SEPARATOR)
            val pathString = path.toString()
            if (rootString.isEmpty()) return pathString
            if (!pathString.startsWith(rootString)) return null
            if (pathString.length == rootString.length) return ""
            if (pathString[rootString.length] != PATH_SEPARATOR) return null
            return pathString.substring(rootString.length + 1)
        }

        override suspend fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

        override suspend fun readBytes(path: Path): ByteArray {
            require(SystemFileSystem.exists(path)) { "Path must exist" }
            require(metadataOrNull(path)?.type == FileType.File) { "Path must be a regular file" }
            return SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        }

        override suspend fun inputStream(path: Path): Source {
            require(SystemFileSystem.exists(path)) { "Path must exist" }
            require(metadataOrNull(path)?.type == FileType.File) { "Path must be a regular file" }
            return SystemFileSystem.source(path).buffered()
        }

        override suspend fun size(path: Path): Long {
            require(SystemFileSystem.exists(path)) { "Path must exist" }
            val metadata = SystemFileSystem.metadataOrNull(path)
            require(metadata?.isRegularFile == true) { "Path must be a regular file" }
            return metadata.size
        }

        internal fun metadataOrNull(path: Path): FileMetadata? =
            SystemFileSystem.metadataOrNull(path)?.let { ioMetadata ->
                val hidden = path.name.startsWith(HIDDEN_NAME_PREFIX)
                when {
                    ioMetadata.isRegularFile -> FileMetadata(FileType.File, hidden)
                    ioMetadata.isDirectory -> FileMetadata(FileType.Directory, hidden)
                    else -> null
                }
            }

        /**
         * Probes the file head like the JVM provider does: no NUL bytes and valid UTF-8 means text.
         * Empty files are reported as binary; the tools treat them as text explicitly by size.
         */
        private fun isHeadTextBased(path: Path): Boolean = runCatching {
            val size = SystemFileSystem.metadataOrNull(path)?.size ?: 0L
            val headSize = minOf(size, TEXT_PROBE_MAX_BYTES.toLong()).toInt()
            if (headSize <= 0) return false

            val head = SystemFileSystem.source(path).buffered().use { it.readByteArray(headSize) }
            if (head.any { it == 0.toByte() }) return false
            head.decodeToString(throwOnInvalidSequence = true)
            true
        }.getOrDefault(false)

        private const val TEXT_PROBE_MAX_BYTES = 8_000
        private const val HIDDEN_NAME_PREFIX = '.'
        private const val PATH_SEPARATOR = '/'
    }

    public object ReadWrite : FileSystemProvider.ReadWrite<Path>, FileSystemProvider.ReadOnly<Path> by ReadOnly {
        override suspend fun create(path: Path, type: FileType) {
            if (SystemFileSystem.exists(path)) throw IOException("Path already exists: $path")
            createParentDirectories(path)

            when (type) {
                FileType.File -> SystemFileSystem.sink(path).buffered().use { }
                FileType.Directory -> SystemFileSystem.createDirectories(path, mustCreate = true)
            }
        }

        override suspend fun move(source: Path, target: Path) {
            if (SystemFileSystem.exists(target)) throw IOException("Target path already exists: $target")
            if (!SystemFileSystem.exists(source)) throw IOException("Source path doesn't exist: $source")

            createParentDirectories(target)
            SystemFileSystem.atomicMove(source, target)
        }

        override suspend fun copy(source: Path, target: Path) {
            if (SystemFileSystem.exists(target)) throw IOException("Target path already exists: $target")
            if (!SystemFileSystem.exists(source)) throw IOException("Source path doesn't exist: $source")

            copyRecursively(source, target)
        }

        override suspend fun writeBytes(path: Path, data: ByteArray) {
            createParentDirectories(path)
            SystemFileSystem.sink(path).buffered().use { it.write(data) }
        }

        override suspend fun outputStream(path: Path, append: Boolean): Sink {
            createParentDirectories(path)
            return SystemFileSystem.sink(path, append = append).buffered()
        }

        override suspend fun delete(path: Path) {
            if (!SystemFileSystem.exists(path)) throw FileNotFoundException("Path doesn't exist: $path")
            deleteRecursively(path)
        }

        private fun copyRecursively(source: Path, target: Path) {
            val metadata = SystemFileSystem.metadataOrNull(source)
                ?: throw FileNotFoundException("Source path doesn't exist: $source")

            when {
                metadata.isDirectory -> {
                    SystemFileSystem.createDirectories(target)
                    SystemFileSystem.list(source).forEach { child -> copyRecursively(child, Path(target, child.name)) }
                }

                metadata.isRegularFile -> {
                    createParentDirectories(target)
                    SystemFileSystem.source(source).buffered().use { input ->
                        SystemFileSystem.sink(target).buffered().use { output -> input.transferTo(output) }
                    }
                }

                else -> throw IOException("Source path is neither a file nor a directory: $source")
            }
        }

        private fun deleteRecursively(path: Path) {
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                SystemFileSystem.list(path).forEach { child -> deleteRecursively(child) }
            }
            SystemFileSystem.delete(path)
        }

        private fun createParentDirectories(path: Path) {
            path.parent?.let { SystemFileSystem.createDirectories(it) }
        }
    }
}
