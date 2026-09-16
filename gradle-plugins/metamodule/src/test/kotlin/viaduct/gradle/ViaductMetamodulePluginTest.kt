package viaduct.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class ViaductMetamodulePluginTest {
    @Test
    fun `configures neutral module contract`() {
        val project = ProjectBuilder.builder().build()
        project.registerViaductTopology(modules = mapOf(":" to "resolvers"))

        project.pluginManager.apply(TestTenantApiPlugin::class.java)

        project.pluginManager.hasPlugin(ViaductMetamodulePlugin.ID) shouldBe true
        val extension = project.extensions.getByType(ViaductMetamoduleExtension::class.java)
        extension.applicationProjectPath shouldBe ":"
        extension.layout.modulePackagePrefix shouldBe "com.example.test"
        extension.layout.modulePackageSuffix shouldBe "resolvers"
        extension.layout.fullTenantPackage shouldBe "com.example.test.resolvers"
        extension.centralSchemaConfiguration.name shouldBe ViaductPluginCommon.Configs.CENTRAL_SCHEMA_INCOMING

        project.configurations.getByName(ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING).run {
            isCanBeConsumed shouldBe true
            isCanBeResolved shouldBe false
            attributes.getAttribute(ViaductPluginCommon.VIADUCT_KIND) shouldBe
                ViaductPluginCommon.Kind.SCHEMA_PARTITION
        }
        project.tasks.findByName("prepareViaductSchemaPartition")?.name shouldBe
            "prepareViaductSchemaPartition"
    }

    @Test
    fun `rejects a project outside the topology`() {
        val project = ProjectBuilder.builder().build()
        project.registerViaductTopology()

        val error = shouldThrow<GradleException> {
            project.pluginManager.apply(ViaductMetamodulePlugin::class.java)
        }

        generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message }
            .any { it.contains("not as a module project") } shouldBe true
    }

    private fun Project.registerViaductTopology(modules: Map<String, String> = emptyMap()) {
        gradle.sharedServices.registerIfAbsent(
            ViaductTopologyService.NAME,
            ViaductTopologyService::class.java,
        ) {
            parameters.topologyJson.set(
                ViaductTopologyJson.encode(
                    ViaductApplicationMap(
                        applicationTopologies = mapOf(
                            ":" to ViaductApplicationTopology(
                                applicationProjectPath = ":",
                                modulePackagePrefix = "com.example.test",
                                modulePackageSuffixes = modules,
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private class TestTenantApiPlugin : Plugin<Project> {
        override fun apply(project: Project) {
            project.pluginManager.apply(ViaductMetamodulePlugin.ID)
        }
    }
}
