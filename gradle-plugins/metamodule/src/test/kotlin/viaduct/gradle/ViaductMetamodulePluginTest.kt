package viaduct.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Usage
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.AssembleSchemaPartitionTask

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
    fun `same project neutral conventions wire schema when application is applied first`() {
        assertSameProjectSchemaWiring(applicationFirst = true)
    }

    @Test
    fun `same project neutral conventions wire schema when module is applied first`() {
        assertSameProjectSchemaWiring(applicationFirst = false)
    }

    private fun assertSameProjectSchemaWiring(applicationFirst: Boolean) {
        val project = ProjectBuilder.builder().build()
        project.registerViaductTopology(modules = mapOf(":" to "resolvers"))
        val plugins = listOf(ViaductApplicationConventionsPlugin::class.java, ViaductModuleConventionsPlugin::class.java)
        (if (applicationFirst) plugins else plugins.reversed()).forEach { project.pluginManager.apply(it) }

        val module = project.extensions.getByType(ViaductModuleConventions::class.java)
        val centralSchema = project.tasks.named("assembleViaductCentralSchema", AssembleCentralSchemaTask::class.java).get()
        val partition = project.tasks.named("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java).get()
        val contributions = project.tasks.named("assembleViaductSchemaContributions").get()

        module.application.get().dependencies.withType(ProjectDependency::class.java).size shouldBe 0
        module.centralSchemaIn.get().files shouldBe setOf(centralSchema.outputDirectory.get().asFile)
        centralSchema.schemaPartitions.files shouldBe setOf(partition.outputDirectory.get().asFile)
        centralSchema.schemaPartitions.buildDependencies.getDependencies(centralSchema) shouldBe setOf(partition)
        centralSchema.schemaContributionFiles.buildDependencies.getDependencies(centralSchema) shouldBe setOf(contributions)
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
