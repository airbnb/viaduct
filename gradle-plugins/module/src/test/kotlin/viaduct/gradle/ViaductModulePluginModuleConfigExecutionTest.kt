package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ViaductModulePluginModuleConfigExecutionTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `module config is generated when schema file collection is empty`() {
        writeStatefulExecutionProject()
        File(projectDir, "build.gradle.kts").appendText(
            """

            tasks.named<viaduct.gradle.task.AssembleTenantModuleConfigFileTask>(
                "assembleViaductModuleConfigFile"
            ) {
                centralSchemaFiles.setFrom(emptyList<Any>())
            }
            """.trimIndent()
        )

        runModuleConfigBuild(skipKsp = false)

        val moduleConfigFile = File(
            projectDir,
            "build/generated-resources/viaduct-registry/" +
                "META-INF/viaduct/modules/com.example.test.resolvers.json",
        )
        assertTrue(moduleConfigFile.exists(), "Expected module config JSON to be generated")
        moduleConfigFile.readText() shouldContain "GreetingResolver"
    }

    @Test
    fun `module config rejects tenant-local selections owned by another module`() {
        writeCrossTenantLocalExecutionProject()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":alpha:assembleViaductModuleConfigFile")
            .buildAndFail()

        result.output shouldContain "RSS validation failed at assembly"
        result.output shouldContain "Query.privateGreeting"
        result.output shouldContain "owned by beta"
    }

    @Test
    fun `module config is updated as resolver descriptor files are added and removed`() {
        writeStatefulExecutionProject()

        val resolverDir = File(projectDir, "src/main/kotlin/com/example/test/resolvers")
        val greetingResolverFile = File(resolverDir, "GreetingResolver.kt")
        val authorResolverFile = File(resolverDir, "AuthorResolver.kt")

        val sourceDescriptorDir = File(
            projectDir,
            "build/generated/ksp/main/resources/viaduct-registry/com/example/test/resolvers",
        )
        val greetingSourceDescriptor = File(sourceDescriptorDir, "GreetingResolver.json")
        val authorSourceDescriptor = File(sourceDescriptorDir, "AuthorResolver.json")

        val descriptorDir = File(
            projectDir,
            "build/intermediates/viaduct-registry-descriptors/com/example/test/resolvers",
        )
        val greetingDescriptor = File(descriptorDir, "GreetingResolver.json")
        val authorDescriptor = File(descriptorDir, "AuthorResolver.json")
        val moduleConfigFile = File(
            projectDir,
            "build/generated-resources/viaduct-registry/" +
                "META-INF/viaduct/modules/com.example.test.resolvers.json",
        )

        runModuleConfigBuild(skipKsp = false)

        assertTrue(greetingDescriptor.exists(), "Expected greeting descriptor to exist after initial build")
        assertFalse(authorDescriptor.exists(), "Did not expect author descriptor before its source file exists")
        assertTrue(moduleConfigFile.exists(), "Expected module config JSON to exist after initial build")
        moduleConfigFile.readText() shouldContain "GreetingResolver"
        moduleConfigFile.readText() shouldContain "\"tenantName\" : \"resolvers\""
        assertFalse(
            moduleConfigFile.readText().contains("AuthorResolver"),
            "Did not expect author resolver in module config before its source file exists",
        )

        authorResolverFile.writeText(authorResolverSource())

        runModuleConfigBuild(skipKsp = false)

        assertTrue(authorDescriptor.exists(), "Expected author descriptor to exist after adding its source file")
        moduleConfigFile.readText() shouldContain "GreetingResolver"
        moduleConfigFile.readText() shouldContain "AuthorResolver"

        // After the real KSP path has produced descriptors in the current on-disk format, drive
        // the stale-output phases by mutating those descriptor inputs directly. This avoids
        // hard-coding descriptor JSON in the test and cuts out two extra compiler/KSP runs.
        assertTrue(authorResolverFile.delete(), "Expected author resolver source file to be deleted")
        assertTrue(authorSourceDescriptor.delete(), "Expected author descriptor file to be deleted")

        runModuleConfigBuild(skipKsp = true)

        assertFalse(authorDescriptor.exists(), "Expected author descriptor to be removed after deleting its descriptor file")
        moduleConfigFile.readText() shouldContain "GreetingResolver"
        assertFalse(
            moduleConfigFile.readText().contains("AuthorResolver"),
            "Did not expect author resolver in module config after deleting its descriptor file",
        )

        assertTrue(greetingResolverFile.delete(), "Expected greeting resolver source file to be deleted")
        assertTrue(greetingSourceDescriptor.delete(), "Expected greeting descriptor file to be deleted")

        runModuleConfigBuild(skipKsp = true)

        assertFalse(greetingDescriptor.exists(), "Expected greeting descriptor to be removed after deleting its descriptor file")
        assertFalse(moduleConfigFile.exists(), "Expected module config JSON to be removed when no resolver descriptors remain")
    }

    private fun runModuleConfigBuild(skipKsp: Boolean) {
        val args = mutableListOf(
            "assembleViaductModuleConfigFile",
        )
        if (skipKsp) {
            args += listOf("-x", "kspKotlin")
        }

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(args)
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    private val fixtureRepoPath: String
        get() = File(
            requireNotNull(System.getProperty("viaduct.testFixtureRepo")) {
                "viaduct.testFixtureRepo is set by the test task; run this suite through Gradle"
            }
        ).invariantSeparatorsPath

    private val fixtureVersion: String
        get() = requireNotNull(System.getProperty("viaduct.testFixtureVersion")) {
            "viaduct.testFixtureVersion is set by the test task; run this suite through Gradle"
        }

    private fun combinedPluginClasspath(): List<File> {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }
    }

    private fun writeStatefulExecutionProject() {
        writeGradleProperties()
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            modules = mapOf(":" to "resolvers"),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }

            repositories {
                mavenCentral()
                flatDir { dirs("$fixtureRepoPath") }
            }

            dependencies {
                implementation("com.airbnb.viaduct:api:$fixtureVersion")
            }
            """.trimIndent()
        )

        val resolverDir = File(projectDir, "src/main/kotlin/com/example/test/resolvers").also { it.mkdirs() }
        File(resolverDir, "GreetingResolver.kt").writeText(greetingResolverSource())

        val schemaDir = File(projectDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText(
            """
            extend type Query {
              greeting: String @resolver
              author: String @resolver
            }
            """.trimIndent()
        )
    }

    private fun writeCrossTenantLocalExecutionProject() {
        writeGradleProperties()
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            modules = linkedMapOf(
                ":alpha" to "alpha",
                ":beta" to "beta",
            ),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }

            repositories {
                mavenCentral()
                flatDir { dirs("$fixtureRepoPath") }
            }
            """.trimIndent()
        )

        listOf("alpha", "beta").forEach { module ->
            val moduleDir = File(projectDir, module).also { it.mkdirs() }
            File(moduleDir, "build.gradle.kts").writeText(
                """
                plugins {
                    kotlin("jvm")
                    id("com.airbnb.viaduct.module-gradle-plugin")
                    id("com.google.devtools.ksp")
                }

                repositories {
                    mavenCentral()
                    flatDir { dirs("$fixtureRepoPath") }
                }

                dependencies {
                    implementation("com.airbnb.viaduct:api:$fixtureVersion")
                }
                """.trimIndent()
            )
        }

        val alphaResolverDir = File(
            projectDir,
            "alpha/src/main/kotlin/com/example/test/alpha",
        ).also { it.mkdirs() }
        File(alphaResolverDir, "GreetingResolver.kt").writeText(
            """
            package com.example.test.alpha

            import com.example.test.alpha.resolverbases.QueryResolvers
            import viaduct.api.resolver.Resolver

            @Resolver(queryValueFragment = "fragment _ on Query { privateGreeting }")
            class GreetingResolver : QueryResolvers.Greeting() {
                override suspend fun resolve(ctx: Context) = "hello"
            }
            """.trimIndent()
        )

        val alphaSchemaDir = File(projectDir, "alpha/src/main/viaduct/schema").also { it.mkdirs() }
        File(alphaSchemaDir, "schema.graphqls").writeText(
            """
            extend type Query {
              greeting: String @resolver
            }
            """.trimIndent()
        )
        val betaSchemaDir = File(projectDir, "beta/src/main/viaduct/schema").also { it.mkdirs() }
        File(betaSchemaDir, "schema.graphqls").writeText(
            """
            extend type Query {
              privateGreeting: String @tenantLocal @resolver
            }
            """.trimIndent()
        )
    }

    private fun writeGradleProperties() {
        File(projectDir, "gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g
            kotlin.daemon.jvmargs=-Xmx2g
            org.gradle.workers.max=1
            org.gradle.configuration-cache=true
            """.trimIndent()
        )
    }

    private fun greetingResolverSource(): String =
        """
        package com.example.test.resolvers

        import com.example.test.resolvers.resolverbases.QueryResolvers
        import viaduct.api.resolver.Resolver

        @Resolver
        class GreetingResolver : QueryResolvers.Greeting() {
            override suspend fun resolve(ctx: Context) = "hello"
        }
        """.trimIndent()

    private fun authorResolverSource(): String =
        """
        package com.example.test.resolvers

        import com.example.test.resolvers.resolverbases.QueryResolvers
        import viaduct.api.resolver.Resolver

        @Resolver
        class AuthorResolver : QueryResolvers.Author() {
            override suspend fun resolve(ctx: Context) = "viaduct"
        }
        """.trimIndent()
}
