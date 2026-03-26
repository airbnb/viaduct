package viaduct.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class ViaductModulePluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    /**
     * Returns the combined plugin classpath: the module plugin's auto-detected classpath plus
     * the application plugin's location (needed for multi-project functional tests).
     */
    private fun combinedPluginClasspath(): List<File> {
        val moduleClasspath = GradleRunner.create().withPluginClasspath().pluginClasspath
        val appPluginJar = File(ViaductApplicationPlugin::class.java.protectionDomain.codeSource.location.toURI())
        return moduleClasspath + listOf(appPluginJar)
    }

    @Test
    fun `module plugin without application plugin fails with clear message`() {
        // Multi-project build: root applies no plugins; subproject applies only the module plugin.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("mymodule")
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText("")
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("application-gradle-plugin"), "Expected output to mention 'application-gradle-plugin'")
    }

    @Test
    fun `same-project topology is supported`() {
        // Single-project build: both plugins applied to the root project (cli-starter topology).
        // The module plugin's afterEvaluate fires after viaductApplication { ... } is configured,
        // so modulePackagePrefix is visible by the time validation runs.
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "test"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )
        val schemaDir = File(projectDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    @Test
    fun `missing modulePackagePrefix fails with clear message`() {
        // Multi-project build: root applies application plugin WITHOUT setting modulePackagePrefix.
        // Validation fires during configuration (afterEvaluate), so `help` is sufficient to trigger it.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("mymodule")
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("modulePackagePrefix"), "Expected output to mention 'modulePackagePrefix'")
    }

    @Test
    fun `blank modulePackagePrefix fails with clear message`() {
        // Same shape as missing-prefix test, but modulePackagePrefix is explicitly set to blank.
        // The contract covers both absent and blank values.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("mymodule")
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("   ")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("modulePackagePrefix"), "Expected output to mention 'modulePackagePrefix'")
    }

    @Test
    fun `module without schema directory fails with clear message`() {
        // Multi-project build: root applies application plugin with modulePackagePrefix set;
        // subproject applies module plugin but has no src/main/viaduct/schema directory.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("mymodule")
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":mymodule:prepareViaductSchemaPartition")
            .buildAndFail()

        assertTrue(result.output.contains("src/main/viaduct/schema"), "Expected output to mention 'src/main/viaduct/schema'")
        assertTrue(result.output.contains("graphqls"), "Expected output to mention 'graphqls'")
    }

    @Test
    fun `schema directory with no graphqls files fails with clear message`() {
        // Same multi-project setup, but the schema directory exists and is empty.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("mymodule")
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )
        // Create the directory but add no .graphqls files
        File(moduleDir, "src/main/viaduct/schema").mkdirs()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":mymodule:prepareViaductSchemaPartition")
            .buildAndFail()

        assertTrue(result.output.contains("src/main/viaduct/schema"), "Expected output to mention 'src/main/viaduct/schema'")
        assertTrue(result.output.contains("graphqls"), "Expected output to mention 'graphqls'")
    }
}
