package viaduct.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * TestKit functional tests for the module plugin.
 * These tests cover diagnostics, configuration-time enforcement, and model wiring only.
 * Real execution (codegen, serve) is validated through demoapps.
 */
class ViaductModulePluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    /**
     * Returns the full test runtime classpath as the plugin classpath, which includes the module
     * plugin, application plugin, KSP, and all transitive dependencies.
     */
    private fun combinedPluginClasspath(): List<File> {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }
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
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "test"""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
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
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
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
    fun `direct module-to-module dependency fails with clear message`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("moduleA")
            include("moduleB")
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
        val moduleADir = File(projectDir, "moduleA").also { it.mkdirs() }
        File(moduleADir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            dependencies {
                implementation(project(":moduleB"))
            }
            """.trimIndent()
        )
        val schemaADir = File(moduleADir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaADir, "schema.graphqls").writeText("type Query { fieldA: String }")

        val moduleBDir = File(projectDir, "moduleB").also { it.mkdirs() }
        File(moduleBDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            """.trimIndent()
        )
        val schemaBDir = File(moduleBDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaBDir, "schema.graphqls").writeText("type Query { fieldB: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":moduleA:dependencies")
            .buildAndFail()

        assertTrue(result.output.contains(":moduleA"), "Expected output to mention ':moduleA'")
        assertTrue(result.output.contains(":moduleB"), "Expected output to mention ':moduleB'")
        assertTrue(result.output.contains("central schema"), "Expected output to mention 'central schema'")
    }

    @Test
    fun `dependency on non-module project is allowed`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("moduleA")
            include("libproject")
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
        val moduleADir = File(projectDir, "moduleA").also { it.mkdirs() }
        File(moduleADir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            dependencies {
                implementation(project(":libproject"))
            }
            """.trimIndent()
        )
        val schemaADir = File(moduleADir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaADir, "schema.graphqls").writeText("type Query { fieldA: String }")

        val libDir = File(projectDir, "libproject").also { it.mkdirs() }
        File(libDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":moduleA:dependencies")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    @Test
    fun `dependency on non-module project with same leaf name as another module is not blocked`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("module")
            include("lib:module")
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
        val moduleDir = File(projectDir, "module").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            dependencies {
                implementation(project(":lib:module"))
            }
            """.trimIndent()
        )
        val schemaDir = File(moduleDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val libModuleDir = File(projectDir, "lib/module").also { it.mkdirs() }
        File(libModuleDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":module:dependencies")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    @Test
    fun `assembleViaductCentralSchema succeeds when optional schema directories are absent`() {
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
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductModule {
                modulePackageSuffix.set("mymodule")
            }
            """.trimIndent()
        )
        val schemaDir = File(moduleDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    @Test
    fun `schema directory with no graphqls files fails with clear message`() {
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
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )
        File(moduleDir, "src/main/viaduct/schema").mkdirs()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":mymodule:prepareViaductSchemaPartition")
            .buildAndFail()

        assertTrue(result.output.contains("src/main/viaduct/schema"), "Expected output to mention 'src/main/viaduct/schema'")
        assertTrue(result.output.contains("graphqls"), "Expected output to mention 'graphqls'")
    }

    /**
     * Negative functional test: a module SDL that references an undeclared scope must fail
     * at central assembly (AssembleCentralSchemaTask), NOT at module partition assembly
     * (AssembleSchemaPartitionTask).
     * This guards that scope validation does NOT leak into the module plugin.
     */
    @Test
    fun `undeclared scope in module SDL fails at central assembly not at partition assembly`() {
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
                declaredSchemaScopes(setOf("public"))
                declaredScopedSchema("api", setOf("public"))
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductModule {
                modulePackageSuffix.set("mymodule")
            }
            """.trimIndent()
        )
        val schemaDir = File(moduleDir, "src/main/viaduct/schema").also { it.mkdirs() }
        // Module SDL references a scope not declared in the application ("undeclared").
        // Only "public" is in the scope universe.
        File(schemaDir, "schema.graphqls").writeText(
            """
            type Query { field: String }
            type BadScopedType @scope(to: ["undeclared"]) { id: ID }
            """.trimIndent()
        )

        // Step 1: partition assembly must SUCCEED — module plugin is scope-unaware
        val partitionResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":mymodule:prepareViaductSchemaPartition")
            .build()
        assertTrue(
            partitionResult.output.contains("BUILD SUCCESSFUL"),
            "Partition assembly must succeed — module plugin does not validate scope names"
        )

        // Step 2: central assembly must FAIL — scope validation happens here
        val centralResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema")
            .buildAndFail()
        assertTrue(
            centralResult.output.contains("undeclared") || centralResult.output.contains("Scope validation"),
            "Central assembly must fail with a scope validation error mentioning 'undeclared': ${centralResult.output}"
        )
    }
}
