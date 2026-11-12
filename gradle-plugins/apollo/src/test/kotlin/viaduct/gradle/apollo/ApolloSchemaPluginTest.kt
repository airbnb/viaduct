package viaduct.gradle.apollo

import java.io.File
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductApplicationMap
import viaduct.gradle.ViaductApplicationTopology
import viaduct.gradle.ViaductTopologyJson
import viaduct.gradle.ViaductTopologyService
import viaduct.gradle.task.AssembleFinalSchemaTask

@OptIn(InternalApi::class)
class ApolloSchemaPluginTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `application plugin wires generation after central assembly and before final validation`() {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        project.gradle.sharedServices.registerIfAbsent(ViaductTopologyService.NAME, ViaductTopologyService::class.java) {
            parameters.topologyJson.set(
                ViaductTopologyJson.encode(
                    ViaductApplicationMap(
                        applicationTopologies = mapOf(
                            project.path to ViaductApplicationTopology(project.path, "example.apollo", emptyMap())
                        )
                    )
                )
            )
        }
        project.pluginManager.apply(ApolloSchemaApplicationPlugin::class.java)
        val generate = project.tasks.getByName("generateApolloSchema") as GenerateApolloSchema
        val central = project.tasks.getByName("assembleViaductCentralSchema")
        val prepareFinal = project.tasks.getByName("prepareViaductPluginFinalSchema")
        val final = project.tasks.getByName("assembleViaductFinalSchema") as AssembleFinalSchemaTask
        assertEquals(setOf(central), generate.taskDependencies.getDependencies(generate))
        assertTrue(generate in prepareFinal.taskDependencies.getDependencies(prepareFinal))
        assertTrue(prepareFinal in final.taskDependencies.getDependencies(final))
        assertFalse(generate in central.taskDependencies.getDependencies(central))
        assertEquals(project.layout.buildDirectory.dir("viaduct/centralSchema").get().asFile, generate.centralSchemaDirectory.get().asFile)
        assertEquals(project.layout.buildDirectory.file("generated/apollo/schema.graphqls").get().asFile, generate.outputFile.get().asFile)
    }

    @Test
    fun `settings plugin selects schema generation without installing a tenant API`() {
        tempDir.resolve("settings.gradle.kts").toFile().writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
                id("com.airbnb.viaduct.apollo-schema") apply false
            }
            rootProject.name = "apollo-model"
            includeViaductApplication {
                usePlugin("com.airbnb.viaduct.apollo-schema")
                project(":app")
                modulePackagePrefix("example.apollo")
            }
            """.trimIndent()
        )
        tempDir.resolve("app").toFile().mkdirs()
        val result = GradleRunner.create().withProjectDir(tempDir.toFile())
            .withPluginClasspath(System.getProperty("java.class.path").split(File.pathSeparator).map { File(it) })
            .withArguments(":app:tasks", "--all", "--stacktrace").build()
        assertTrue(result.output.contains("generateApolloSchema"), result.output)
        assertFalse(result.output.contains("generateViaductSchemaObjects"), result.output)
    }
}
