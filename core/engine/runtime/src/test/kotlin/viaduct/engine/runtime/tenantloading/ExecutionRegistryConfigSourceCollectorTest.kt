package viaduct.engine.runtime.tenantloading

import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.bootstrap.ViaductResourceIndexes

class ExecutionRegistryConfigSourceCollectorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fromResources loads only indexed resources within the package boundary`() {
        val matchingPath = "META-INF/viaduct/execution-manifest/module/com.example.indexed/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.indexed.json"
        val packagePath = "META-INF/viaduct/execution-manifest/module/com.example/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.json"
        val lookalikePath = "META-INF/viaduct/execution-manifest/module/com.exampleish/generated/com.airbnb.viaduct.module-gradle-plugin/com.exampleish.json"
        val unrelatedPath = "META-INF/viaduct/execution-manifest/module/com.other.unrelated/generated/com.airbnb.viaduct.module-gradle-plugin/com.other.unrelated.json"
        val paths = listOf(matchingPath, packagePath, lookalikePath, unrelatedPath).sorted()
        writeResource(ViaductResourceIndexes.EXECUTION_REGISTRY, paths.joinToString("\n", postfix = "\n"))
        writeResource(matchingPath, registryJson("indexed"))
        writeResource(packagePath, registryJson("package"))
        writeResource(lookalikePath, "not a module config")
        writeResource(unrelatedPath, "not a module config")
        writeResource("META-INF/viaduct/execution-manifest/module/com.example.unlisted/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.unlisted.json", "not a module config")

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            val sources = ExecutionRegistryConfigSourceCollector.fromResources("com.example", classLoader)

            assertThat(sources.map { it.tenantName }).containsExactly("indexed", "package")
        }
    }

    @Test
    fun `indexed manifests reject portable path aliases before parsing`() {
        val first = "META-INF/viaduct/execution-manifest/module/catalog/generated/dev.generator/Resolvers.json"
        val second = first.replace("Resolvers.json", "resolvers.json")
        writeResource(ViaductResourceIndexes.EXECUTION_REGISTRY, "$first\n$second\n")

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy { ExecutionRegistryConfigSourceCollector.fromResources(null, classLoader) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(first)
                .hasMessageContaining(second)
        }
    }

    @Test
    fun `indexed legacy module paths are not discovered`() {
        val legacy = "META-INF/viaduct/modules/com.example.legacy.json"
        writeResource(ViaductResourceIndexes.EXECUTION_REGISTRY, "$legacy\n")
        writeResource(legacy, registryJson("legacy"))

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThat(ExecutionRegistryConfigSourceCollector.fromResources(null, classLoader)).isEmpty()
        }
    }

    @Test
    fun `fromResources requires a generated index`() {
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            val exception = assertThrows<IllegalArgumentException> {
                ExecutionRegistryConfigSourceCollector.fromResources(null, classLoader)
            }

            assertThat(exception).hasMessageContaining("includeViaductApplication in settings.gradle.kts")
                .hasMessageContaining("supply module config sources explicitly")
                .hasMessageContaining("plugins compatible with this runtime")
                .hasMessageContaining("Replace legacy META-INF/viaduct/modules manifests")
        }
    }

    @Test
    fun `fromResources returns matching module config sources`() {
        val tenantNames = ExecutionRegistryConfigSourceCollector.fromResources("com.example")
            .map { it.tenantName }
            .toSet()

        assertEquals(
            setOf(
                "bootstrapped",
                "test",
                "unknown",
            ),
            tenantNames,
        )
    }

    @Test
    fun `fromResources without prefix returns module config sources`() {
        val tenantNames = ExecutionRegistryConfigSourceCollector.fromResources().map { it.tenantName }

        assertTrue("test" in tenantNames)
    }

    @Test
    fun `fromResources returns empty list for unmatched prefix`() {
        assertEquals(emptyList<String>(), ExecutionRegistryConfigSourceCollector.fromResources("com.nomatch"))
    }

    @Test
    fun `fromResourcePaths loads exact resources in path order`() {
        val alphaPath = "META-INF/viaduct/execution-manifest/module/com.example.alpha/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.alpha.json"
        val betaPath = "META-INF/viaduct/execution-manifest/module/com.example.beta/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.beta.json"
        writeResource(alphaPath, registryJson("alpha"))
        writeResource(betaPath, registryJson("beta"))

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            val sources = ExecutionRegistryConfigSourceCollector.fromResourcePaths(
                listOf(betaPath, alphaPath),
                classLoader,
            )

            assertThat(sources.map { it.tenantName }).containsExactly("alpha", "beta")
        }
    }

    @Test
    fun `fromResourcePaths requires every resource`() {
        val missingPath = "META-INF/viaduct/execution-manifest/module/com.example.missing/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.missing.json"

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy {
                ExecutionRegistryConfigSourceCollector.fromResourcePaths(listOf(missingPath), classLoader)
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(missingPath)
                .hasMessageContaining("found 0")
        }
    }

    @Test
    fun `fromResourcePaths rejects duplicate classpath resources`() {
        val path = "META-INF/viaduct/execution-manifest/module/com.example.duplicate/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.duplicate.json"
        val firstRoot = tempDir.resolve("first")
        val secondRoot = tempDir.resolve("second")
        writeResource(firstRoot, path, registryJson("first"))
        writeResource(secondRoot, path, registryJson("second"))

        URLClassLoader(arrayOf(firstRoot.toUri().toURL(), secondRoot.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy {
                ExecutionRegistryConfigSourceCollector.fromResourcePaths(listOf(path), classLoader)
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(path)
                .hasMessageContaining("found 2")
        }
    }

    @Test
    fun `fromResourcePaths rejects configs with the same tenant and API`() {
        val alphaPath = "META-INF/viaduct/execution-manifest/module/com.example.alpha/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.alpha.json"
        val betaPath = "META-INF/viaduct/execution-manifest/module/com.example.beta/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.beta.json"
        writeResource(alphaPath, registryJson("duplicate"))
        writeResource(betaPath, registryJson("duplicate"))

        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy {
                ExecutionRegistryConfigSourceCollector.fromResourcePaths(listOf(alphaPath, betaPath), classLoader)
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("duplicate")
        }
    }

    @Test
    fun `fromResourcePaths rejects blank and repeated paths`() {
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
            assertThatThrownBy {
                ExecutionRegistryConfigSourceCollector.fromResourcePaths(listOf(""), classLoader)
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must not be blank")

            assertThatThrownBy {
                ExecutionRegistryConfigSourceCollector.fromResourcePaths(listOf("module.json", "module.json"), classLoader)
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must be unique")
        }
    }

    private fun writeResource(
        path: String,
        contents: String,
    ) = writeResource(tempDir, path, contents)

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

    private fun registryJson(tenantName: String): String =
        """
        {
          "version": "1",
          "executorFactory": "com.example.ExecutorFactory",
          "tenantName": "$tenantName",
          "apiName": "kotlin",
          "nodes": [],
          "fields": []
        }
        """.trimIndent()
}
