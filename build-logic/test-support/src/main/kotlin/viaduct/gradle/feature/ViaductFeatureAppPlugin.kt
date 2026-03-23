package viaduct.gradle.feature

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import viaduct.gradle.common.ViaductFeatureAppPluginBase
import viaduct.gradle.common.getOrCreateCodegenClasspath
import viaduct.gradle.defaultschema.DefaultSchemaPlugin
import viaduct.gradle.shared.BuildFlags
import viaduct.gradle.utils.capitalize

/**
 * Plugin for automatically discovering FeatureApp files and generating
 * both schema and tenant code for each discovered file using existing tasks.
 *
 * Schema discovery follows the `:` (superclass) chain: if a Kotlin file has no inline SDL
 * markers or `override var sdl`, the plugin walks up the superclass hierarchy until it
 * finds a file that contains `#START_SCHEMA`/`#END_SCHEMA`.
 */
abstract class ViaductFeatureAppPlugin : Plugin<Project> {
    /**
     * Index from simple class name → `.kt` source file, built once during `afterEvaluate`.
     * Avoids walking the project tree inside the recursive [ViaductFeatureAppPluginBase.findSchemaFile] call.
     */
    private var ktFileIndex: Map<String, File> = emptyMap()

    private lateinit var projectRoot: File

    override fun apply(project: Project) {
        val extension = project.extensions.create<ViaductFeatureAppExtension>("viaductFeatureApp", project)

        val codegenClasspath = project.getOrCreateCodegenClasspath()

        // Walk up from projectDir to find the settings root (the real OSS root)
        projectRoot = ViaductFeatureAppPluginBase.findSettingsRoot(project.projectDir) ?: project.rootDir

        // Ensure default schema plugin is applied so default schema is available
        DefaultSchemaPlugin.ensureApplied(project)

        project.afterEvaluate {
            ktFileIndex = ViaductFeatureAppPluginBase.buildKtFileIndex(projectRoot)

            val ssName = extension.sourceSetName.get()
            DefaultSchemaPlugin.wireToSourceSet(project, ssName)

            val featureAppFiles = discoverFeatureAppFiles(project, extension)
            if (featureAppFiles.isEmpty()) {
                return@afterEvaluate
            }
            featureAppFiles.forEach { featureAppFile ->
                configureFeatureApp(project, featureAppFile, extension, codegenClasspath)
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
        val ssName = extension.sourceSetName.get()
        val targetSS = project.extensions.getByType(JavaPluginExtension::class.java)
            .sourceSets.getByName(ssName)

        // allSource includes Kotlin sources when the Kotlin plugin is applied
        val roots = (targetSS.allSource.srcDirs + targetSS.resources.srcDirs)
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
     * Check if a file is a FeatureApp by examining its content.
     *
     * A file qualifies if it has inline schema markers, an `override var sdl` property,
     * or if any file in its superclass chain contains `#START_SCHEMA`/`#END_SCHEMA`.
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

            // Has inline schema markers — process it directly
            if (content.contains("#START_SCHEMA") && content.contains("#END_SCHEMA")) return true

            // Has override sdl — process it directly
            if (content.contains(Regex("override\\s+var\\s+sdl\\s*="))) return true

            // No inline SDL — follow superclass chain to find schema
            ViaductFeatureAppPluginBase.findSchemaFile(file, ktFileIndex) != null
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
        extension: ViaductFeatureAppExtension,
        codegenClasspath: Configuration
    ) {
        // Extract a clean name for the FeatureApp
        val fileName = featureAppFile.nameWithoutExtension
        val featureAppName = fileName
            .replace("FeatureAppTest", "")
            .replace("FeatureApp", "")
            .replace("Test", "")
            .lowercase()
            .ifEmpty { "default" }

        val packageName = ViaductFeatureAppPluginBase.extractPackageFromFile(featureAppFile) ?: "${extension.basePackageName.get()}.$featureAppName"
        if (!packageName.contains(".")) {
            throw GradleException("Invalid package name '$packageName'. Package name must contain at least one segment (e.g., 'com.example.feature')")
        }

        val schemaDir = project.layout.buildDirectory.dir("featureapp-schemas").get().asFile
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

        val ssName = extension.sourceSetName.get()
        val javaExtension = project.extensions.getByType<JavaPluginExtension>()
        val targetSourceSet = javaExtension.sourceSets.getByName(ssName)

        val schemaTask = configureSchemaGeneration(project, featureAppName, schemaFile, packageName, extractionTask, codegenClasspath)
        targetSourceSet.java.srcDir(schemaTask.flatMap { it.generatedSrcDir })

        val tenantTask = configureTenantGeneration(project, featureAppName, schemaFile, packageName, schemaTask, ssName, codegenClasspath)
        targetSourceSet.java.srcDir(tenantTask.flatMap { it.modernModuleSrcDir })
        targetSourceSet.java.srcDir(tenantTask.flatMap { it.resolverSrcDir })
        targetSourceSet.java.srcDir(tenantTask.flatMap { it.metaInfSrcDir })
    }

    /**
     * Extract GraphQL schema from FeatureApp file.
     * If no inline schema is found, follows the superclass chain.
     */
    private fun extractSchemaFromFeatureApp(
        featureAppFile: File,
        outputFile: File
    ) {
        val content = featureAppFile.readText()
        var schemaContent: String? = null

        // Try to find schema between #START_SCHEMA and #END_SCHEMA markers.
        // Use [^\n]* before/after markers to handle both plain indentation (spaces)
        // and Kotlin trimMargin style (| prefix on each line).
        val schemaMarkerPattern = Regex(
            """#START_SCHEMA[^\n]*\n(.*?)\n[^\n]*#END_SCHEMA""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        val markerMatch = schemaMarkerPattern.find(content)
        if (markerMatch != null) {
            val rawSchema = markerMatch.groupValues[1]
            schemaContent = ViaductFeatureAppPluginBase.cleanupSchema(rawSchema)
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
                schemaContent = ViaductFeatureAppPluginBase.cleanupSchema(rawSchema)
            }
        }

        // If no inline schema found, follow superclass chain
        if (schemaContent.isNullOrBlank()) {
            val schemaFile = ViaductFeatureAppPluginBase.findSchemaFile(featureAppFile, ktFileIndex)
                ?: throw GradleException("No valid GraphQL schema found in ${featureAppFile.name} or its superclass chain")
            extractSchemaFromFeatureApp(schemaFile, outputFile)
            return
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(schemaContent)
    }

    /**
     * Configure schema generation using ViaductSchemaTask
     */
    private fun configureSchemaGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        extractionTask: TaskProvider<Task>,
        codegenClasspath: Configuration
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
            this.buildFlags.putAll(BuildFlags.DEFAULT)
            this.workerNumber.set(0)
            this.workerCount.set(1)
            this.includeIneligibleForTesting.set(true)
            this.schemaFiles.from(schemaFile)
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.codegenClasspath.from(codegenClasspath)
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
        schemaTask: TaskProvider<ViaductFeatureAppSchemaTask>?,
        sourceSetName: String,
        codegenClasspath: Configuration
    ): TaskProvider<ViaductFeatureAppTenantTask> {
        val tenantName = packageName.split(".").last()
        val tenantPackageName = packageName.split(".").dropLast(1).joinToString(".")

        val schemaOutputDirProvider = project.layout.buildDirectory.dir("generated-sources/featureapp/schema/$featureAppName")

        // Add schema generated classes directory to the target source set classpath only
        // This prevents the generated sources from leaking to consuming projects
        val implConfigName = "${sourceSetName}Implementation"
        project.dependencies.add(
            implConfigName,
            project.files(schemaOutputDirProvider).also { fc -> schemaTask?.let { fc.builtBy(it) } }
        )

        return project.tasks.register<ViaductFeatureAppTenantTask>(
            "generate${featureAppName.capitalize()}Tenant"
        ) {
            group = "viaduct-feature-app"
            description = "Generates tenant code for FeatureApp $featureAppName"

            this.tenantName.set(tenantName)
            this.packageNamePrefix.set(tenantPackageName)
            this.featureAppTest.set(true)
            this.buildFlags.putAll(BuildFlags.DEFAULT)
            this.schemaFiles.from(schemaFile)
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.tenantFromSourceNameRegex.set("(.*)")
            this.codegenClasspath.from(codegenClasspath)
            this.modernModuleSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp/tenant/$featureAppName/modernmodule"))
            this.resolverSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp/tenant/$featureAppName/resolverbases"))
            this.metaInfSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp/tenant/$featureAppName/META-INF"))

            // Depend on schema generation if both are enabled
            schemaTask?.let { dependsOn(it) }
            dependsOn("processResources")
        }
    }
}
