package viaduct.tenant.codegen.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtil {
    fun File.listTopDownFiles(): List<File> = Files.walk(toPath()).map(Path::toFile).collect(Collectors.toList())

    // Sometimes we want to zip the contents of a temporary directory without including the temporary directory name
    fun File.zipAndWriteChildren(source: File) = zipAndWriteDirectories(stripPrefix = source.name, source)

    // Similar to zipAndWriteChildren, but avoids emitting an entry for the source directory itself.
    // This is useful when producing a jar-like archive rooted at the children of a temp output dir.
    fun File.zipAndWriteChildrenAsRoot(source: File) {
        ZipOutputStream(outputStream()).use { out ->
            for (item in source.listFiles().orEmpty().sortedBy(File::getAbsolutePath)) {
                if (item.isDirectory) {
                    item.listTopDownFiles().sortedBy(File::getAbsolutePath).forEach { sourceFile ->
                        val zipPath = if (sourceFile == item) item.name else item.name + "/" + sourceFile.relativeTo(item)
                        out.addZipEntry(zipPath, sourceFile)
                    }
                } else {
                    out.addZipEntry(item.name, item)
                }
            }
        }
    }

    fun File.zipAndWriteDirectories(vararg sources: File?) = zipAndWriteDirectories("", *sources)

    /**
     * Writes the contents of [sources] and their children to [this] [File] location using a [ZipOutputStream] in the
     * relative file structure of the input files.
     */
    fun File.zipAndWriteDirectories(
        stripPrefix: String = "",
        vararg sources: File?
    ) {
        ZipOutputStream(outputStream()).use { out ->
            for (item in sources) {
                if (item == null) {
                    continue
                } else if (item.isDirectory) {
                    item.listTopDownFiles().sortedBy(File::getAbsolutePath).forEach { sourceFile ->
                        // Because we are zipping multiple directories prefix each file by the source directory name
                        val zipPath = item.name + "/" + sourceFile.relativeTo(item)
                        out.addZipEntry(zipPath.removePrefix(stripPrefix).trimStart('/'), sourceFile)
                    }
                } else {
                    out.addZipEntry(item.name.removePrefix(stripPrefix).trimStart('/'), item)
                }
            }
        }
    }

    private fun ZipOutputStream.addZipEntry(
        zipPath: String,
        sourceFile: File
    ) {
        if (sourceFile.isDirectory) {
            val entry = ZipEntry("$zipPath/")
            zeroOutTimestamps(entry)
            putNextEntry(entry)
        } else {
            val entry = ZipEntry(zipPath)
            zeroOutTimestamps(entry)
            putNextEntry(entry)
            Files.copy(sourceFile.toPath(), this)
        }
        closeEntry()
    }

    private fun zeroOutTimestamps(entry: ZipEntry) {
        entry.lastAccessTime = FileTime.fromMillis(0)
        entry.lastModifiedTime = FileTime.fromMillis(0)
    }
}
