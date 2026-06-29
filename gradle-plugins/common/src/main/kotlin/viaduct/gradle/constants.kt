import org.gradle.api.Project
import viaduct.apiannotations.InternalApi

@InternalApi
val viaductBuildDirectory = "viaduct"

@InternalApi
val centralSchemaDirectoryName = "$viaductBuildDirectory/centralSchema"

@InternalApi
fun Project.centralSchemaDirectory() = layout.buildDirectory.dir(centralSchemaDirectoryName)

@InternalApi
val grtClassesDirectoryName = "generated-sources/$viaductBuildDirectory/grtClasses"

@InternalApi
fun Project.grtClassesDirectory() = layout.buildDirectory.dir(grtClassesDirectoryName)

@InternalApi
val resolverBasesDirectoryName = "generated-sources/$viaductBuildDirectory/resolverBases"

@InternalApi
fun Project.resolverBasesDirectory() = layout.buildDirectory.dir(resolverBasesDirectoryName)

@InternalApi
val schemaPartitionDirectoryName = "$viaductBuildDirectory/schemaPartition"

@InternalApi
fun Project.schemaPartitionDirectory() = layout.buildDirectory.dir(schemaPartitionDirectoryName)

@InternalApi
val javaGrtSourcesDirectoryName = "generated-sources/$viaductBuildDirectory/javaGrtSources"

@InternalApi
fun Project.javaGrtSourcesDirectory() = layout.buildDirectory.dir(javaGrtSourcesDirectoryName)

@InternalApi
val javaGrtClassesDirectoryName = "classes/$viaductBuildDirectory/javaGrts"

@InternalApi
fun Project.javaGrtClassesDirectory() = layout.buildDirectory.dir(javaGrtClassesDirectoryName)

@InternalApi
val javaResolverBasesDirectoryName = "generated-sources/$viaductBuildDirectory/javaResolverBases"

@InternalApi
fun Project.javaResolverBasesDirectory() = layout.buildDirectory.dir(javaResolverBasesDirectoryName)
