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
import viaduct.gradle.feature.ViaductFeatureAppSchemaTask
import viaduct.gradle.feature.ViaductFeatureAppTenantTask
import viaduct.gradle.shared.BuildFlags
import viaduct.gradle.utils.capitalize

/**
 * Plugin for Kotlin contract-style FeatureApp tests where the schema is defined in a
 * base class via `@TestSchema` annotation and subclasses provide resolver implementations.
 *
 * Uses Kotlin codegen (bytecode GRTs + modern resolver sources).
 */
abstract class ViaductKotlinFeatureAppContractPlugin : Plugin<Project> {
    private var ktFileIndex: Map<String, File> = emptyMap()
    private lateinit var projectRoot: File

    override fun apply(project: Project) {
        val extension = project.extensions.create<ViaductFeatureAppContractExtension>("viaductKotlinFeatureAppContract", project)

        val codegenClasspath = project.getOrCreateCodegenClasspath()

        projectRoot = ViaductFeatureAppPluginBase.findSettingsRoot(project.projectDir) ?: project.rootDir

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

    private fun discoverFeatureAppFiles(
        project: Project,
        extension: ViaductFeatureAppContractExtension
    ): List<File> {
        val ssName = extension.sourceSetName.get()
        val targetSS = project.extensions.getByType(JavaPluginExtension::class.java)
            .sourceSets.getByName(ssName)

        val roots = (targetSS.allSource.srcDirs + targetSS.resources.srcDirs)
            .filter { it.exists() }
            .toSet()

        val pattern = extension.fileNamePattern.get().toRegex()

        return roots
            .flatMap { root ->
                project.fileTree(root).matching {
                    include("**/*.kt")
                }.files
            }
            .asSequence()
            .filter { pattern.containsMatchIn(it.name) }
            .filter(::isFeatureAppFile)
            .map { it.canonicalFile }
            .distinct()
            .toList()
    }

    private fun isFeatureAppFile(file: File): Boolean {
        return try {
            val content = file.readText()
            if (file.name == "FeatureAppTestBase.kt") return false

            // Has @TestSchema directly
            if (content.contains("@TestSchema(")) return true

            // Walk superclass chain to find @TestSchema
            findAnnotatedSchemaFile(file) != null
        } catch (_: Exception) {
            false
        }
    }

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
            if (file.name == "FeatureAppTestBase.kt" || file.name == "AbstractFeatureAppTestBase.kt") return null
            if (content.contains("@TestSchema(")) return file
            val parentName = ViaductFeatureAppPluginBase.extractSuperclassName(content) ?: return null
            file = ktFileIndex[parentName] ?: return null
        }
    }

    private fun configureFeatureApp(
        project: Project,
        featureAppFile: File,
        extension: ViaductFeatureAppContractExtension,
        codegenClasspath: Configuration
    ) {
        val fileName = featureAppFile.nameWithoutExtension
        val featureAppName = fileName
            .replace("FeatureAppTest", "")
            .replace("FeatureApp", "")
            .replace("ContractTest", "")
            .replace("Test", "")
            .lowercase()
            .ifEmpty { "default" }

        val packageName = ViaductFeatureAppPluginBase.extractPackageFromFile(featureAppFile)
            ?: "${extension.basePackageName.get()}.$featureAppName"
        require(packageName.contains(".")) {
            "Invalid package name '$packageName'. Must contain at least one segment."
        }

        val schemaDir = project.layout.buildDirectory.dir("featureapp-contract-schemas").get().asFile
        val schemaFile = File(schemaDir, "$featureAppName.graphql")

        val extractionTask = project.tasks.register("extract${featureAppName.capitalize()}ContractSchema") {
            group = "viaduct-feature-app-contract"
            description = "Extracts schema from Kotlin contract test $featureAppName"

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

    private fun extractSchemaFromFeatureApp(
        featureAppFile: File,
        outputFile: File
    ) {
        val content = featureAppFile.readText()

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

        val schemaFile = findAnnotatedSchemaFile(featureAppFile)
            ?: throw GradleException("No @TestSchema annotation found in ${featureAppFile.name} or its superclass chain")
        extractSchemaFromFeatureApp(schemaFile, outputFile)
    }

    private fun configureSchemaGeneration(
        project: Project,
        featureAppName: String,
        schemaFile: File,
        packageName: String,
        extractionTask: TaskProvider<Task>,
        codegenClasspath: Configuration
    ): TaskProvider<ViaductFeatureAppSchemaTask> {
        return project.tasks.register<ViaductFeatureAppSchemaTask>(
            "generate${featureAppName.capitalize()}ContractSchemaObjects"
        ) {
            group = "viaduct-feature-app-contract"
            description = "Generates schema objects for Kotlin contract test $featureAppName"

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
            this.generatedSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp-contract/schema/$featureAppName"))
        }
    }

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

        val schemaOutputDirProvider = project.layout.buildDirectory.dir("generated-sources/featureapp-contract/schema/$featureAppName")

        val implConfigName = "${sourceSetName}Implementation"
        project.dependencies.add(
            implConfigName,
            project.files(schemaOutputDirProvider).also { fc -> schemaTask?.let { fc.builtBy(it) } }
        )

        return project.tasks.register<ViaductFeatureAppTenantTask>(
            "generate${featureAppName.capitalize()}ContractTenant"
        ) {
            group = "viaduct-feature-app-contract"
            description = "Generates tenant code for Kotlin contract test $featureAppName"

            this.tenantName.set(tenantName)
            this.packageNamePrefix.set(tenantPackageName)
            this.featureAppTest.set(true)
            this.buildFlags.putAll(BuildFlags.DEFAULT)
            this.schemaFiles.from(schemaFile)
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.tenantFromSourceNameRegex.set("(.*)")
            this.codegenClasspath.from(codegenClasspath)
            this.modernModuleSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp-contract/tenant/$featureAppName/modernmodule"))
            this.resolverSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp-contract/tenant/$featureAppName/resolverbases"))
            this.metaInfSrcDir.set(project.layout.buildDirectory.dir("generated-sources/featureapp-contract/tenant/$featureAppName/META-INF"))

            schemaTask?.let { dependsOn(it) }
            dependsOn("processResources")
        }
    }
}
