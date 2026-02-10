package viaduct.gradle.scaffold

import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.codegen.st.STContents

/**
 * Tests for the Viaduct scaffolding functionality.
 */
class ScaffoldTaskTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var task: ScaffoldTask

    companion object {
        private const val TEST_VIADUCT_VERSION = "0.21.0-SNAPSHOT"
    }

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder()
            .withName("test")
            .withProjectDir(tempDir.toFile())
            .build()

        task = project.tasks.create("testScaffold", ScaffoldTask::class.java)
    }

    @Test
    fun `task should be created successfully`() {
        assertNotNull(task)
        assertEquals("viaduct", task.group)
        assertEquals("Scaffold a new Viaduct project", task.description)
    }

    @Test
    fun `task should have default gradle version`() {
        assertEquals("8.12", task.gradleVersion.get())
    }

    // Template rendering tests using STContents

    @Test
    fun `Main_kt template should render valid Kotlin`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.mainKt, model).toString()

        assertTrue(content.contains("package com.example.myapp"))
        assertTrue(content.contains("fun main(args: Array<String>)"))
        assertTrue(content.contains("fun Application.module()"))
        assertTrue(content.contains("configureContentNegotiation()"))
        assertTrue(content.contains("configureRouting()"))
        // No wildcard imports
        assertFalse(content.contains("import .*\\*".toRegex()))
    }

    @Test
    fun `Routing_kt template should render valid Kotlin`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.routingKt, model).toString()

        assertTrue(content.contains("package com.example.myapp.ktorplugins"))
        assertTrue(content.contains("fun Application.configureRouting()"))
        assertTrue(content.contains("get(\"/health\")"))
        assertTrue(content.contains("post(\"/graphql\")"))
        assertTrue(content.contains("get(\"/graphiql\")"))
        assertTrue(content.contains("BasicViaductFactory.create"))
        assertTrue(content.contains("tenantPackagePrefix = \"com.example.myapp\""))
        assertFalse(content.contains("import .*\\*".toRegex()))
    }

    @Test
    fun `ContentNegotiation_kt template should render valid Kotlin`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.contentNegotiationKt, model).toString()

        assertTrue(content.contains("package com.example.myapp.ktorplugins"))
        assertTrue(content.contains("fun Application.configureContentNegotiation()"))
        assertTrue(content.contains("install(ContentNegotiation)"))
        assertTrue(content.contains("jackson"))
        assertFalse(content.contains("import .*\\*".toRegex()))
    }

    @Test
    fun `application_conf template should render valid HOCON`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.applicationConf, model).toString()

        assertTrue(content.contains("ktor {"))
        assertTrue(content.contains("port = 8080"))
        assertTrue(content.contains("host = \"0.0.0.0\""))
        assertTrue(content.contains("modules = [ com.example.myapp.MainKt.module ]"))
    }

    @Test
    fun `schema_graphqls template should render valid GraphQL schema`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.schemaGraphqls, model).toString()

        assertTrue(content.contains("extend type Query {"))
        assertTrue(content.contains("greeting: String! @resolver"))
    }

    @Test
    fun `GreetingResolver_kt template should render valid Kotlin`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.greetingResolverKt, model).toString()

        assertTrue(content.contains("package com.example.myapp.resolvers"))
        assertTrue(content.contains("import com.example.myapp.resolvers.resolverbases.QueryResolvers"))
        assertTrue(content.contains("@Resolver"))
        assertTrue(content.contains("class GreetingResolver : QueryResolvers.Greeting()"))
        assertTrue(content.contains("override suspend fun resolve"))
        assertTrue(content.contains("Hello from Viaduct!"))
        assertFalse(content.contains("import .*\\*".toRegex()))
    }

    @Test
    fun `build_gradle_kts template should render valid Gradle script`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.buildGradleKts, model).toString()

        assertTrue(content.contains("plugins {"))
        assertTrue(content.contains("kotlin(\"jvm\")"))
        assertTrue(content.contains("com.airbnb.viaduct.application-gradle-plugin"))
        assertTrue(content.contains("com.airbnb.viaduct.module-gradle-plugin"))
        assertTrue(content.contains("mainClass.set(\"com.example.myapp.MainKt\")"))
        assertTrue(content.contains("viaductApplication {"))
        assertTrue(content.contains("modulePackagePrefix.set(\"com.example.myapp\")"))
        assertTrue(content.contains("viaductModule {"))
        assertTrue(content.contains("modulePackageSuffix.set(\"resolvers\")"))
        assertTrue(content.contains("io.ktor:ktor-server-core"))
        assertTrue(content.contains("com.airbnb.viaduct:api"))
        assertTrue(content.contains("com.airbnb.viaduct:runtime"))
    }

    @Test
    fun `settings_gradle_kts template should render valid Gradle settings`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        val content = STContents(Templates.settingsGradleKts, model).toString()

        assertTrue(content.contains("pluginManagement {"))
        assertTrue(content.contains("mavenCentral()"))
        assertTrue(content.contains("rootProject.name = \"myapp\""))
    }

    @Test
    fun `ProjectModel should derive project name from package prefix`() {
        val model = ProjectModel(packagePrefix = "com.example.myapp", viaductVersion = TEST_VIADUCT_VERSION)
        assertEquals("myapp", model.projectName)
        assertEquals("com/example/myapp", model.packagePath)
    }

    @Test
    fun `ProjectModel should handle single segment package`() {
        val model = ProjectModel(packagePrefix = "myapp", viaductVersion = TEST_VIADUCT_VERSION)
        assertEquals("myapp", model.projectName)
        assertEquals("myapp", model.packagePath)
    }

    @Test
    fun `templates should correctly substitute different package prefixes`() {
        val packages = listOf(
            "com.example.myapp",
            "io.viaduct.demo",
            "org.company.project.module"
        )

        for (pkg in packages) {
            val model = ProjectModel(packagePrefix = pkg, viaductVersion = TEST_VIADUCT_VERSION)
            val content = STContents(Templates.mainKt, model).toString()
            assertTrue(content.contains("package $pkg"), "Package $pkg not found in Main.kt")
        }
    }

    // File generation tests

    @Test
    fun `task should create correct directory structure`() {
        val outputDir = File(tempDir.toFile(), "test-output")
        task.packagePrefix.set("com.example.myapp")
        task.outputDir.set(outputDir)

        task.scaffold()

        // Check directories exist
        assertTrue(File(outputDir, "src/main/kotlin/com/example/myapp").exists())
        assertTrue(File(outputDir, "src/main/kotlin/com/example/myapp/ktorplugins").exists())
        assertTrue(File(outputDir, "src/main/kotlin/com/example/myapp/resolvers").exists())
        assertTrue(File(outputDir, "src/main/resources").exists())
        assertTrue(File(outputDir, "src/main/viaduct/schema").exists())
    }

    @Test
    fun `task should generate all required files`() {
        val outputDir = File(tempDir.toFile(), "test-output")
        task.packagePrefix.set("com.example.myapp")
        task.outputDir.set(outputDir)

        task.scaffold()

        // Check files exist
        val kotlinDir = File(outputDir, "src/main/kotlin/com/example/myapp")
        assertTrue(File(kotlinDir, "Main.kt").exists())
        assertTrue(File(kotlinDir, "ktorplugins/Routing.kt").exists())
        assertTrue(File(kotlinDir, "ktorplugins/ContentNegotiation.kt").exists())
        assertTrue(File(kotlinDir, "resolvers/GreetingResolver.kt").exists())
        assertTrue(File(outputDir, "src/main/viaduct/schema/schema.graphqls").exists())
        assertTrue(File(outputDir, "src/main/resources/application.conf").exists())
        assertTrue(File(outputDir, "build.gradle.kts").exists())
        assertTrue(File(outputDir, "settings.gradle.kts").exists())
        assertTrue(File(outputDir, "gradle.properties").exists())
        // Note: AGENTS.md, .gitignore, and .viaduct/agents are created by the
        // install script downloaded from viaduct-dev/skills repo
    }

    @Test
    fun `generated Main_kt should have correct package`() {
        val outputDir = File(tempDir.toFile(), "test-output")
        task.packagePrefix.set("io.mycompany.service")
        task.outputDir.set(outputDir)

        task.scaffold()

        val mainKt = File(outputDir, "src/main/kotlin/io/mycompany/service/Main.kt")
        val content = mainKt.readText()
        assertTrue(content.startsWith("package io.mycompany.service"))
    }

    @Test
    fun `generated Routing_kt should use correct package prefix`() {
        val outputDir = File(tempDir.toFile(), "test-output")
        task.packagePrefix.set("io.mycompany.service")
        task.outputDir.set(outputDir)

        task.scaffold()

        val routingKt = File(outputDir, "src/main/kotlin/io/mycompany/service/ktorplugins/Routing.kt")
        val content = routingKt.readText()
        assertTrue(content.contains("tenantPackagePrefix = \"io.mycompany.service\""))
    }

    @Test
    fun `generated application_conf should reference correct module`() {
        val outputDir = File(tempDir.toFile(), "test-output")
        task.packagePrefix.set("io.mycompany.service")
        task.outputDir.set(outputDir)

        task.scaffold()

        val conf = File(outputDir, "src/main/resources/application.conf")
        val content = conf.readText()
        assertTrue(content.contains("modules = [ io.mycompany.service.MainKt.module ]"))
    }

    @Test
    fun `generated GreetingResolver_kt should use correct package prefix`() {
        val outputDir = File(tempDir.toFile(), "test-output")
        task.packagePrefix.set("io.mycompany.service")
        task.outputDir.set(outputDir)

        task.scaffold()

        val resolverKt = File(outputDir, "src/main/kotlin/io/mycompany/service/resolvers/GreetingResolver.kt")
        val content = resolverKt.readText()
        assertTrue(content.contains("package io.mycompany.service.resolvers"))
        assertTrue(content.contains("import io.mycompany.service.resolvers.resolverbases.QueryResolvers"))
    }

    // Note: AGENTS.md content is now generated by the install script from
    // viaduct-dev/skills repo, not by the scaffold task directly.
}
