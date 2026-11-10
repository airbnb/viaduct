package viaduct.gradle

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.AssembleFinalSchemaTask
import viaduct.gradle.task.ValidateSchemaExtensionsTask
import viaduct.graphql.schema.validation.ValidationErrorCodes

class ValidateSchemaExtensionsTaskTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private val logger = LoggerFactory.getLogger(ValidateSchemaExtensionsTaskTest::class.java)

    @BeforeEach
    fun setUp() {
        val rootDir = tempDir.resolve("root").toFile().apply { mkdirs() }
        val appDir = tempDir.resolve("root/app").toFile().apply { mkdirs() }

        val root = ProjectBuilder.builder()
            .withName("root")
            .withProjectDir(rootDir)
            .build()
        root.pluginManager.apply("java-library")

        project = ProjectBuilder.builder()
            .withName("app")
            .withParent(root)
            .withProjectDir(appDir)
            .build()
        project.pluginManager.apply("java-library")
        root.registerViaductTopology(":app")
        project.pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
    }

    @Test
    fun `validation uses a JVM dependency bucket and gates both schema stages without feeding final additions back`() {
        val conventions = project.extensions.getByType(ViaductApplicationConventions::class.java)
        val bucket = conventions.schemaValidationPlugins.get()
        bucket.isCanBeDeclared shouldBe true
        bucket.isCanBeResolved shouldBe false
        bucket.isCanBeConsumed shouldBe false
        val classpath = project.configurations.getByName("viaductSchemaValidationClasspath")
        classpath.extendsFrom.contains(bucket) shouldBe true
        classpath.isTransitive shouldBe true
        classpath.attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name shouldBe Usage.JAVA_RUNTIME
        val central = project.tasks.getByName("assembleViaductCentralSchema") as AssembleCentralSchemaTask
        val final = project.tasks.getByName("assembleViaductFinalSchema") as AssembleFinalSchemaTask
        val validation = project.tasks.getByName("validateViaductSchema")
        validation.taskDependencies.getDependencies(validation).map { it.name }.toSet() shouldBe setOf(central.name, final.name)
        val check = project.tasks.getByName("check")
        check.taskDependencies.getDependencies(check).contains(validation) shouldBe true
        final.taskDependencies.getDependencies(final).contains(central) shouldBe true
        central.taskDependencies.getDependencies(central).contains(final) shouldBe false
        conventions.centralSchemaDirectory.get() shouldBe central.outputDirectory.get()
        conventions.finalSchemaDirectory.get() shouldBe final.outputDirectory.get()
        val schemaResources = project.tasks.getByName("generateViaductSchemaResources")
        schemaResources.taskDependencies.getDependencies(schemaResources).contains(final) shouldBe true
    }

    @Test
    fun `validateViaductSchemaExtensions task is registered by the plugin`() {
        val task = project.tasks.findByName("validateViaductSchemaExtensions")

        assert(task != null) { "validateViaductSchemaExtensions task should be registered" }
        assert(task is ValidateSchemaExtensionsTask) { "task should be ValidateSchemaExtensionsTask" }
        assert(task!!.group == "viaduct") { "task group should be 'viaduct'" }
    }

    @Test
    fun `contribution registration rejects invalid provenance plugin IDs`() {
        val contributions = DefaultViaductSchemaContributions(project.tasks.register("testContributions", Sync::class.java), "testmodule")

        listOf("", ".", "..", "dev/example", "dev\\example", "dev:example").forEach { pluginId ->
            val error = assertThrows<IllegalArgumentException> { contributions.register(pluginId, project.files()) }
            error.message shouldBe "Invalid plugin ID: $pluginId"
        }
    }

    @Test
    fun `extensionsOnly mode passes valid schema without cross-module checks`() {
        val validator = ViaductSchemaValidator(logger, extensionsOnly = true)
        val schemaFile = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText(
                """
                enum AppStatus {
                  ACTIVE
                }
                input AppInput {
                  name: String
                }
                type Query {
                  hello: String
                }
                """.trimIndent()
            )
        }

        val errors = validator.validateSchema(listOf(schemaFile))

        errors.shouldBeEmpty()
    }

    @Test
    fun `extensionsOnly mode still reports subscription and custom scalar errors`() {
        val validator = ViaductSchemaValidator(logger, extensionsOnly = true)
        val schemaFile = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText(
                """
                scalar URL
                type Query { link: URL }
                type Subscription { onTick: String }
                schema {
                    query: Query
                    subscription: Subscription
                }
                """.trimIndent()
            )
        }

        val errors = validator.validateSchema(listOf(schemaFile))

        errors shouldHaveSize 2
        val messages = errors.map { it.message }
        messages.shouldExist { it.contains("[${ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED}]") }
        messages.shouldExist { it.contains("[${ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED}]") }
    }

    @Test
    fun `default mode applies ApplicationOnlyDefinitionsRule for partition-sourced directive`() {
        val validator = ViaductSchemaValidator(logger, extensionsOnly = false)
        // Place the file inside a "partition/" path so ApplicationOnlyDefinitionsRule detects it
        val partitionDir = tempDir.resolve("partition/testmodule/graphql").toFile().apply { mkdirs() }
        val directiveFile = partitionDir.resolve("directives.graphqls").apply {
            writeText("directive @customDir on FIELD_DEFINITION\ntype Query { hello: String }")
        }

        val errors = validator.validateSchema(listOf(directiveFile))

        errors.map { it.message }.shouldExist { it.contains("[${ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE}]") }
    }

    @Test
    fun `extensionsOnly mode does not apply ApplicationOnlyDefinitionsRule`() {
        val validator = ViaductSchemaValidator(logger, extensionsOnly = true)
        // Even with a partition-path file, extensions mode skips ApplicationOnlyDefinitionsRule
        val partitionDir = tempDir.resolve("partition/testmodule/graphql").toFile().apply { mkdirs() }
        val directiveFile = partitionDir.resolve("directives.graphqls").apply {
            writeText("directive @customDir on FIELD_DEFINITION\ntype Query { hello: String }")
        }

        val errors = validator.validateSchema(listOf(directiveFile))

        errors.map { it.message }.none { it.contains("[${ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE}]") }
            .shouldBe(true)
    }

    private fun Project.registerViaductTopology(applicationProjectPath: String) {
        gradle.sharedServices.registerIfAbsent(
            ViaductTopologyService.NAME,
            ViaductTopologyService::class.java,
        ) {
            parameters.topologyJson.set(
                ViaductTopologyJson.encode(
                    ViaductApplicationMap(
                        applicationTopologies = mapOf(
                            applicationProjectPath to ViaductApplicationTopology(
                                applicationProjectPath = applicationProjectPath,
                                modulePackagePrefix = "com.example.test",
                                modulePackageSuffixes = emptyMap(),
                            ),
                        ),
                    ),
                ),
            )
        }
    }
}
