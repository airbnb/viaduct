package viaduct.gradle.feature

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductPluginCommon
import viaduct.gradle.defaultschema.DefaultSchemaPlugin
import viaduct.gradle.utils.capitalize

/**
 * Plugin for automatically discovering FeatureApp files and generating
 * both schema and tenant code for each discovered file using existing tasks
 */
abstract class ViaductFeatureAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<ViaductFeatureAppExtension>("viaductFeatureApp", project)

        // Ensure default schema plugin is applied so default schema is available
        DefaultSchemaPlugin.ensureApplied(project)

        project.afterEvaluate {
            val featureAppFiles = discoverFeatureAppFiles(project, extension)
            if (featureAppFiles.isEmpty()) {
                return@afterEvaluate
            }
            featureAppFiles.forEach { featureAppFile ->
                configureFeatureApp(project, featureAppFile, extension)
            }
        }
    }

    /**
     * Discover FeatureApp files in the project
     */
    private fun discoverFeatureAppFiles(
        project: Project,
        extension: ViaductFeatureAppExtension
    ): List<File> {
        val testSS = project.extensions.getByType(JavaPluginExtension::class.java)
            .sourceSets.getByName("test")

        // allSource includes Kotlin sources when the Kotlin plugin is applied
        val roots = (testSS.allSource.srcDirs + testSS.resources.srcDirs)
            .filter { it.exists() }
            .toSet()

        val pattern = extension.fileNamePattern.get().toRegex()

        return roots
            .flatMap { root ->
                project.fileTree(root).matching {
                    include("**/*.kt", "**/*.java")
                }.files
            }
            .asSequence()
            .filter { pattern.containsMatchIn(it.name) }
            .filter(::isFeatureAppFile)
            .map { it.canonicalFile }
            .distinct()
            .toList()
    }

    /**
     * Check if a file is a FeatureApp by examining its content
     */
    private fun isFeatureAppFile(file: File): Boolean {
        return try {
            val content = file.readText()

            // Skip base classes and abstract classes
            if (content.contains("interface FeatureAppTestBase") ||
                file.name == "FeatureAppTestBase.kt"
            ) {
                return false
            }

            // Must have either schema markers or override sdl
            val hasSchemaMarker = content.contains("#START_SCHEMA") && content.contains("#END_SCHEMA")
            val hasOverrideSdl = content.contains(Regex("override\\s+var\\s+sdl\\s*="))

            hasSchemaMarker || hasOverrideSdl
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Configure schema and tenant generation for a specific FeatureApp file
     */
    private fun configureFeatureApp(
        project: Project,
        featureAppFile: File,
        extension: ViaductFeatureAppExtension
    ) {
        // Extract a clean name for the FeatureApp
        val fileName = featureAppFile.nameWithoutExtension
        val featureAppName = fileName
            .replace("FeatureAppTest", "")
            .replace("FeatureApp", "")
            .replace("Test", "")
            .lowercase()
            .ifEmpty { "default" }

        val packageName = extractPackageFromFile(featureAppFile) ?: "${extension.basePackageName.get()}.$featureAppName"
        if (!packageName.contains(".")) {
            throw GradleException("Invalid package name '$packageName'. Package name must contain at least one segment (e.g., 'com.example.feature')")
        }

        @Suppress("DEPRECATION")
        val schemaDir = File(project.buildDir, "featureapp-schemas")
        val schemaFile = File(schemaDir, "$featureAppName.graphql")

        // Create schema extraction task
        val extractionTask = project.tasks.register("extract${featureAppName.capitalize()}Schema") {
            group = "viaduct-feature-app"
            description = "Extracts schema from FeatureApp $featureAppName"

            inputs.file(featureAppFile)
            outputs.file(schemaFile)

            doLast {
                schemaDir.mkdirs()
                try {
                    extractSchemaFromFeatureApp(featureAppFile, schemaFile)
                } catch (e: Exception) {
                    throw GradleException("Failed to extract schema from ${featureAppFile.name}: ${e.message}", e)
                }
            }
        }

        val javaExtension = project.extensions.getByType<JavaPluginExtension>()
        val testSourceSet = javaExtension.sourceSets.getByName("test")

        val schemaTask = configureSchemaGeneration(project, featureAppName, schemaFile, packageName, extractionTask)
        testSourceSet.java.srcDir(schemaTask.map { it.outputs.files })

        val tenantTask = configureTenantGeneration(project, featureAppName, schemaFile, packageName, schemaTask)
        testSourceSet.java.srcDir(tenantTask.map { it.outputs.files })
    }

    /**
     * Extract package name from Kotlin/Java file
     */
    private fun extractPackageFromFile(file: File): String? {
        return try {
            val content = file.readText()
            val packagePattern = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)
            val match = packagePattern.find(content)
            match?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract GraphQL schema from FeatureApp file
     */
    private fun extractSchemaFromFeatureApp(
        featureAppFile: File,
        outputFile: File
    ) {
        val content = featureAppFile.readText()
        var schemaContent: String? = null

        // Try to find schema between #START_SCHEMA and #END_SCHEMA markers
        val schemaMarkerPattern = Regex(
            """#START_SCHEMA\s*\n(.*?)\n\s*#END_SCHEMA""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        )

        val markerMatch = schemaMarkerPattern.find(content)
        if (markerMatch != null) {
            val rawSchema = markerMatch.groupValues[1]
            schemaContent = cleanupSchema(rawSchema)
        }

        // If no markers found, try to extract from sdl property
        if (schemaContent == null) {
            val sdlPattern = Regex(
                """override\s+var\s+sdl\s*=\s*"{3}(.*?)"{3}""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )

            val sdlMatch = sdlPattern.find(content)
            if (sdlMatch != null) {
                val rawSchema = sdlMatch.groupValues[1]
                schemaContent = cleanupSchema(rawSchema)
            }
        }

        if (schemaContent.isNullOrBlank()) {
            throw GradleException("No valid GraphQL schema found in ${featureAppFile.name}")
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(schemaContent)
    }

    /**
     * Clean up extracted schema content
     */
    private fun cleanupSchema(rawSchema: String): String {
        return rawSchema.lines()
            .map { line ->
                line.trimStart()
                    .removePrefix("|")
                    .trimStart()
            }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("#START_SCHEMA") &&
                    !line.startsWith("#END_SCHEMA")
            }
            .joinToString("\n")
            .trim()
    }

    /**
     * Configure schema generation using ViaductSchemaTask
     */
    private fun configureSchemaGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        extractionTask: TaskProvider<Task>
    ): TaskProvider<ViaductFeatureAppSchemaTask> {
        return project.tasks.register<ViaductFeatureAppSchemaTask>(
            "generate${featureAppName.capitalize()}SchemaObjects"
        ) {
            group = "viaduct-feature-app"
            description = "Generates schema objects for FeatureApp $featureAppName"

            dependsOn(extractionTask)
            dependsOn("processResources")

            this.schemaName.set("default")
            this.packageName.set(packageName)
            this.buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            this.workerNumber.set(0)
            this.workerCount.set(1)
            this.includeIneligibleForTesting.set(true)
            this.schemaFiles.from(schemaFile)
            this.generatedSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp/schema/$featureAppName"))
        }
    }

    /**
     * Configure tenant generation using ViaductTenantTask
     */
    private fun configureTenantGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        schemaTask: TaskProvider<ViaductFeatureAppSchemaTask>?
    ): TaskProvider<ViaductFeatureAppTenantTask> {
        val tenantName = packageName.split(".").last()
        val tenantPackageName = packageName.split(".").dropLast(1).joinToString(".")

        val schemaOutputDir = project.layout.buildDirectory.dir("generated-sources/featureapp/schema/$featureAppName").get()

        // Add schema generated classes directory to the test classpath only (not implementation)
        // This prevents the generated sources from leaking to consuming projects
        project.dependencies.add("testImplementation", project.files(schemaOutputDir))

        return project.tasks.register<ViaductFeatureAppTenantTask>(
            "generate${featureAppName.capitalize()}Tenant"
        ) {
            group = "viaduct-feature-app"
            description = "Generates tenant code for FeatureApp $featureAppName"

            this.tenantName.set(tenantName)
            this.packageNamePrefix.set(tenantPackageName)
            this.buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            this.schemaFiles.from(schemaFile)
            this.tenantFromSourceNameRegex.set("(.*)")
            this.modernModuleSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp/tenant/$featureAppName/modernmodule"))
            this.resolverSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp/tenant/$featureAppName/resolverbases"))
            this.metaInfSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp/tenant/$featureAppName/META-INF"))

            // Depend on schema generation if both are enabled
            schemaTask?.let { dependsOn(it) }
            dependsOn("processResources")
        }
    }
}
