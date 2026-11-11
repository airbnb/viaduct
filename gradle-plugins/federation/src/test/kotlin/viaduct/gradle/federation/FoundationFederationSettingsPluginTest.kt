package viaduct.gradle.federation

import java.nio.file.Path
import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.gradle.ViaductApplicationConventions
import viaduct.gradle.ViaductApplicationMap
import viaduct.gradle.ViaductApplicationTopology
import viaduct.gradle.ViaductTopologyJson
import viaduct.gradle.ViaductTopologyService

class FoundationFederationSettingsPluginTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `application plugin contributes Foundation SDL and validation dependency`() {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply("java-library")
        project.gradle.sharedServices.registerIfAbsent(ViaductTopologyService.NAME, ViaductTopologyService::class.java) {
            parameters.topologyJson.set(
                ViaductTopologyJson.encode(
                    ViaductApplicationMap(
                        mapOf(
                            ":" to ViaductApplicationTopology(":", "com.example.foundation", emptyMap()),
                        )
                    )
                )
            )
        }

        project.pluginManager.apply(FoundationFederationApplicationPlugin::class.java)

        val conventions = project.extensions.getByType(ViaductApplicationConventions::class.java)
        val dependency = conventions.schemaValidationPlugins.get().dependencies.single()
        assertEquals("com.airbnb.viaduct.gradle", dependency.group)
        assertEquals("federation-validation", dependency.name)
        assertTrue(!dependency.version.isNullOrBlank())
        val prepare = project.tasks.getByName("prepareViaductPluginSchema") as Sync
        assertTrue(prepare.source.files.single().readText().contains("directive @key(fields: FieldSelectionSet!)"))
    }

    @Test
    fun `settings plugin registers its application capability`() {
        root.resolve("settings.gradle.kts").toFile().writeText(
            """
            import viaduct.gradle.viaductPluginRegistry
            plugins { id("com.airbnb.viaduct.foundation-federation") }
            check(viaductPluginRegistry().registrations.getValue("com.airbnb.viaduct.foundation-federation")
                .applicationPlugin.name == "viaduct.gradle.federation.FoundationFederationApplicationPlugin")
            """.trimIndent(),
        )
        root.resolve("build.gradle.kts").toFile().writeText("")
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments("help", "--stacktrace").build()
    }
}
