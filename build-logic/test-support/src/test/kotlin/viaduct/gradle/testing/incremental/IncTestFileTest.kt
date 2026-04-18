package viaduct.gradle.testing.incremental

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.work.ChangeType
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
        assertThat(file).exists().isFile
        assertThat(file.length()).isZero()
    }

    @Test
    fun `ADDED leaf file is created on disk`(
        @TempDir root: File
    ) {
        val leaf = IncTestFile("added.txt", status = ChangeType.ADDED, files = null)
        leaf.writeInto(root)

        assertThat(File(root, "added.txt")).exists().isFile
    }

    @Test
    fun `MODIFIED leaf file is created on disk`(
        @TempDir root: File
    ) {
        val leaf = IncTestFile("mod.txt", status = ChangeType.MODIFIED, files = null)
        leaf.writeInto(root)

        assertThat(File(root, "mod.txt")).exists().isFile
    }

    @Test
    fun `REMOVED leaf file is not created on disk`(
        @TempDir root: File
    ) {
        val leaf = IncTestFile("gone.txt", status = ChangeType.REMOVED, files = null)
        leaf.writeInto(root)

        assertThat(File(root, "gone.txt")).doesNotExist()
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

        assertThat(File(root, "mydir")).exists().isDirectory
        assertThat(File(root, "mydir/a.txt")).exists().isFile
        assertThat(File(root, "mydir/b.txt")).exists().isFile
    }

    @Test
    fun `empty directory is created on disk`(
        @TempDir root: File
    ) {
        val dir = IncTestFile("empty", status = null, files = emptyList())
        dir.writeInto(root)

        val d = File(root, "empty")
        assertThat(d).exists().isDirectory
        assertThat(d.listFiles()).isEmpty()
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

        assertThat(File(root, "a")).isDirectory
        assertThat(File(root, "a/b")).isDirectory
        assertThat(File(root, "a/b/deep.txt")).isFile
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

        assertThat(File(root, "gone")).doesNotExist()
        assertThat(File(root, "gone/child.txt")).doesNotExist()
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

        assertThat(File(root, "mix/added.txt")).exists()
        assertThat(File(root, "mix/modified.txt")).exists()
        assertThat(File(root, "mix/unchanged.txt")).exists()
        assertThat(File(root, "mix/removed.txt")).doesNotExist()
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

        assertThat(File(root, "dir")).isDirectory
        assertThat(File(root, "dir/keep.txt")).exists()
        assertThat(File(root, "dir/drop.txt")).doesNotExist()
    }
}
