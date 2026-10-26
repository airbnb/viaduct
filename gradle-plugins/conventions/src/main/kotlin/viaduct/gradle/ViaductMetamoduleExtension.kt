package viaduct.gradle

import org.gradle.api.artifacts.Configuration
import viaduct.apiannotations.StableApi

@StableApi
interface ViaductMetamoduleExtension {
    val layout: ViaductModulePackageLayout
    val applicationProjectPath: String
    val centralSchemaConfiguration: Configuration
}

internal data class DefaultViaductMetamoduleExtension(
    override val layout: ViaductModulePackageLayout,
    override val applicationProjectPath: String,
    override val centralSchemaConfiguration: Configuration,
) : ViaductMetamoduleExtension
