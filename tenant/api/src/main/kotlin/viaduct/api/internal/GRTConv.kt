@file:Suppress("UNCHECKED_CAST")

package viaduct.api.internal

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import kotlin.Enum as KotlinEnum
import viaduct.api.globalid.GlobalID
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Enum
import viaduct.api.types.Input
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.Object
import viaduct.engine.api.EngineObjectData
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.ConvMemo
import viaduct.mapping.graphql.IR

/**
 * Factory methods for [Conv]s that map between [viaduct.api.types.GRT] and
 * [IR] representations.
 *
 * @see invoke
 */
object GRTConv {
    /**
     * Create a [Conv] for [viaduct.api.types.GRT] values backed by [graphQLType].
     *
     * Some GRT conversions require more context than just a [graphQLType] --
     * prefer using the overloads of [invoke] that operate on fields.
     */
    operator fun invoke(
        internalCtx: InternalContext,
        graphQLType: GraphQLType
    ): Conv<Any?, IR.Value> {
        val builder = Builder(internalCtx)
        return builder.build(graphQLType, None)
    }

    /**
     * Create a [Conv] that maps between [viaduct.api.types.GRT] and [IR] representations
     * for the provided input [field].
     */
    operator fun invoke(
        internalCtx: InternalContext,
        field: GraphQLInputObjectField
    ): Conv<Any?, IR.Value> {
        val builder = Builder(internalCtx)
        return builder.build(field.type, InputParentContext(field))
    }

    /**
     * Create a [Conv] that maps between [viaduct.api.types.GRT] and [IR] representations
     * for the provided output [field].
     */
    operator fun invoke(
        internalCtx: InternalContext,
        field: GraphQLFieldDefinition,
        parentType: GraphQLCompositeType
    ): Conv<Any?, IR.Value> {
        val builder = Builder(internalCtx)
        return builder.build(field.type, OutputParentContext(parentType, field))
    }

    internal fun abstractGRT(
        typeName: String,
        impls: Map<String, Conv<Object, IR.Value.Object>>
    ): Conv<CompositeOutput, IR.Value.Object> =
        Conv(
            forward = {
                it as ObjectBase
                val impl = requireNotNull(impls[it.engineObject.graphQLObjectType.name])
                impl(it)
            },
            inverse = {
                val impl = requireNotNull(impls[it.name])
                impl.invert(it)
            },
            "abstractGRT-$typeName"
        )

    internal fun <OutputGRT : Object> objectGRT(
        ctx: InternalContext,
        type: Type<OutputGRT>,
        engineObjectDataConv: Conv<EngineObjectData.Sync, IR.Value.Object>
    ): Conv<OutputGRT, IR.Value.Object> =
        Conv(
            forward = {
                it as ObjectBase
                val engineData = requireNotNull(it.engineObject as? EngineObjectData.Sync) {
                    "Expecting engineObject data as EngineObjectData.Sync, but got ${it.engineObject.javaClass}"
                }
                engineObjectDataConv(engineData)
            },
            inverse = {
                val eod = engineObjectDataConv.invert(it)
                wrapOutputObject(ctx, type, eod)
            },
            "objectGRT-${type.name}"
        )

    internal fun <InputGRT : Input> inputGRT(
        ctx: InternalContext,
        type: Type<InputGRT>,
        graphQLType: GraphQLInputObjectType,
        mapConv: Conv<Map<String, Any?>, IR.Value.Object>,
    ): Conv<InputGRT, IR.Value.Object> =
        Conv(
            forward = {
                val inputData = (it as InputLikeBase).inputData
                mapConv(inputData)
            },
            inverse = {
                val inputData = mapConv.invert(it)
                wrapInputObject(
                    ctx,
                    type,
                    graphQLType,
                    inputData
                )
            },
            name = "inputGRT-${type.name}"
        )

    internal fun <EnumGRT : Enum> enumGRT(type: Type<EnumGRT>): Conv<EnumGRT, IR.Value.String> =
        Conv(
            forward = {
                it as KotlinEnum<*>
                IR.Value.String(it.name)
            },
            inverse = {
                val javaCls = type.kcls.java as Class<out KotlinEnum<*>>
                java.lang.Enum.valueOf(javaCls, it.value) as EnumGRT
            },
            name = "enumGRT-${type.name}"
        )

    private fun globalIDGRT(codec: GlobalIDCodec): Conv<GlobalID<*>, IR.Value.String> =
        Conv(
            forward = { IR.Value.String(codec.serialize(it)) },
            inverse = { codec.deserialize<NodeCompositeOutput>(it.value) }
        )

    /**
     * Converting GRT values has a number of contextual dependencies.
     * For example, an ID scalar may be either a simple String value in some contexts,
     * or a GlobalID value in others.
     * [ParentContext] is a marker interface that can be used during Conv construction to retain
     * the context needed to generate the correct Conv tree.
     */
    private sealed interface ParentContext

    /**
     * A [ParentContext] indicating that the current context was derived from an object field
     *
     * @param parentField the nearest ancestor object field from which the current context is derived
     * @param parentType the type that [parentField] is defined on
     */
    private data class OutputParentContext(val parentType: GraphQLCompositeType, val parentField: GraphQLFieldDefinition) : ParentContext

    /**
     * A [ParentContext] indicating that the current context was derived from an input object field
     *
     * @param parentField the enarest ancestor input object field from which the current context is derived
     */
    private data class InputParentContext(val parentField: GraphQLInputObjectField) : ParentContext

    /**
     * A [ParentContext] indicating that the current context was not derived from either an
     * input or output field.
     *
     * This is the case when generating a Conv for an unparented type, such as root query type,
     * a scalar, etc.
     */
    private object None : ParentContext

    private class Builder(val internalContext: InternalContext) {
        private val memo = ConvMemo()

        private data class Ctx(
            val type: GraphQLType,
            val isNullable: Boolean = true,
            val parentContext: ParentContext = None,
            val wrapGRTs: Boolean = true
        ) {
            fun push(
                type: GraphQLType,
                isNullable: Boolean = true
            ): Ctx = copy(type = type, isNullable = isNullable, wrapGRTs = this.wrapGRTs)
        }

        fun build(
            type: GraphQLType,
            parentContext: ParentContext
        ): Conv<Any?, IR.Value> =
            mk(Ctx(type, parentContext = parentContext)).also {
                memo.finalize()
            }

        private fun mk(ctx: Ctx): Conv<Any?, IR.Value> {
            val conv = when {
                ctx.type is GraphQLNonNull ->
                    // return early to prevent wrapping in nullable below
                    return EngineValueConv.nonNull(
                        mk(ctx.push(type = ctx.type.wrappedType, isNullable = false))
                    )

                ctx.type is GraphQLList ->
                    EngineValueConv.list(mk(ctx.push(ctx.type.wrappedType)))

                ctx.type is GraphQLInputObjectType -> {
                    // At minimum, all input objects require extracting values from a Map<String, Any?>
                    val inputDataConv = memo.buildIfAbsent("${ctx.type.name}-engineValue") {
                        val fieldConvs = ctx.type.fields.associate { f ->
                            // values for inner objects should not be wrapped as GRTs
                            val fieldCtx = Ctx(f.type, parentContext = InputParentContext(f), wrapGRTs = false)
                            f.name to mk(fieldCtx)
                        }
                        EngineValueConv.obj(ctx.type.name, fieldConvs)
                    }

                    // When wrapGRTs is true, the inputData map is wrapped in an additional GRT layer
                    if (ctx.wrapGRTs) {
                        inputGRT(
                            internalContext,
                            internalContext.reflectionLoader.reflectionFor(ctx.type.name) as Type<Input>,
                            ctx.type,
                            inputDataConv
                        )
                    } else {
                        inputDataConv
                    }
                }

                ctx.type is GraphQLObjectType -> {
                    // At minimum, all objects require extracting values from an EngineObjectData
                    val engineDataConv = memo.buildIfAbsent("${ctx.type.name}-engineData") {
                        val fieldConvs = ctx.type.fields.associate { f ->
                            // values for inner objects should not be wrapped as GRTs
                            // Set wrapGRTs to false for the convs in this types subtree
                            val fieldCtx = Ctx(f.type, parentContext = OutputParentContext(ctx.type, f), wrapGRTs = false)
                            f.name to mk(fieldCtx)
                        }

                        EngineValueConv.engineObjectData(
                            ctx.type,
                            EngineValueConv.obj(ctx.type.name, fieldConvs)
                        )
                    }

                    // When wrapGRTs is true (ie we are generating for an outer type in a type tree), then objects
                    // are represented with an additional layer of GRT wrapping, which need to be Conv'd
                    if (ctx.wrapGRTs) {
                        // when wrapGRTs is true, wrap engineDataConv in an additional conv that will wrap/unwrap GRTs
                        objectGRT(
                            internalContext,
                            internalContext.reflectionLoader.reflectionFor(ctx.type.name) as Type<Object>,
                            engineDataConv
                        )
                    } else {
                        engineDataConv
                    }
                }

                ctx.type is GraphQLCompositeType && ctx.wrapGRTs -> {
                    memo.buildIfAbsent("${ctx.type.name}-grt") {
                        val impls = internalContext.schema.rels.possibleObjectTypes(ctx.type)
                            .toList()
                            .associate { obj ->
                                val innerCtx = ctx.copy(type = obj)
                                val conv = mk(innerCtx) as Conv<Object, IR.Value.Object>
                                obj.name to conv
                            }
                        abstractGRT(ctx.type.name, impls)
                    }.asAnyConv
                }

                ctx.type is GraphQLEnumType && ctx.wrapGRTs -> {
                    memo.buildIfAbsent("${ctx.type.name}-grt") {
                        val reflectedType = internalContext.reflectionLoader.reflectionFor(ctx.type.name)
                        enumGRT(reflectedType as Type<Enum>)
                    }
                }

                ctx.type is GraphQLScalarType -> when {
                    /**
                     * GlobalID values are GRTs similar to object or input GRTs:
                     * They are a typed tenant-facing representation that represents
                     * untyped engine data.
                     *
                     * Generate a Conv for GlobalIDs where required
                     */

                    /** handling for input GlobalID wrapping */
                    ctx.parentContext is InputParentContext &&
                        isGlobalID(ctx.parentContext.parentField) &&
                        ctx.wrapGRTs -> {
                        globalIDGRT(internalContext.globalIDCodec)
                    }

                    /** handling for output GlobalID wrapping */
                    ctx.parentContext is OutputParentContext &&
                        ctx.parentContext.parentType is GraphQLObjectType &&
                        isGlobalID(ctx.parentContext.parentField, ctx.parentContext.parentType) &&
                        ctx.wrapGRTs -> {
                        globalIDGRT(internalContext.globalIDCodec)
                    }

                    /**
                     * We didn't match any of the GlobalID wrapping cases.
                     * Default to using the engine representation for this type.
                     */
                    else -> EngineValueConv(internalContext.schema, ctx.type)
                }

                /**
                 * We didn't match any of the tenant-facing wrapper cases.
                 * Default to using the engine representation for this type.
                 */
                else -> EngineValueConv(internalContext.schema, ctx.type).asAnyConv
            }
            return if (ctx.isNullable) {
                EngineValueConv.nullable(conv.asAnyConv)
            } else {
                conv.asAnyConv
            }
        }
    }
}
