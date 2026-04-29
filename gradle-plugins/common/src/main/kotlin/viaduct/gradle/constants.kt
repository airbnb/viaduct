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

val javaGrtSourcesDirectoryName = "generated-sources/$viaductBuildDirectory/javaGrtSources"

fun Project.javaGrtSourcesDirectory() = layout.buildDirectory.dir(javaGrtSourcesDirectoryName)

val javaGrtClassesDirectoryName = "classes/$viaductBuildDirectory/javaGrts"

fun Project.javaGrtClassesDirectory() = layout.buildDirectory.dir(javaGrtClassesDirectoryName)

val javaResolverBasesDirectoryName = "generated-sources/$viaductBuildDirectory/javaResolverBases"

fun Project.javaResolverBasesDirectory() = layout.buildDirectory.dir(javaResolverBasesDirectoryName)
