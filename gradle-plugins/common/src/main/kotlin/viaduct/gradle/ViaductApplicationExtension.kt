package viaduct.gradle

import org.gradle.api.model.ObjectFactory
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
open class ViaductApplicationExtension(objects: ObjectFactory)
