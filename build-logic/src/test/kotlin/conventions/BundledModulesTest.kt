package conventions

import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BundledModulesTest {
    @TempDir
    lateinit var dir: Path

    private fun jar(
        name: String,
        vararg entries: String
    ): File =
        dir.resolve(name).toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach {
                    zip.putNextEntry(ZipEntry(it))
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `list only the modules with a class in the jar after relocation`() {
        val shadowJar = jar(
            "shadow.jar",
            "module-info.class",
            "org/plain/Kept.class",
            "viaduct/Own.class",
            "viaduct/shaded/guava/collect/Lists.class",
            "META-INF/versions/11/viaduct/shaded/multirelease/Only.class",
        )
        val moduleFiles = listOf(
            "org.plain:plain" to jar("plain.jar", "org/plain/Kept.class"),
            "com.google.guava:guava" to jar("guava.jar", "com/google/common/collect/Lists.class"),
            "com.google.guava:guava" to jar("guava-copy.jar", "com/google/common/collect/Lists.class"),
            "org.excluded:excluded" to jar("excluded.jar", "org/excluded/Dropped.class"),
            "org.multirelease:multirelease" to jar("multirelease.jar", "META-INF/versions/11/org/multirelease/Only.class"),
            "org.modular:modular" to jar("modular.jar", "module-info.class", "META-INF/versions/9/module-info.class"),
            "org.directory:directory" to dir.toFile(),
        )
        val relocators = listOf(
            SimpleRelocator("com.google.common", "viaduct.shaded.guava", emptyList(), emptyList()),
            SimpleRelocator("org.multirelease", "viaduct.shaded.multirelease", emptyList(), emptyList()),
        )

        assertEquals(
            listOf("com.google.guava:guava", "org.multirelease:multirelease", "org.plain:plain"),
            modulesWithClassesIn(shadowJar, moduleFiles, relocators)
        )
    }
}
