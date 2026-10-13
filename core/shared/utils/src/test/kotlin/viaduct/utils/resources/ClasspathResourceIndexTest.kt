package viaduct.utils.resources

import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ClasspathResourceIndexTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads sorted exact resource paths`() {
        writeResource(tempDir, INDEX_PATH, "resources/a.txt\nresources/b.txt\n")
        writeResource(tempDir, "resources/a.txt", "alpha")
        writeResource(tempDir, "resources/b.txt", "beta")

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            val index = ClasspathResourceIndex.load(INDEX_PATH, classLoader)

            assertThat(index?.paths).containsExactly("resources/a.txt", "resources/b.txt")
            assertThat(index?.resourceUrl("resources/b.txt")?.readText()).isEqualTo("beta")
        }
    }

    @Test
    fun `returns null when the index is absent and accepts an empty index`() {
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThat(ClasspathResourceIndex.load(INDEX_PATH, classLoader)).isNull()
        }

        writeResource(tempDir, INDEX_PATH, "")
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThat(ClasspathResourceIndex.load(INDEX_PATH, classLoader)?.paths).isEmpty()
        }
    }

    @Test
    fun `rejects duplicate indexes and unsorted entries`() {
        val otherRoot = tempDir.resolve("other").createDirectories()
        writeResource(tempDir, INDEX_PATH, "resources/a.txt\n")
        writeResource(otherRoot, INDEX_PATH, "resources/a.txt\n")

        URLClassLoader(arrayOf(tempDir.toUri().toURL(), otherRoot.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy { ClasspathResourceIndex.load(INDEX_PATH, classLoader) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Expected exactly one resource index")
        }

        otherRoot.toFile().deleteRecursively()
        writeResource(tempDir, INDEX_PATH, "resources/b.txt\nresources/a.txt\n")
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy { ClasspathResourceIndex.load(INDEX_PATH, classLoader) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must be sorted")
        }
    }

    @Test
    fun `requires exactly one occurrence of an indexed resource`() {
        writeResource(tempDir, INDEX_PATH, "resources/missing.txt\n")

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            val index = ClasspathResourceIndex.load(INDEX_PATH, classLoader)
            assertThatThrownBy { index?.resourceUrl("resources/missing.txt") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("found 0")
        }
    }

    @Test
    fun `rejects ambiguous indexed resources`() {
        val firstRoot = tempDir.resolve("first")
        val secondRoot = tempDir.resolve("second")
        writeResource(firstRoot, INDEX_PATH, "resources/a.txt\n")
        writeResource(firstRoot, "resources/a.txt", "first")
        writeResource(secondRoot, "resources/a.txt", "second")

        URLClassLoader(arrayOf(firstRoot.toUri().toURL(), secondRoot.toUri().toURL()), null).use { classLoader ->
            val index = ClasspathResourceIndex.load(INDEX_PATH, classLoader)!!

            assertThatThrownBy { index.resourceUrl("resources/a.txt") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("resources/a.txt")
                .hasMessageContaining("found 2")
        }
    }

    @Test
    fun `does not load resources outside the index`() {
        writeResource(tempDir, INDEX_PATH, "")
        writeResource(tempDir, "resources/unlisted.txt", "unlisted")

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            val index = ClasspathResourceIndex.load(INDEX_PATH, classLoader)!!

            assertThatThrownBy { index.resourceUrl("resources/unlisted.txt") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("not present in this index")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/resources/a.txt", "resources/../a.txt", "resources/./a.txt", "resources/"])
    fun `rejects invalid resource paths`(path: String) {
        writeResource(tempDir, INDEX_PATH, "$path\n")

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy { ClasspathResourceIndex.load(INDEX_PATH, classLoader) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("invalid resource paths")
        }
    }

    @Test
    fun `rejects blank and duplicate index entries`() {
        writeResource(tempDir, INDEX_PATH, "\n")
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy { ClasspathResourceIndex.load(INDEX_PATH, classLoader) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("blank entries")
        }

        writeResource(tempDir, INDEX_PATH, "resources/a.txt\nresources/a.txt\n")
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy { ClasspathResourceIndex.load(INDEX_PATH, classLoader) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("duplicate entries")
        }
    }

    private fun writeResource(
        root: Path,
        path: String,
        contents: String,
    ) {
        root.resolve(path).apply {
            parent.createDirectories()
            writeText(contents)
        }
    }

    private companion object {
        const val INDEX_PATH = "META-INF/viaduct/indexes/test.index"
    }
}
