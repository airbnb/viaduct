package viaduct.java.runtime.bridge

import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.resolvers.FieldResolverBase

// Internal marker types for the context implementation
object AnyQuery : viaduct.java.api.types.Query

object AnyArguments : viaduct.java.api.types.Arguments

object AnySelections : viaduct.java.api.types.CompositeOutput

/**
 * Minimal implementation of FieldExecutionContext for simple resolvers.
 *
 * This provides just enough functionality for basic field resolvers that don't use
 * required selection sets. For now, it throws UnsupportedOperationException for
 * methods that require full context support.
 */
@Suppress("UNCHECKED_CAST", "TooManyFunctions")
class SimpleFieldExecutionContext(
    private val requestContext: Any?
) : FieldExecutionContext<AnyQuery, AnyQuery, AnyArguments, AnySelections>,
    FieldResolverBase.Context<AnyQuery, AnyQuery, AnyArguments, AnySelections> {
    override fun getObjectValue(): AnyQuery {
        throw UnsupportedOperationException(
            "Object value access not yet implemented for Java resolvers"
        )
    }

    override fun getQueryValue(): AnyQuery {
        throw UnsupportedOperationException(
            "Query value access not yet implemented for Java resolvers"
        )
    }

    override fun getArguments(): AnyArguments {
        throw UnsupportedOperationException(
            "Arguments access not yet implemented for Java resolvers"
        )
    }

    override fun getSelections(): Any {
        throw UnsupportedOperationException(
            "Selections access not yet implemented for Java resolvers"
        )
    }

    override fun getRequestContext(): Any? = requestContext

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> globalIDFor(
        type: viaduct.java.api.reflect.Type<T>,
        internalID: String
    ): viaduct.java.api.globalid.GlobalID<T> {
        throw UnsupportedOperationException(
            "globalIDFor not yet implemented for Java resolvers"
        )
    }

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> serialize(globalID: viaduct.java.api.globalid.GlobalID<T>): String {
        throw UnsupportedOperationException(
            "serialize not yet implemented for Java resolvers"
        )
    }

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> nodeFor(id: viaduct.java.api.globalid.GlobalID<T>): T {
        throw UnsupportedOperationException(
            "nodeFor not yet implemented for Java resolvers"
        )
    }
}
