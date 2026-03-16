package viaduct.gradle.javafeature

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import viaduct.gradle.common.ViaductFeatureAppExtensionBase
import viaduct.gradle.common.ViaductFeatureAppPluginBase
import viaduct.gradle.common.getOrCreateCodegenClasspath
import viaduct.gradle.utils.capitalize

/**
 * Plugin for automatically discovering Java FeatureApp files and generating
 * both schema and tenant code for each discovered file using existing tasks.
 *
 * Mirrors the Kotlin [viaduct.gradle.feature.ViaductFeatureAppPlugin] but uses
 * the already-built Java GRT codegen (JavaGRTsCodegen + JavaResolversCodegen)
 * via process isolation.
 */
abstract class ViaductJavaFeatureAppPlugin : ViaductFeatureAppPluginBase() {
    override val fileTreeIncludes = listOf("**/*.java")
    override val schemaDirName = "java-featureapp-schemas"
    override val taskGroup = "viaduct-java-feature-app"
    override val taskNamePrefix = "Java"
    override val displayName = "Java FeatureApp"

    override fun apply(project: Project) {
        // Ensure the configuration exists before super.apply() evaluates the build script
        project.getOrCreateCodegenClasspath()
        super.apply(project)
    }

    override fun createExtension(project: Project): ViaductFeatureAppExtensionBase {
        return project.extensions.create<ViaductFeatureAppExtensionBase>("viaductJavaFeatureApp", project)
    }

    override fun isFeatureAppFile(file: File): Boolean {
        return try {
            val content = file.readText()

            // Skip base classes
            if (content.contains("abstract class JavaFeatureAppTestBase") ||
                file.name == "JavaFeatureAppTestBase.java"
            ) {
                return false
            }

            // Must have schema markers
            content.contains("#START_SCHEMA") && content.contains("#END_SCHEMA")
        } catch (_: Exception) {
            false
        }
    }

    override fun extractSchemaFromFeatureApp(
        featureAppFile: File,
        outputFile: File
    ) {
        val content = featureAppFile.readText()

        val schemaMarkerPattern = Regex(
            """#START_SCHEMA\s*\n(.*?)\n\s*#END_SCHEMA""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        )

        val markerMatch = schemaMarkerPattern.find(content)
            ?: throw GradleException("No #START_SCHEMA / #END_SCHEMA markers found in ${featureAppFile.name}")

        val rawSchema = markerMatch.groupValues[1]
        val schemaContent = cleanupSchema(rawSchema)

        if (schemaContent.isBlank()) {
            throw GradleException("No valid GraphQL schema found in ${featureAppFile.name}")
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(schemaContent)
    }

    override fun configureSchemaGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        extractionTask: TaskProvider<Task>
    ): TaskProvider<ViaductJavaFeatureAppSchemaTask> {
        return project.tasks.register<ViaductJavaFeatureAppSchemaTask>(
            "generateJava${featureAppName.capitalize()}SchemaObjects"
        ) {
            group = taskGroup
            description = "Generates schema objects for Java FeatureApp $featureAppName"

            dependsOn(extractionTask)
            dependsOn("processResources")

            this.schemaFiles.from(schemaFile)
            this.packageName.set(packageName)
            this.codegenClasspath.from(project.getOrCreateCodegenClasspath())
            this.generatedSrcDir.set(project.layout.buildDirectory.dir("generated-sources/java-featureapp/schema/$featureAppName"))
        }
    }

    override fun configureTenantGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        schemaTask: TaskProvider<out Task>?
    ): TaskProvider<ViaductJavaFeatureAppTenantTask> {
        return project.tasks.register<ViaductJavaFeatureAppTenantTask>(
            "generateJava${featureAppName.capitalize()}Tenant"
        ) {
            group = taskGroup
            description = "Generates tenant code for Java FeatureApp $featureAppName"

            this.schemaFiles.from(schemaFile)
            this.grtPackage.set(packageName)
            this.tenantPackage.set(packageName)
            this.codegenClasspath.from(project.getOrCreateCodegenClasspath())
            this.resolverSrcDir.set(project.layout.buildDirectory.dir("generated-sources/java-featureapp/tenant/$featureAppName/resolverbases"))

            // Depend on schema generation if both are enabled
            schemaTask?.let { dependsOn(it) }
            dependsOn("processResources")
        }
    }
}
