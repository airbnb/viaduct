package viaduct.gradle.testing.incremental

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.api.file.FileType
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.ChangeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

        assertTrue(File(root, "sub/a.txt").exists())
        assertTrue(File(root, "sub/b.txt").exists())
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

        fs.toChanges("sub").shouldBeEmpty()
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

        changes.shouldHaveSize(1)
        assertEquals(ChangeType.ADDED, changes[0].changeType)
        assertEquals(FileType.FILE, changes[0].fileType)
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

        changes.shouldHaveSize(1)
        assertEquals(ChangeType.MODIFIED, changes[0].changeType)
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

        changes.shouldHaveSize(1)
        assertEquals(ChangeType.REMOVED, changes[0].changeType)
        assertFalse(changes[0].file.exists())
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

        changes.shouldHaveSize(3)
        changes.map { it.changeType }.shouldContainExactly(
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

        changes.shouldHaveSize(1)
        assertEquals("leaf.json", changes[0].file.name)
        assertEquals(FileType.FILE, changes[0].fileType)
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

        changes.shouldHaveSize(1)
        assertEquals("deep.json", changes[0].file.name)
    }

    @Test
    fun `toChanges on empty directory returns empty list`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile("empty", status = null, files = emptyList()),
        )
        val fs = IncTestFS(root, tree)

        fs.toChanges("empty").shouldBeEmpty()
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

        changes.map { it.file.name }.shouldContainExactly("z.json", "a.json", "m.json")
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

        fs.toChanges("schemas").shouldHaveSize(1)
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

        changes.shouldHaveSize(1)
        assertEquals("file.txt", changes[0].file.name)
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

        val e1 = assertThrows<IllegalStateException> { fs.toChanges("dir/leaf.txt/deeper") }
        e1.message!! shouldContain "Expected directory"
    }

    @Test
    fun `toChanges throws on nonexistent path segment`(
        @TempDir root: File
    ) {
        val tree = entries(
            IncTestFile("sub", status = null, files = emptyList()),
        )
        val fs = IncTestFS(root, tree)

        val e2 = assertThrows<IllegalStateException> { fs.toChanges("nonexistent") }
        e2.message!! shouldContain "No child named"
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
        assertEquals(expected.canonicalPath, changes[0].file.canonicalPath)
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

        assertEquals("pkg${File.separator}f.json", changes[0].normalizedPath)
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

        assertEquals("direct.json", changes[0].normalizedPath)
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

        assertEquals("f.json", changes[0].normalizedPath)
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
        assertEquals(expected, changes[0].normalizedPath)
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
        assertEquals(expected, changes[0].normalizedPath)
    }
}
