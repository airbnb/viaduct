import org.gradle.api.Project
import viaduct.apiannotations.InternalApi

@InternalApi
val viaductBuildDirectory = "viaduct"

@InternalApi
val centralSchemaDirectoryName = "$viaductBuildDirectory/centralSchema"

@InternalApi
fun Project.centralSchemaDirectory() = layout.buildDirectory.dir(centralSchemaDirectoryName)

@InternalApi
fun Project.schemaScopingManifestDirectory() = layout.buildDirectory.dir("$viaductBuildDirectory/schemaScoping")

@InternalApi
val grtClassesDirectoryName = "classes/viaduct/grt/kotlin/main"

@InternalApi
fun Project.grtClassesDirectory() = layout.buildDirectory.dir(grtClassesDirectoryName)

@InternalApi
val resolverBasesDirectoryName = "generated/sources/viaduct/resolver-bases/kotlin/main"

@InternalApi
fun Project.resolverBasesDirectory() = layout.buildDirectory.dir(resolverBasesDirectoryName)

@InternalApi
val schemaPartitionDirectoryName = "$viaductBuildDirectory/schemaPartition"

@InternalApi
fun Project.schemaPartitionDirectory() = layout.buildDirectory.dir(schemaPartitionDirectoryName)

@InternalApi
val javaGrtSourcesDirectoryName = "generated/sources/viaduct/grt/java/main"

@InternalApi
fun Project.javaGrtSourcesDirectory() = layout.buildDirectory.dir(javaGrtSourcesDirectoryName)

@InternalApi
val javaGrtClassesDirectoryName = "classes/viaduct/grt/java/main"

@InternalApi
fun Project.javaGrtClassesDirectory() = layout.buildDirectory.dir(javaGrtClassesDirectoryName)

@InternalApi
val javaResolverBasesDirectoryName = "generated/sources/viaduct/resolver-bases/java/main"

@InternalApi
fun Project.javaResolverBasesDirectory() = layout.buildDirectory.dir(javaResolverBasesDirectoryName)
