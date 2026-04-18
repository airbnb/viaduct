package viaduct.gradle.testing.incremental

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.file.FileType
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.ChangeType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IncTestFSTest {
    /** Helper: wraps children in a list for [IncTestFS]. */
    private fun entries(vararg children: IncTestFile) = children.toList()

    // ── Constructor ─────────────────────────────────────────────────────────

    @Test
    fun `constructor materializes tree on disk`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("a.txt", status = ChangeType.ADDED, files = null),
                    IncTestFile("b.txt", status = null, files = null),
                ),
            ),
        )
        IncTestFS(root, tree)

        assertThat(File(root, "sub/a.txt")).exists()
        assertThat(File(root, "sub/b.txt")).exists()
    }

    // ── toChanges: basic change collection ──────────────────────────────────

    @Test
    fun `toChanges returns empty list when all statuses are null`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("a.txt", status = null, files = null),
                    IncTestFile("b.txt", status = null, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)

        assertThat(fs.toChanges("sub")).isEmpty()
    }

    @Test
    fun `toChanges returns ADDED change`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("new.json", status = ChangeType.ADDED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        assertThat(changes).hasSize(1)
        assertThat(changes[0].changeType).isEqualTo(ChangeType.ADDED)
        assertThat(changes[0].fileType).isEqualTo(FileType.FILE)
    }

    @Test
    fun `toChanges returns MODIFIED change`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("mod.json", status = ChangeType.MODIFIED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        assertThat(changes).hasSize(1)
        assertThat(changes[0].changeType).isEqualTo(ChangeType.MODIFIED)
    }

    @Test
    fun `toChanges returns REMOVED change with nonexistent file`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("gone.json", status = ChangeType.REMOVED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        assertThat(changes).hasSize(1)
        assertThat(changes[0].changeType).isEqualTo(ChangeType.REMOVED)
        assertThat(changes[0].file).doesNotExist()
    }

    @Test
    fun `toChanges collects multiple changes and excludes unchanged`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("added.json", status = ChangeType.ADDED, files = null),
                    IncTestFile("unchanged.json", status = null, files = null),
                    IncTestFile("modified.json", status = ChangeType.MODIFIED, files = null),
                    IncTestFile("removed.json", status = ChangeType.REMOVED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        assertThat(changes).hasSize(3)
        assertThat(changes.map { it.changeType }).containsExactly(
            ChangeType.ADDED,
            ChangeType.MODIFIED,
            ChangeType.REMOVED,
        )
    }

    @Test
    fun `toChanges never includes directories`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile(
                        "nested",
                        status = ChangeType.ADDED,
                        files = listOf(
                            IncTestFile("leaf.json", status = ChangeType.ADDED, files = null),
                        ),
                    ),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        assertThat(changes).hasSize(1)
        assertThat(changes[0].file.name).isEqualTo("leaf.json")
        assertThat(changes[0].fileType).isEqualTo(FileType.FILE)
    }

    @Test
    fun `toChanges walks nested directories to find deep changes`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile(
                        "a",
                        status = null,
                        files = listOf(
                            IncTestFile(
                                "b",
                                status = null,
                                files = listOf(
                                    IncTestFile("deep.json", status = ChangeType.ADDED, files = null),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        assertThat(changes).hasSize(1)
        assertThat(changes[0].file.name).isEqualTo("deep.json")
    }

    @Test
    fun `toChanges on empty directory returns empty list`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile("empty", status = null, files = emptyList()),
        )
        val fs = IncTestFS(root, tree)

        assertThat(fs.toChanges("empty")).isEmpty()
    }

    @Test
    fun `toChanges preserves declaration order`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("z.json", status = ChangeType.ADDED, files = null),
                    IncTestFile("a.json", status = ChangeType.MODIFIED, files = null),
                    IncTestFile("m.json", status = ChangeType.REMOVED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        assertThat(changes.map { it.file.name }).containsExactly("z.json", "a.json", "m.json")
    }

    // ── toChanges: path resolution ──────────────────────────────────────────

    @Test
    fun `toChanges resolves single-segment path`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "schemas",
                status = null,
                files = listOf(
                    IncTestFile("s.graphql", status = ChangeType.MODIFIED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)

        assertThat(fs.toChanges("schemas")).hasSize(1)
    }

    @Test
    fun `toChanges resolves multi-segment path`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "a",
                status = null,
                files = listOf(
                    IncTestFile(
                        "b",
                        status = null,
                        files = listOf(
                            IncTestFile(
                                "c",
                                status = null,
                                files = listOf(
                                    IncTestFile("file.txt", status = ChangeType.ADDED, files = null),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("a/b/c")

        assertThat(changes).hasSize(1)
        assertThat(changes[0].file.name).isEqualTo("file.txt")
    }

    @Test
    fun `toChanges throws on path through leaf file`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "dir",
                status = null,
                files = listOf(
                    IncTestFile("leaf.txt", status = null, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)

        assertThatThrownBy { fs.toChanges("dir/leaf.txt/deeper") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Expected directory")
    }

    @Test
    fun `toChanges throws on nonexistent path segment`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile("sub", status = null, files = emptyList()),
        )
        val fs = IncTestFS(root, tree)

        assertThatThrownBy { fs.toChanges("nonexistent") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No child named")
    }

    // ── toChanges: file paths ───────────────────────────────────────────────

    @Test
    fun `change file points to correct absolute location`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile(
                        "pkg",
                        status = null,
                        files = listOf(
                            IncTestFile("file.json", status = ChangeType.ADDED, files = null),
                        ),
                    ),
                ),
            ),
        )
        val fs = IncTestFS(root, tree)
        val changes = fs.toChanges("sub")

        val expected = File(root, "sub/pkg/file.json")
        assertThat(changes[0].file.canonicalPath).isEqualTo(expected.canonicalPath)
    }

    // ── toChanges: PathSensitivity ──────────────────────────────────────────

    @Test
    fun `RELATIVE normalizedPath is relative to subtree root`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile(
                        "pkg",
                        status = null,
                        files = listOf(
                            IncTestFile("f.json", status = ChangeType.ADDED, files = null),
                        ),
                    ),
                ),
            ),
        )
        val fs = IncTestFS(root, tree, PathSensitivity.RELATIVE)
        val changes = fs.toChanges("sub")

        assertThat(changes[0].normalizedPath).isEqualTo("pkg${File.separator}f.json")
    }

    @Test
    fun `RELATIVE normalizedPath for direct child of subtree root`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("direct.json", status = ChangeType.ADDED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree, PathSensitivity.RELATIVE)
        val changes = fs.toChanges("sub")

        assertThat(changes[0].normalizedPath).isEqualTo("direct.json")
    }

    @Test
    fun `NAME_ONLY normalizedPath is just the file name`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile(
                        "deep",
                        status = null,
                        files = listOf(
                            IncTestFile("f.json", status = ChangeType.ADDED, files = null),
                        ),
                    ),
                ),
            ),
        )
        val fs = IncTestFS(root, tree, PathSensitivity.NAME_ONLY)
        val changes = fs.toChanges("sub")

        assertThat(changes[0].normalizedPath).isEqualTo("f.json")
    }

    @Test
    fun `ABSOLUTE normalizedPath is the full absolute path`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("f.json", status = ChangeType.ADDED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree, PathSensitivity.ABSOLUTE)
        val changes = fs.toChanges("sub")

        val expected = File(root, "sub/f.json").absolutePath
        assertThat(changes[0].normalizedPath).isEqualTo(expected)
    }

    @Test
    fun `NONE normalizedPath matches ABSOLUTE`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile(
                "sub",
                status = null,
                files = listOf(
                    IncTestFile("f.json", status = ChangeType.ADDED, files = null),
                ),
            ),
        )
        val fs = IncTestFS(root, tree, PathSensitivity.NONE)
        val changes = fs.toChanges("sub")

        val expected = File(root, "sub/f.json").absolutePath
        assertThat(changes[0].normalizedPath).isEqualTo(expected)
    }
}
