package viaduct.api.mapping

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.internal.JsonConv
import viaduct.api.internal.internal
import viaduct.api.mapping.JsonDomain.forSelectionSet
import viaduct.api.mapping.JsonDomain.forType
import viaduct.api.mapping.JsonDomain.invoke
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Input
import viaduct.api.types.Object
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.RawSelectionSet
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.Domain
import viaduct.mapping.graphql.IR

/**
 * A mapping [Domain] that converts between json-formatted Strings
 * describing object values and [IR.Value.Object]s
 */
@ExperimentalApi
object JsonDomain {
    private class Impl(
        val internal: InternalContext,
        val selectionSet: RawSelectionSet?,
        val resolveTypeName: ResolveTypeName
    ) : Domain<String> {
        override val conv: Conv<String, IR.Value.Object> = Conv(
            forward = { str ->
                val typeName = resolveTypeName(str)
                val type = objectishType(typeName)
                val conv = JsonConv(internal.schema, type, selectionSet)
                conv(str) as IR.Value.Object
            },
            inverse = { ir ->
                val type = objectishType(ir.name)
                val conv = JsonConv(internal.schema, type, selectionSet)
                conv.invert(ir)
            }
        )

        private fun objectishType(name: String): GraphQLType {
            val type = requireNotNull(internal.schema.schema.getType(name)) {
                "Unknown type: $name"
            }
            require(type is GraphQLObjectType || type is GraphQLInputObjectType) {
                "Expected an input or output object type, but got $type"
            }
            return type
        }
    }

    /** Determine the GraphQL typename that corresponds to a provided Json string */
    private fun interface ResolveTypeName {
        operator fun invoke(jsonString: String): String

        /** A [ResolveTypeName] that always resolves to the provided [typeName] */
        class Const(val typeName: String) : ResolveTypeName {
            constructor(type: Type<*>) : this(type.name)

            override fun invoke(jsonString: String): String = typeName
        }

        /** A [ResolveTypeName] that parses a __typename key out of the input json */
        object Parse : ResolveTypeName {
            val mapper = ObjectMapper()

            override fun invoke(jsonString: String): String {
                val tree = mapper.readTree(jsonString)
                val typeNameNode = requireNotNull(tree["__typename"]) {
                    "Cannot resolve typename for object when neither a type is programmatically provided " +
                        "nor a __typename field is present in the input json"
                }
                return typeNameNode.asText()
            }
        }
    }

    /**
     * Create a Domain that translates json-encoded strings into the IR representation of [type].
     * The returned Domain will expect all input values to be JSON-encoded representations
     * of [type].
     *
     * This can be used when the caller handles JSON with an expected type, and that type
     * is not encoded in the JSON in the form of a "__typename" key.
     *
     * For use-cases where the decoded type is not always known, but the input json includes
     * a __typename key, see [invoke].
     *
     * @see invoke
     */
    @JvmName("forInputType")
    fun <T : Input> forType(
        ctx: ExecutionContext,
        type: Type<T>
    ): Domain<String> = Impl(ctx.internal, null, ResolveTypeName.Const(type))

    /**
     * Create a Domain that translates json-encoded strings into the IR representation of [type].
     * The returned Domain will expect all input values to be JSON-encoded representations
     * of [type].
     *
     * This can be used when the caller handles JSON with an expected type, and that type
     * is not encoded in the JSON in the form of a "__typename" key.
     *
     * For use-cases where the decoded type is not always known, but the input json includes
     * a __typename key, see [invoke].
     *
     * @see invoke
     */
    @JvmName("forObjectType")
    fun <T : Object> forType(
        ctx: ExecutionContext,
        type: Type<T>
    ): Domain<String> = Impl(ctx.internal, null, ResolveTypeName.Const(type))

    /**
     * Create a [Domain] that maps json-encoded strings corresponding to
     * the given [selectionSet]
     */
    fun <T : CompositeOutput> forSelectionSet(
        ctx: ExecutionContext,
        selectionSet: SelectionSet<T>
    ): Domain<String> {
        selectionSet as InternalSelectionSet
        val type = selectionSet.rawSelectionSet.type
        return Impl(
            ctx.internal,
            selectionSet.rawSelectionSet,
            ResolveTypeName.Const(type)
        )
    }

    /**
     * Create a Domain that translates json-encoded strings of unknown types into
     * [IR.Value.Object] representations.
     *
     * The returned Domain will expect all input values to include a "__typename" key,
     * with the name of a GraphQL input or output object type. Any input that does not
     * include a "__typename" will cause a runtime exception to be thrown.
     *
     * For use-cases where a decoded type is always known, see [forType].
     * For use-cases where values may be aliased, see [forSelectionSet].
     *
     * @see forType
     * @see forselectionSet
     */
    operator fun invoke(ctx: ExecutionContext): Domain<String> = Impl(ctx.internal, null, ResolveTypeName.Parse)
}
