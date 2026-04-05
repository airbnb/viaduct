package viaduct.gradle.featureappcontract

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
import viaduct.gradle.javafeature.ViaductJavaFeatureAppSchemaTask
import viaduct.gradle.javafeature.ViaductJavaFeatureAppTenantTask
import viaduct.gradle.utils.capitalize

/**
 * Plugin for contract-style FeatureApp tests where the schema is defined in a
 * base class via `@TestSchema` annotation and subclasses provide resolver implementations.
 *
 * Schema discovery follows the superclass chain until it finds a file containing
 * `@TestSchema("""`.
 */
abstract class ViaductFeatureAppContractPlugin : Plugin<Project> {
    /**
     * Index from simple class name → source file, built once during `afterEvaluate`.
     * Avoids walking the project tree inside the recursive [ViaductFeatureAppPluginBase.findSchemaFile] call.
     */
    private var ktFileIndex: Map<String, File> = emptyMap()

    private lateinit var projectRoot: File

    override fun apply(project: Project) {
        val extension = project.extensions.create<ViaductFeatureAppContractExtension>("viaductFeatureAppContract", project)

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
        extension: ViaductFeatureAppContractExtension
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
     * Check if a file is a contract test by examining its content.
     *
     * A file qualifies if it has a `@TestSchema` annotation directly,
     * or if any file in its superclass chain does.
     */
    private fun isFeatureAppFile(file: File): Boolean {
        return try {
            val content = file.readText()

            // Skip base classes
            if (file.name == "FeatureAppTestBase.kt") return false

            // Has @TestSchema annotation directly
            if (content.contains("@TestSchema(")) return true

            // Follow superclass chain to find @TestSchema
            findAnnotatedSchemaFile(file) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Walk the superclass chain to find a file containing `@TestSchema(`.
     */
    private fun findAnnotatedSchemaFile(startFile: File): File? {
        val visited = mutableSetOf<File>()
        var file = startFile
        while (true) {
            if (!visited.add(file)) return null
            val content = try {
                file.readText()
            } catch (_: Exception) {
                return null
            }
            if (content.contains("@TestSchema(")) return file
            val parentName = ViaductFeatureAppPluginBase.extractSuperclassName(content) ?: return null
            file = ktFileIndex[parentName] ?: return null
        }
    }

    /**
     * Configure schema and tenant generation for a specific FeatureApp file
     */
    private fun configureFeatureApp(
        project: Project,
        featureAppFile: File,
        extension: ViaductFeatureAppContractExtension,
        codegenClasspath: Configuration
    ) {
        // Extract a clean name for the FeatureApp
        val fileName = featureAppFile.nameWithoutExtension
        val featureAppName = fileName
            .replace("FeatureAppTest", "")
            .replace("FeatureApp", "")
            .replace("ContractTest", "")
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
            group = "viaduct-feature-app-contract"
            description = "Extracts schema from contract test $featureAppName"

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

        val tenantTask = configureTenantGeneration(project, featureAppName, schemaFile, packageName, schemaTask, codegenClasspath)
        targetSourceSet.java.srcDir(tenantTask.flatMap { it.resolverSrcDir })
    }

    /**
     * Extract GraphQL schema from @TestSchema annotation in the file.
     * If not found, follows the superclass chain.
     */
    private fun extractSchemaFromFeatureApp(
        featureAppFile: File,
        outputFile: File
    ) {
        val content = featureAppFile.readText()

        // Try to find schema in @TestSchema(""" ... """)
        val annotationPattern = Regex(
            """@TestSchema\(\s*"{3}(.*?)"{3}\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        val match = annotationPattern.find(content)
        if (match != null) {
            val schemaContent = match.groupValues[1].trimIndent().trim()
            if (schemaContent.isNotBlank()) {
                outputFile.parentFile.mkdirs()
                outputFile.writeText(schemaContent)
                return
            }
        }

        // No annotation found — follow superclass chain
        val schemaFile = findAnnotatedSchemaFile(featureAppFile)
            ?: throw GradleException("No @TestSchema annotation found in ${featureAppFile.name} or its superclass chain")
        extractSchemaFromFeatureApp(schemaFile, outputFile)
    }

    /**
     * Configure schema generation using Java GRT codegen
     */
    private fun configureSchemaGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        extractionTask: TaskProvider<Task>,
        codegenClasspath: Configuration
    ): TaskProvider<ViaductJavaFeatureAppSchemaTask> {
        return project.tasks.register<ViaductJavaFeatureAppSchemaTask>(
            "generate${featureAppName.capitalize()}SchemaObjects"
        ) {
            group = "viaduct-feature-app-contract"
            description = "Generates schema objects for contract test $featureAppName"

            dependsOn(extractionTask)
            dependsOn("processResources")

            this.schemaFiles.from(schemaFile)
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.packageName.set(packageName)
            this.codegenClasspath.from(codegenClasspath)
            this.generatedSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp-contract/schema/$featureAppName"))
        }
    }

    /**
     * Configure tenant generation using Java resolver codegen
     */
    private fun configureTenantGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        schemaTask: TaskProvider<ViaductJavaFeatureAppSchemaTask>?,
        codegenClasspath: Configuration
    ): TaskProvider<ViaductJavaFeatureAppTenantTask> {
        val ssName = "test"
        val schemaOutputDirProvider = project.layout.buildDirectory.dir("generated-sources/featureapp-contract/schema/$featureAppName")

        // Add schema generated classes directory to the target source set classpath
        val implConfigName = "${ssName}Implementation"
        project.dependencies.add(
            implConfigName,
            project.files(schemaOutputDirProvider).also { fc -> schemaTask?.let { fc.builtBy(it) } }
        )

        return project.tasks.register<ViaductJavaFeatureAppTenantTask>(
            "generate${featureAppName.capitalize()}Tenant"
        ) {
            group = "viaduct-feature-app-contract"
            description = "Generates tenant code for contract test $featureAppName"

            this.schemaFiles.from(schemaFile)
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.grtPackage.set(packageName)
            this.tenantPackage.set(packageName)
            this.codegenClasspath.from(codegenClasspath)
            this.resolverSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp-contract/tenant/$featureAppName/resolverbases"))

            schemaTask?.let { dependsOn(it) }
            dependsOn("processResources")
        }
    }
}
