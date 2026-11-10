package viaduct.gradle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import viaduct.apiannotations.StableApi
import viaduct.graphql.schema.SchemaProvenance

@StableApi
interface ViaductSchemaContributions {
    fun register(
        pluginId: String,
        files: FileCollection,
    )
}

@StableApi
val Project.viaductSchemaContributions: ViaductSchemaContributions
    get() = extensions.getByType(ViaductSchemaContributions::class.java)

internal class DefaultViaductSchemaContributions(
    private val assembleTask: TaskProvider<Sync>,
    private val moduleId: String,
) : ViaductSchemaContributions {
    override fun register(
        pluginId: String,
        files: FileCollection,
    ) {
        require(pluginId.matches(Regex("[A-Za-z0-9_.-]+")) && pluginId != "." && pluginId != "..") { "Invalid plugin ID: $pluginId" }
        assembleTask.configure {
            from(files) {
                include("**/*.graphqls")
                into(SchemaProvenance.moduleGeneratedDirectory(moduleId, pluginId))
                includeEmptyDirs = false
            }
        }
    }
}
