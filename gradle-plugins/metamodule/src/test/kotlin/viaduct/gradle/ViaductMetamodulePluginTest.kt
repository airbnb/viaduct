package viaduct.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class ViaductMetamodulePluginTest {
    @Test
    fun `public conventions supply an empty manifest variant and isolated schema inputs`() {
        val project = ProjectBuilder.builder().build()
        project.registerViaductTopology(modules = mapOf(":" to "resolvers"))

        project.pluginManager.apply(ViaductModuleConventionsPlugin::class.java)

        val conventions = project.extensions.getByType(ViaductModuleConventions::class.java)
        conventions.layout.fullTenantPackage shouldBe "com.example.test.resolvers"
        conventions.application.get().isCanBeConsumed shouldBe false
        conventions.application.get().isCanBeResolved shouldBe false
        conventions.centralSchemaIn.get().extendsFrom shouldBe setOf(conventions.application.get())
        conventions.centralSchemaIn.get().isTransitive shouldBe false
        conventions.moduleManifests.get().run {
            isCanBeConsumed shouldBe true
            isCanBeResolved shouldBe false
            outgoing.artifacts.size shouldBe 0
            attributes.getAttribute(ViaductArtifacts.KIND) shouldBe ViaductArtifacts.MODULE_MANIFEST
            attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name shouldBe ViaductArtifacts.USAGE
        }
    }

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
