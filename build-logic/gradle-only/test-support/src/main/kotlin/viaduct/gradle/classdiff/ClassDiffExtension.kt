package viaduct.gradle.classdiff

import java.io.File
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/**
 * Extension for configuring ClassDiff code generation.
 *
 * This extension allows configuration of multiple schema diffs, each with their own
 * packages and schema resources. The plugin will generate code for each configured
 * schema diff independently.
 */
open class ClassDiffExtension(private val project: Project) {
    /**
     * Container of schema diff configurations. Each call to [schemaDiff] registers
     * tasks immediately — no afterEvaluate needed.
     */
    val schemaDiffs: NamedDomainObjectContainer<SchemaDiff> =
        project.objects.domainObjectContainer(SchemaDiff::class.java) { name -> SchemaDiff(name, project) }

    /**
     * The source set to wire generated code into.
     * Default: "test". Must be set before any [schemaDiff] calls if overriding the default.
     */
    val sourceSetName: Property<String> = project.objects.property(String::class.java)
        .convention("test")

    /**
     * Create and configure a new schema diff, immediately registering its codegen tasks.
     */
    fun schemaDiff(
        name: String,
        configure: SchemaDiff.() -> Unit
    ) = schemaDiffs.create(name, configure)
}

/**
 * Configuration for a single schema diff, including packages and schema resources.
 */
class SchemaDiff(
    private val diffName: String,
    private val project: Project
) : Named {
    override fun getName(): String = diffName

    /**
     * Package name for generated schema objects (actuals).
     * Default: "actuals.api.generated.{name}"
     */
    val actualPackage: Property<String> = project.objects.property(String::class.java)
        .convention("actuals.api.generated.$diffName")

    /**
     * Package name for generated GRT objects (expected).
     * Default: "viaduct.api.grts.{name}"
     */
    val expectedPackage: Property<String> = project.objects.property(String::class.java)
        .convention("viaduct.api.grts.$diffName")

    /**
     * List of schema resource paths (relative paths within the source set resource dirs).
     */
    private val schemaResources: ListProperty<String> = project.objects.listProperty(String::class.java)

    /**
     * Source set names to search (e.g., "main", "test"). Defaults to ["main", "test"].
     */
    val sourceSetNames: ListProperty<String> = project.objects.listProperty(String::class.java)
        .convention(listOf(SourceSet.MAIN_SOURCE_SET_NAME, SourceSet.TEST_SOURCE_SET_NAME))

    /**
     * Add a schema resource file path.
     * The path should be relative to a resource directory of the selected source sets.
     */
    fun schemaResource(path: String) {
        schemaResources.add(path)
    }

    /**
     * Add multiple schema resource file paths.
     */
    @Suppress("unused")
    fun schemaResources(vararg paths: String) {
        schemaResources.addAll(paths.toList())
    }

    /**
     * Resolve schema resource files to actual File objects by searching the configured source sets'
     * resource directories.
     */
    internal fun resolveSchemaFiles(): List<File> {
        val container = project.extensions.findByType(SourceSetContainer::class.java)
        if (container == null) {
            project.logger.warn("No SourceSetContainer found. Ensure the Java/Kotlin plugin is applied.")
            return emptyList()
        }

        val selectedSets: List<SourceSet> =
            sourceSetNames.get()
                .mapNotNull { ssName ->
                    container.findByName(ssName).also {
                        if (it == null) {
                            project.logger.warn("Source set '$ssName' not found in project '${project.path}'.")
                        }
                    }
                }

        if (selectedSets.isEmpty()) {
            project.logger.warn("No valid source sets configured for schema diff '$diffName'.")
            return emptyList()
        }

        // Collect all resource roots from the selected source sets
        val resourceRoots: List<File> =
            selectedSets.flatMap { it.resources.srcDirs }
                .filter { it.exists() && it.isDirectory }

        if (resourceRoots.isEmpty()) {
            project.logger.info(
                "No resource directories found in source sets ${sourceSetNames.get()} for schema diff '$diffName'."
            )
        }

        // For each declared resource path, find the first matching file under any resource root.
        val resolved = mutableListOf<File>()
        schemaResources.get().forEach { resourcePath ->
            val normalized = resourcePath.trimStart('/', '\\')
            val found = resourceRoots
                .asSequence()
                .map { root -> File(root, normalized) }
                .firstOrNull { it.exists() && it.isFile }

            if (found != null) {
                resolved += found
            } else {
                project.logger.warn(
                    "Schema resource '$resourcePath' not found in source sets ${sourceSetNames.get()} " +
                        "for schema diff '$diffName'. Searched roots: ${resourceRoots.joinToString { it.absolutePath }}"
                )
            }
        }

        return resolved
    }
}
