package viaduct.gradle.common

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import viaduct.gradle.defaultschema.DefaultSchemaPlugin
import viaduct.gradle.utils.capitalize

/**
 * Abstract base plugin for discovering FeatureApp files and generating
 * both schema and tenant code. Uses the template method pattern so that
 * Kotlin and Java variants only override the parts that differ (file
 * detection heuristics, schema extraction strategy, and codegen task wiring).
 */
abstract class ViaductFeatureAppPluginBase : Plugin<Project> {
    /** File tree include patterns for source discovery */
    protected abstract val fileTreeIncludes: List<String>

    /** Build dir sub-folder for extracted `.graphql` schemas */
    protected abstract val schemaDirName: String

    /** Gradle task group name */
    protected abstract val taskGroup: String

    /** Prefix inserted into generated task names (e.g. `""` or `"Java"`) */
    protected abstract val taskNamePrefix: String

    /** Human-readable name used in task descriptions */
    protected abstract val displayName: String

    // ── abstract behavioural hooks ──────────────────────────────────────

    /** Create the typed extension and register it on the project. */
    protected abstract fun createExtension(project: Project): ViaductFeatureAppExtensionBase

    /** Return `true` if [file] is a FeatureApp that should be processed. */
    protected abstract fun isFeatureAppFile(file: File): Boolean

    /** Extract the GraphQL schema from [featureAppFile] and write it to [outputFile]. */
    protected abstract fun extractSchemaFromFeatureApp(
        featureAppFile: File,
        outputFile: File
    )

    /** Register the schema-generation task and return its provider. */
    protected abstract fun configureSchemaGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        extractionTask: TaskProvider<Task>
    ): TaskProvider<out Task>

    /** Register the tenant-generation task and return its provider. */
    protected abstract fun configureTenantGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        schemaTask: TaskProvider<out Task>?
    ): TaskProvider<out Task>

    // ── concrete lifecycle ──────────────────────────────────────────────

    override fun apply(project: Project) {
        val extension = createExtension(project)

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

    // ── concrete helpers ────────────────────────────────────────────────

    /**
     * Discover FeatureApp files in the project's test source sets.
     */
    private fun discoverFeatureAppFiles(
        project: Project,
        extension: ViaductFeatureAppExtensionBase
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
                    include(fileTreeIncludes)
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
     * Configure schema and tenant generation for a specific FeatureApp file.
     */
    private fun configureFeatureApp(
        project: Project,
        featureAppFile: File,
        extension: ViaductFeatureAppExtensionBase
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
        val schemaDir = File(project.buildDir, schemaDirName)
        val schemaFile = File(schemaDir, "$featureAppName.graphql")

        // Create schema extraction task
        val extractionTask = project.tasks.register("extract${taskNamePrefix}${featureAppName.capitalize()}Schema") {
            group = taskGroup
            description = "Extracts schema from $displayName $featureAppName"

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
     * Extract package name from a Kotlin or Java source file.
     */
    protected fun extractPackageFromFile(file: File): String? {
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
     * Clean up extracted schema content by stripping margin characters and markers.
     */
    protected fun cleanupSchema(rawSchema: String): String {
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
}
