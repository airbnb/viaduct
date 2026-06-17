package viaduct.gradle.testing.incremental

import java.io.File
import org.gradle.work.ChangeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IncTestFileTest {
    @Test
    fun `unchanged leaf file is created on disk`(
        @TempDir root: File
    ) {
        val leaf = IncTestFile("hello.txt", status = null, files = null)
        leaf.writeInto(root)

        val file = File(root, "hello.txt")
        assertTrue(file.exists())
        assertTrue(file.isFile)
        assertEquals(0L, file.length())
    }

    @Test
    fun `ADDED leaf file is created on disk`(
        @TempDir root: File
    ) {
        val leaf = IncTestFile("added.txt", status = ChangeType.ADDED, files = null)
        leaf.writeInto(root)

        assertTrue(File(root, "added.txt").exists())
        assertTrue(File(root, "added.txt").isFile)
    }

    @Test
    fun `MODIFIED leaf file is created on disk`(
        @TempDir root: File
    ) {
        val leaf = IncTestFile("mod.txt", status = ChangeType.MODIFIED, files = null)
        leaf.writeInto(root)

        assertTrue(File(root, "mod.txt").exists())
        assertTrue(File(root, "mod.txt").isFile)
    }

    @Test
    fun `REMOVED leaf file is not created on disk`(
        @TempDir root: File
    ) {
        val leaf = IncTestFile("gone.txt", status = ChangeType.REMOVED, files = null)
        leaf.writeInto(root)

        assertFalse(File(root, "gone.txt").exists())
    }

    @Test
    fun `directory with children creates directory and child files`(
        @TempDir root: File
    ) {
        val dir = IncTestFile(
            "mydir",
            status = null,
            files = listOf(
                IncTestFile("a.txt", status = null, files = null),
                IncTestFile("b.txt", status = null, files = null),
            ),
        )
        dir.writeInto(root)

        assertTrue(File(root, "mydir").exists())
        assertTrue(File(root, "mydir").isDirectory)
        assertTrue(File(root, "mydir/a.txt").exists())
        assertTrue(File(root, "mydir/a.txt").isFile)
        assertTrue(File(root, "mydir/b.txt").exists())
        assertTrue(File(root, "mydir/b.txt").isFile)
    }

    @Test
    fun `empty directory is created on disk`(
        @TempDir root: File
    ) {
        val dir = IncTestFile("empty", status = null, files = emptyList())
        dir.writeInto(root)

        val d = File(root, "empty")
        assertTrue(d.exists())
        assertTrue(d.isDirectory)
        assertTrue(d.listFiles()!!.isEmpty())
    }

    @Test
    fun `nested directories are created recursively`(
        @TempDir root: File
    ) {
        val tree = IncTestFile(
            "a",
            status = null,
            files = listOf(
                IncTestFile(
                    "b",
                    status = null,
                    files = listOf(
                        IncTestFile("deep.txt", status = null, files = null),
                    ),
                ),
            ),
        )
        tree.writeInto(root)

        assertTrue(File(root, "a").isDirectory)
        assertTrue(File(root, "a/b").isDirectory)
        assertTrue(File(root, "a/b/deep.txt").isFile)
    }

    @Test
    fun `REMOVED directory skips entire subtree`(
        @TempDir root: File
    ) {
        val dir = IncTestFile(
            "gone",
            status = ChangeType.REMOVED,
            files = listOf(
                IncTestFile("child.txt", status = ChangeType.REMOVED, files = null),
            ),
        )
        dir.writeInto(root)

        assertFalse(File(root, "gone").exists())
        assertFalse(File(root, "gone/child.txt").exists())
    }

    @Test
    fun `mixed tree creates non-REMOVED entries and skips REMOVED entries`(
        @TempDir root: File
    ) {
        val dir = IncTestFile(
            "mix",
            status = null,
            files = listOf(
                IncTestFile("added.txt", status = ChangeType.ADDED, files = null),
                IncTestFile("modified.txt", status = ChangeType.MODIFIED, files = null),
                IncTestFile("unchanged.txt", status = null, files = null),
                IncTestFile("removed.txt", status = ChangeType.REMOVED, files = null),
            ),
        )
        dir.writeInto(root)

        assertTrue(File(root, "mix/added.txt").exists())
        assertTrue(File(root, "mix/modified.txt").exists())
        assertTrue(File(root, "mix/unchanged.txt").exists())
        assertFalse(File(root, "mix/removed.txt").exists())
    }

    @Test
    fun `REMOVED child inside non-REMOVED directory is skipped`(
        @TempDir root: File
    ) {
        val dir = IncTestFile(
            "dir",
            status = null,
            files = listOf(
                IncTestFile("keep.txt", status = ChangeType.ADDED, files = null),
                IncTestFile("drop.txt", status = ChangeType.REMOVED, files = null),
            ),
        )
        dir.writeInto(root)

        assertTrue(File(root, "dir").isDirectory)
        assertTrue(File(root, "dir/keep.txt").exists())
        assertFalse(File(root, "dir/drop.txt").exists())
    }
}
