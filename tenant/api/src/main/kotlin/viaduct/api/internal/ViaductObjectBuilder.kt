package viaduct.api.internal

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import kotlin.reflect.KClass
import viaduct.api.DynamicOutputValueBuilder
import viaduct.api.ViaductTenantUsageException
import viaduct.api.context.ExecutionContext
import viaduct.api.handleTenantAPIErrors
import viaduct.api.types.Object

class ViaductObjectBuilder<T : Object> private constructor(
    val context: InternalContext,
    val graphqlType: GraphQLObjectType,
    private val grtClazz: KClass<T>,
) : DynamicOutputValueBuilder<T> {
    private val underlyingWrapper = EODBuilderWrapper(graphqlType, context.globalIDCodec)

    /** The [value] parameter must be a GRT, i.e., an [Int]
     * for integer-valued fields, a subtype of [viaduct.api.types.Object]
     * for object-valued fields, [viaduct.api.types.Enum] instances for
     * enum-valued fields, etc.
     *
     * For fields that are GraphQL object types, you can pass
     * a ViaductObjectBuilder<S> for its value as long as S
     * matches the type of the field.  This is slightly more
     * efficient `b` is of type ViaductObjectBuilder<S>,
     * passing `b` directly to [put] is slightly more efficient
     * than passing `b.build()`.
     */
    override fun put(
        name: String,
        value: Any?
    ): ViaductObjectBuilder<T> {
        val fieldDefinition = graphqlType.getField(name) ?: throw ViaductTenantUsageException("Field $name not found on type ${graphqlType.name}")
        val fieldContext = DynamicValueBuilderTypeChecker.FieldContext(fieldDefinition, graphqlType)
        DynamicValueBuilderTypeChecker(context).checkType(fieldDefinition.type, value, fieldContext)

        val unwrappedType = GraphQLTypeUtil.unwrapNonNull(fieldDefinition.type)
        handleTenantAPIErrors("ValueObjectBuilder.put failed") {
            if (isViaductObjectBuilderValue(unwrappedType, value)) {
                underlyingWrapper.put(name, (value as ViaductObjectBuilder<*>).underlyingWrapper.getEngineObjectData())
            } else {
                underlyingWrapper.put(name, value)
            }
        }
        return this
    }

    private fun isViaductObjectBuilderValue(
        type: GraphQLType,
        value: Any?
    ): Boolean {
        return value is ViaductObjectBuilder<*> &&
            DynamicValueBuilderTypeChecker(context).isValidObjectType(type, value.graphqlType)
    }

    override fun build(): T = grtClazz.constructors.first().call(context, underlyingWrapper.getEngineObjectData())

    companion object {
        @Suppress("unused")
        fun <T : Object> dynamicBuilderFor(
            ctx: ExecutionContext,
            grtClazz: KClass<T>
        ): ViaductObjectBuilder<T> = dynamicBuilderFor(ctx.internal, grtClazz)

        fun <T : Object> dynamicBuilderFor(
            ctx: InternalContext,
            grtClazz: KClass<T>
        ): ViaductObjectBuilder<T> {
            val graphqlType = ctx.schema.schema.getObjectType(grtClazz.simpleName)
            return ViaductObjectBuilder(ctx, graphqlType, grtClazz)
        }
    }
}
