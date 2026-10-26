package viaduct.gradle

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import viaduct.apiannotations.StableApi

@StableApi
object ViaductArtifacts {
    val KIND: Attribute<String> = Attribute.of("viaduct.kind", String::class.java)
    const val USAGE = "viaduct-contribution"
    const val SCHEMA_PARTITION = "schema-partition"
    const val CENTRAL_SCHEMA = "central-schema"
    const val MODULE_MANIFEST = "module-manifest"
}

@StableApi
interface ViaductModuleConventions {
    val application: NamedDomainObjectProvider<Configuration>
    val centralSchemaIn: NamedDomainObjectProvider<Configuration>
    val schemaPartition: NamedDomainObjectProvider<Configuration>
    val moduleManifests: NamedDomainObjectProvider<Configuration>
    val layout: ViaductModulePackageLayout
}

@StableApi
interface ViaductApplicationConventions {
    val modules: NamedDomainObjectProvider<Configuration>
    val allSchemaPartitionsIn: NamedDomainObjectProvider<Configuration>
    val centralSchema: NamedDomainObjectProvider<Configuration>
    val allModuleManifestsIn: NamedDomainObjectProvider<Configuration>
    val modulePackagePrefix: String
    val centralSchemaDirectory: Provider<Directory>
}
