package viaduct.gradle.testing.incremental

import java.io.File
import org.gradle.api.file.FileType
import org.gradle.work.ChangeType
import org.gradle.work.FileChange

/**
 * Simple [FileChange] implementation for unit tests.
 *
 * Gradle's [FileChange] is an interface returned by `InputChanges.getFileChanges()`.
 * This implementation lets tests construct change lists without running a real
 * Gradle build.
 */
internal class TestFileChange(
    private val file: File,
    private val changeType: ChangeType,
    private val normalizedPath: String,
    private val fileType: FileType,
) : FileChange {
    override fun getFile(): File = file

    override fun getChangeType(): ChangeType = changeType

    override fun getNormalizedPath(): String = normalizedPath

    override fun getFileType(): FileType = fileType
}
