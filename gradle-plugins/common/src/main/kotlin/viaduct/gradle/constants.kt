import org.gradle.api.Project

val viaductBuildDirectory = "viaduct"

val centralSchemaDirectoryName = "$viaductBuildDirectory/centralSchema"

fun Project.centralSchemaDirectory() = layout.buildDirectory.dir(centralSchemaDirectoryName)

val grtClassesDirectoryName = "generated-sources/$viaductBuildDirectory/grtClasses"

fun Project.grtClassesDirectory() = layout.buildDirectory.dir(grtClassesDirectoryName)

val resolverBasesDirectoryName = "generated-sources/$viaductBuildDirectory/resolverBases"

fun Project.resolverBasesDirectory() = layout.buildDirectory.dir(resolverBasesDirectoryName)

val schemaPartitionDirectoryName = "$viaductBuildDirectory/schemaPartition"

fun Project.schemaPartitionDirectory() = layout.buildDirectory.dir(schemaPartitionDirectoryName)
