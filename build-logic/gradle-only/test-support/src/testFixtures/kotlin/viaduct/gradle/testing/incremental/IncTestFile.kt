package viaduct.gradle.testing.incremental

import java.io.File
import org.gradle.work.ChangeType

/**
 * Represents the state of a file system _after_ a set of changes
 * have been applied to it.
 *
 * Each node is either a leaf file ([files] is `null`) or a directory
 * ([files] is non-null). The [status] records what change was applied:
 * [ChangeType.ADDED], [ChangeType.MODIFIED], [ChangeType.REMOVED], or
 * `null` (unchanged).
 *
 * Use [writeInto] to materialize the post-change file tree on disk, and
 * [IncTestFS] to generate both the on-disk tree and Gradle [FileChange]
 * lists from this structure.
 */
class IncTestFile(
    val name: String,
    /**
     * The change applied to this file, e.g., if this is [ChangeType.ADDED]
     * then the change-set added this file to the file system. `null` means
     * the file wasn't changed by the change-set.
     */
    val status: ChangeType?,
    /**
     * `null` means this is a leaf file, non-null means it's a directory.
     * If [status] is [ChangeType.REMOVED], then the status of all members
     * must also be [ChangeType.REMOVED].
     */
    val files: List<IncTestFile>?,
) {
    /**
     * Materialize the post-change file tree on disk under [parent].
     *
     * Removed entries are skipped (they don't exist after the change).
     * All other entries (added, modified, unchanged) are created as
     * empty files or directories.
     */
    fun writeInto(parent: File) {
        val target = File(parent, name)

        if (status == ChangeType.REMOVED) {
            return
        }

        if (files != null) {
            target.mkdirs()
            for (child in files) {
                child.writeInto(target)
            }
        } else {
            target.parentFile.mkdirs()
            target.createNewFile()
        }
    }
}
