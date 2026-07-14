package viaduct.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ViaductJavaModulePluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private fun combinedPluginClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }

    @Test
    fun `java module plugin without settings topology fails with clear message`() {
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
                id("com.airbnb.viaduct.module-java-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("Viaduct settings topology"), "Expected output to mention Viaduct settings topology")
        assertTrue(result.output.contains("settings-gradle-plugin"), "Expected output to mention 'settings-gradle-plugin'")
    }

    @Test
    fun `java module plugin rejects application-only topology placement`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":")
                modulePackagePrefix("com.example.test")
            }
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-java-gradle-plugin")
            }

            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("not as a module project"), "Expected output to reject application-only placement")
    }

    @Test
    fun `topology package values configure Java module resolver base task when project DSL disagrees`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.topology")

                includeModule {
                    project(":app:javaresolvers")
                    modulePackageSuffix("javaresolvers")
                }
            }
            """.trimIndent()
        )
        File(projectDir, "build.gradle").writeText("")

        val appDir = File(projectDir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.airbnb.viaduct.application-gradle-plugin'
            }
            viaductApplication {
                modulePackagePrefix.set('com.example.project')
            }
            """.trimIndent()
        )

        val moduleDir = File(projectDir, "app/javaresolvers").also { it.mkdirs() }
        File(moduleDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.airbnb.viaduct.module-java-gradle-plugin'
            }
            viaductModule {
                modulePackageSuffix.set('project')
            }

            tasks.register('printViaductJavaTopologyPackageInputs') {
                doLast {
                    def resolverTask = tasks.named('generateViaductResolverBases').get()

                    println "JAVA_TENANT_PREFIX=${'$'}{resolverTask.tenantPackagePrefix.get()}"
                    println "JAVA_TENANT_PACKAGE=${'$'}{resolverTask.tenantPackage.get()}"
                    println "JAVA_OUTPUT=${'$'}{resolverTask.outputDirectory.get().asFile.absolutePath.replace(File.separatorChar, '/' as char)}"
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":app:javaresolvers:printViaductJavaTopologyPackageInputs")
            .build()

        assertTrue(result.output.contains("JAVA_TENANT_PREFIX=com.example.topology"))
        assertTrue(result.output.contains("JAVA_TENANT_PACKAGE=javaresolvers"))
        assertTrue(
            result.output.contains("generated-sources/viaduct/javaResolverBases"),
            "Expected Java resolver-base output root to be configured",
        )
    }
}
