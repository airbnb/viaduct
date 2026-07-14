package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi
import viaduct.service.api.scoping.SchemaScoping
import viaduct.service.api.scoping.ScopingErrorCodes

@StableApi
@OptIn(ExperimentalApi::class, InternalApi::class)
open class ViaductApplicationExtension(objects: ObjectFactory) {
    private val schemaScopingProperty =
        objects.property(SchemaScoping::class.java).convention(SchemaScoping.EMPTY)

    private var scopingDeclared = false

    /**
     * The validated [SchemaScoping] snapshot produced by the `declareScoping { ... }` block, or
     * [SchemaScoping.EMPTY] if `declareScoping` was never called. Read by `:application` for
     * downstream wiring (e.g. as a typed task input). Marked [InternalApi] so BCV omits it from
     * the public-surface listing while keeping the symbol visible across `:common` → `:application`.
     */
    @InternalApi
    val schemaScoping: Provider<SchemaScoping> = schemaScopingProperty

    /**
     * Declares scope universe and scoped schemas for this application. May be called at most once;
     * omit it entirely to express "no scoping". Per-ID syntax, duplicate IDs, reserved IDs, and the
     * cross-property subset check all run immediately inside / at the end of the block; any failure
     * throws a [GradleException] at the offending DSL line.
     */
    @ExperimentalApi
    fun declareScoping(configure: SchemaScopingBuilder.() -> Unit) {
        if (scopingDeclared) {
            throw GradleException(
                "[${ScopingErrorCodes.SCHEMA_SCOPING_DECLARED_TWICE}] " +
                    "declareScoping may only be called once. " +
                    "Compose convention-plugin contributions into a single block.",
            )
        }
        scopingDeclared = true
        schemaScopingProperty.set(SchemaScopingBuilder().apply(configure).build())
    }
}
