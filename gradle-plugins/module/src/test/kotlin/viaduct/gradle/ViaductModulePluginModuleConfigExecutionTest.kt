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

    private fun combinedPluginClasspath(): List<File> {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }
    }

    private fun writeStatefulExecutionProject() {
        val publicationsDir = findOssRoot().resolve("publications")
        File(projectDir, "gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g
            kotlin.daemon.jvmargs=-Xmx2g
            org.gradle.workers.max=1
            org.gradle.configuration-cache=true
            """.trimIndent()
        )
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            modules = mapOf(":" to "resolvers"),
            includedBuilds = listOf(publicationsDir),
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
            }

            dependencies {
                implementation("com.airbnb.viaduct:api")
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

    private fun findOssRoot(): File {
        return generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { candidate ->
                File(candidate, "publications/settings.gradle.kts").exists() &&
                    File(candidate, "gradle-plugins/settings.gradle.kts").exists()
            }
            ?: error("Could not locate Viaduct OSS root from ${System.getProperty("user.dir")}")
    }
}
