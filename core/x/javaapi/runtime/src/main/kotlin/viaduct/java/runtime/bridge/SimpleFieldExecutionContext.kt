package viaduct.java.runtime.bridge

import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.Query

// Internal marker types for the context implementation
object AnyQuery : Query

object AnySelections : CompositeOutput

/**
 * Minimal implementation of FieldExecutionContext for Java resolvers.
 *
 * Bridges the engine's untyped data (argument maps) to the Java API's typed interfaces.
 * Arguments are populated from the engine's argument map using reflection on the Arguments class.
 *
 * Uses [Arguments] directly as the generic argument type so that any concrete Arguments
 * subtype (e.g., Query_person_Arguments) can be returned without a ClassCastException.
 * Callers access getArguments() through the erased interface and cast to their specific type.
 *
 * @param requestContext The request context from the engine
 * @param arguments The typed Arguments instance (populated from the engine's argument map), or null
 * @param objectValue The parent object value (e.g., a Person instance for a Person.fullAddress resolver), or null
 */
@Suppress("UNCHECKED_CAST", "TooManyFunctions")
class SimpleFieldExecutionContext(
    private val requestContext: Any?,
    private val arguments: Arguments? = null,
    private val objectValue: Any? = null,
) : FieldExecutionContext<GraphQLObject, AnyQuery, Arguments, AnySelections>,
    FieldResolverBase.Context<GraphQLObject, AnyQuery, Arguments, AnySelections> {
    override fun getObjectValue(): GraphQLObject {
        if (objectValue != null) {
            return objectValue as GraphQLObject
        }
        throw UnsupportedOperationException(
            "Object value not available. Ensure the resolver declares an objectValueFragment."
        )
    }

    override fun getQueryValue(): AnyQuery {
        throw UnsupportedOperationException(
            "Query value access not yet implemented for Java resolvers"
        )
    }

    override fun getArguments(): Arguments {
        return arguments ?: Arguments.NoArguments
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
