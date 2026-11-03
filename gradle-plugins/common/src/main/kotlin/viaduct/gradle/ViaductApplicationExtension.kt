package viaduct.gradle

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi

/**
 * The `viaductApplication { ... }` block on an application project.
 *
 * Empty: an application declares its schema scopes in `src/main/viaduct/scopes.yaml`.
 *
 * [objects] is retained because BCV tracks this constructor's signature.
 */
@StableApi
@Suppress("UNUSED_PARAMETER")
open class ViaductApplicationExtension(objects: ObjectFactory) : ViaductApplicationConventions {
    private lateinit var conventions: ViaductApplicationConventions

    @InternalApi
    fun initializeConventions(conventions: ViaductApplicationConventions) {
        check(!this::conventions.isInitialized) { "Application conventions are already initialized" }
        this.conventions = conventions
    }

    override val modules: NamedDomainObjectProvider<Configuration> get() = conventions.modules
    override val allSchemaPartitionsIn: NamedDomainObjectProvider<Configuration> get() = conventions.allSchemaPartitionsIn
    override val centralSchema: NamedDomainObjectProvider<Configuration> get() = conventions.centralSchema
    override val allModuleManifestsIn: NamedDomainObjectProvider<Configuration> get() = conventions.allModuleManifestsIn
    override val modulePackagePrefix: String get() = conventions.modulePackagePrefix
    override val centralSchemaDirectory: Provider<Directory> get() = conventions.centralSchemaDirectory
}
