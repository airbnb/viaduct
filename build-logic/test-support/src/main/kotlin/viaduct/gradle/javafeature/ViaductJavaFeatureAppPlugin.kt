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
import viaduct.gradle.defaultschema.DefaultSchemaPlugin
import viaduct.gradle.utils.capitalize

/**
 * Plugin for automatically discovering Java FeatureApp files and generating
 * both schema and tenant code for each discovered file using existing tasks.
 *
 * Mirrors the Kotlin [viaduct.gradle.feature.ViaductFeatureAppPlugin] but uses
 * the already-built Java GRT codegen (JavaGRTsCodegen + JavaResolversCodegen)
 * via process isolation.
 *
 * Schema discovery follows the `extends` chain: if a Java file has no inline SDL
 * markers, the plugin walks up the superclass hierarchy (through Kotlin and Java
 * source files) until it finds a file that contains `#START_SCHEMA`/`#END_SCHEMA`.
 */
abstract class ViaductJavaFeatureAppPlugin : ViaductFeatureAppPluginBase() {
    override val fileTreeIncludes = listOf("**/*.java")
    override val schemaDirName = "java-featureapp-schemas"
    override val taskGroup = "viaduct-java-feature-app"
    override val taskNamePrefix = "Java"
    override val displayName = "Java FeatureApp"

    private lateinit var projectRoot: File

    /**
     * Index from simple class name → `.kt` source file, built once during `afterEvaluate`
     * before schema discovery runs. Avoids walking the project tree inside the recursive
     * [ViaductFeatureAppPluginBase.findSchemaFile] call (which would add Gradle instrumentation frames per file and overflow
     * the JVM stack when the inheritance chain is deep).
     */
    private var ktFileIndex: Map<String, File> = emptyMap()

    override fun apply(project: Project) {
        // Resolve the source root: for projects in included builds, project.rootDir points at the
        // included-build's root (e.g., core/), NOT the oss/ root. Walk up from
        // project.projectDir until a settings.gradle.kts is found to get the real OSS root.
        projectRoot = findSettingsRoot(project.projectDir) ?: project.rootDir
        // Ensure the configuration exists before super.apply() evaluates the build script
        project.getOrCreateCodegenClasspath()
        // Pre-build the .kt file index before the base-class afterEvaluate runs schema discovery.
        // Registering our afterEvaluate BEFORE super.apply() ensures it executes first.
        project.afterEvaluate { ktFileIndex = buildKtFileIndex(projectRoot) }
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

            // Has inline schema markers — process it directly
            if (content.contains("#START_SCHEMA") && content.contains("#END_SCHEMA")) return true

            // No inline SDL — follow extends chain to find schema in a superclass
            findSchemaFile(file, ktFileIndex) != null
        } catch (_: Exception) {
            false
        }
    }

    override fun extractSchemaFromFeatureApp(
        featureAppFile: File,
        outputFile: File
    ) {
        val content = featureAppFile.readText()

        if (content.contains("#START_SCHEMA") && content.contains("#END_SCHEMA")) {
            extractInlineSchema(content, featureAppFile, outputFile)
            return
        }

        // No inline SDL — follow extends chain
        val schemaFile = findSchemaFile(featureAppFile, ktFileIndex)
            ?: throw GradleException(
                "No #START_SCHEMA / #END_SCHEMA markers found in ${featureAppFile.name} or its superclass chain"
            )
        extractSchemaFromFeatureApp(schemaFile, outputFile)
    }

    private fun extractInlineSchema(
        content: String,
        featureAppFile: File,
        outputFile: File
    ) {
        // Use [^\n]* instead of \s* before/after markers to handle both plain Java indentation
        // (spaces only) and Kotlin trimMargin style (| prefix on each line).
        val schemaMarkerPattern = Regex(
            """#START_SCHEMA[^\n]*\n(.*?)\n[^\n]*#END_SCHEMA""",
            setOf(RegexOption.DOT_MATCHES_ALL)
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

    companion object {
        /**
         * Extracts the simple superclass name from a Java (`extends Foo`) or Kotlin class declaration.
         * Delegates to [ViaductFeatureAppPluginBase.extractSuperclassName].
         */
        internal fun extractSuperclassName(content: String): String? = ViaductFeatureAppPluginBase.extractSuperclassName(content)
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
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
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
            this.defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
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
