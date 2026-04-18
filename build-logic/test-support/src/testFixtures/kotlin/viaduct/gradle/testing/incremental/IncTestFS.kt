package viaduct.gradle.testing.incremental

import java.io.File
import org.gradle.api.file.FileType
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.FileChange

/**
 * Materializes a list of [IncTestFile] entries on disk and generates Gradle
 * [FileChange] lists for subtrees, suitable for testing incremental
 * Gradle tasks.
 *
 * On construction, each entry in [entries] is written directly under [root]
 * via [IncTestFile.writeInto]. Call [toChanges] with a `/`-separated
 * path to get the change list for a subtree (corresponding to one
 * `@Incremental` input property).
 *
 * @param root the directory to materialize the file tree into
 *     (typically a JUnit `@TempDir`)
 * @param entries the top-level files and directories to create under [root]
 * @param pathSensitivity controls how [FileChange.getNormalizedPath]
 *     is computed, matching the `@PathSensitive` annotation on the
 *     Gradle input property
 */
class IncTestFS(
    val root: File,
    private val entries: List<IncTestFile>,
    private val pathSensitivity: PathSensitivity = PathSensitivity.RELATIVE,
) {
    init {
        for (entry in entries) {
            entry.writeInto(root)
        }
    }

    /**
     * Returns the [FileChange] list for the subtree at [path].
     *
     * [path] is a `/`-separated string identifying a directory node
     * under [root] (e.g., `"descriptors"` or `"schemas"`). The returned
     * changes have [FileChange.getFile] resolved against [root] and
     * [FileChange.getNormalizedPath] computed per [pathSensitivity].
     */
    fun toChanges(path: String): List<FileChange> {
        val subtree = resolve(path)
        val subtreeRoot = root.resolve(path)
        val changes = mutableListOf<FileChange>()
        collectChildren(subtree, subtreeRoot, subtreeRoot, changes)
        return changes
    }

    private fun resolve(path: String): IncTestFile {
        var current: List<IncTestFile> = entries
        var resolved: IncTestFile? = null
        for (segment in path.split("/")) {
            resolved = current.firstOrNull { it.name == segment }
                ?: error("No child named '$segment' while resolving '$path'")
            current = resolved.files
                ?: error("Expected directory at '${resolved.name}' while resolving '$path'")
        }
        return requireNotNull(resolved) { "Path must not be empty" }
    }

    /**
     * Recursively collects [FileChange] entries for leaf nodes with non-null status.
     * [dir] is the resolved directory node; [dirPath] is its on-disk location.
     */
    private fun collectChildren(
        dir: IncTestFile,
        subtreeRoot: File,
        dirPath: File,
        out: MutableList<FileChange>,
    ) {
        val children = dir.files ?: return
        for (child in children) {
            val childPath = File(dirPath, child.name)
            if (child.files != null) {
                collectChildren(child, subtreeRoot, childPath, out)
            } else if (child.status != null) {
                out.add(
                    TestFileChange(
                        file = childPath,
                        changeType = child.status,
                        normalizedPath = normalizedPath(childPath, subtreeRoot),
                        fileType = FileType.FILE,
                    ),
                )
            }
        }
    }

    private fun normalizedPath(
        file: File,
        subtreeRoot: File
    ): String =
        when (pathSensitivity) {
            PathSensitivity.RELATIVE -> file.relativeTo(subtreeRoot).path
            PathSensitivity.NAME_ONLY -> file.name
            PathSensitivity.ABSOLUTE -> file.absolutePath
            PathSensitivity.NONE -> file.absolutePath
        }
}
