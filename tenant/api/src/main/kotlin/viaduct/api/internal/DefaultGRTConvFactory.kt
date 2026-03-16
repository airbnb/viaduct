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
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Enum
import viaduct.api.types.Input
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.Object
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.gj
import viaduct.graphql.isGlobalID
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.ConvMemo
import viaduct.mapping.graphql.IR

/**
 * Factory methods for [Conv]s that map between [viaduct.api.types.GRT] and
 * [IR] representations.
 *
 * @see create
 */
@InternalApi
object DefaultGRTConvFactory : GRTConvFactory {
    /**
     * Create a [Conv] for [viaduct.api.types.GRT] values backed by [type].
     *
     * Some GRT conversions require more context than just a [type] --
     * prefer using [createForInputField] or [createForOutputField] that operate on fields.
     *
     * @param type the GraphQL type for which a [Conv] will be created.
     * @param selectionSet A [EngineSelectionSet] possible projection of [type].
     *   [selectionSet] may not be null if any type in [keyMapping] is [KeyMapping.KeyType.Selection].
     *   The returned [Conv] will only be able to map the selections in [selectionSet].
     *   Any aliases used in [selectionSet] will be used as object keys in both the JSON and IR Values
     * @param keyMapping A [KeyMapping] that describes how object keys should be mapped.
     *   If null, a default [KeyMapping] will be chosen based on the value of [selectionSet]
     */
    override fun create(
        internalCtx: InternalContext,
        type: GraphQLType,
        selectionSet: EngineSelectionSet?,
        keyMapping: KeyMapping?
    ): Conv<Any?, IR.Value> =
        Builder(internalCtx, keyMapping ?: KeyMapping.defaultKeyMapping(selectionSet))
            .build(
                type = type,
                parentContext = None,
                selectionSet = selectionSet
            )

    /**
     * Create a [Conv] that maps between [viaduct.api.types.GRT] and [IR] representations
     * for the provided input [field].
     */
    override fun createForInputField(
        internalCtx: InternalContext,
        field: GraphQLInputObjectField
    ): Conv<Any?, IR.Value> =
        Builder(internalCtx, KeyMapping.FieldNameToFieldName)
            .build(
                type = field.type,
                parentContext = InputParentContext(field),
                selectionSet = null
            )

    /**
     * Create a [Conv] that maps between [viaduct.api.types.GRT] and [IR] representations
     * for the provided output [field].
     */
    fun createForOutputField(
        internalCtx: InternalContext,
        field: GraphQLFieldDefinition,
        parentType: GraphQLCompositeType,
        selectionSet: EngineSelectionSet?,
        keyMapping: KeyMapping? = null
    ): Conv<Any?, IR.Value> =
        Builder(internalCtx, keyMapping ?: KeyMapping.defaultKeyMapping(selectionSet))
            .build(
                type = field.type,
                parentContext = OutputParentContext(parentType, field),
                selectionSet = selectionSet
            )

    internal fun abstractGRT(impls: Map<String, Conv<Object, IR.Value.Object>>): Conv<CompositeOutput, IR.Value.Object> =
        Conv(
            forward = {
                it as ObjectBase
                val impl = requireNotNull(impls[it.engineObject.type.name])
                impl(it)
            },
            inverse = {
                val impl = requireNotNull(impls[it.name])
                impl.invert(it)
            },
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
        )

    private fun globalIDGRT(ctx: InternalContext): Conv<GlobalID<*>, IR.Value.String> =
        Conv(
            forward = { IR.Value.String(ctx.globalIDCodec.serialize(it.type.name, it.internalID)) },
            inverse = { ctx.deserializeGlobalID<NodeCompositeOutput>(it.value) }
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
    private data class OutputParentContext(
        val parentType: GraphQLCompositeType,
        val parentField: GraphQLFieldDefinition,
    ) : ParentContext

    /**
     * A [ParentContext] indicating that the current context was derived from an input object field
     *
     * @param parentField the nearest ancestor input object field from which the current context is derived
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

    private class Builder(
        val internalContext: InternalContext,
        val keyMapping: KeyMapping
    ) {
        // Separate memos per namespace avoid string concatenation for memo keys.
        // Each namespace uses just type.name as the key (already interned, cached hashCode).
        // Cycles only occur within a namespace, so separate cycle detection is correct.
        private val grtMemo = ConvMemo()
        private val engineValueMemo = ConvMemo()

        private data class Ctx(
            val type: GraphQLType,
            val isNullable: Boolean = true,
            val parentContext: ParentContext = None,
            val selectionSet: EngineSelectionSet?,
            val wrapGRTs: Boolean = true
        ) {
            fun push(
                type: GraphQLType,
                isNullable: Boolean = true
            ): Ctx = copy(type = type, isNullable = isNullable, wrapGRTs = this.wrapGRTs)
        }

        fun build(
            type: GraphQLType,
            parentContext: ParentContext,
            selectionSet: EngineSelectionSet?,
        ): Conv<Any?, IR.Value> =
            mk(Ctx(type, parentContext = parentContext, selectionSet = selectionSet)).also {
                grtMemo.resolveRefs()
                engineValueMemo.resolveRefs()
            }

        private fun mk(ctx: Ctx): Conv<Any?, IR.Value> {
            // PERFORMANCE NOTES
            // This code is performance sensitive and needs to be fast. Any changes to this code should
            // use ConvBuildBenchmark to validate that there are no perf regressions.
            //
            // A couple of important notes:
            // - though this Builder will delegate to EngineValueConv for most types, the Builders in these systems use
            //   independent ConvMemo instances. Not using ConvMemo in this class, expecting that memoization will be handled in
            //   EngineValueConv, can cause duplicate traversals over large parts of the schema. Use ConvMemo on every named type!
            //
            // - ParentContext is used to generate correct convs for GlobalID types. It's only needed when we are traversing in
            //   a mode where wrapGRTs is true. An important property in this system is that wrapGRTs will start as true, but any
            //   changes to false will be permanent for an entire subtree. We can use this property to drop ParentContext in places
            //   where wrapGRTs is false, saving on allocations.
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
                    val inputDataConv = engineValueMemo.memoizeIf(ctx.type.name, ctx.selectionSet == null) {
                        val fieldConvs = ctx.type.fields.associate { f ->
                            // values for inner objects should not be wrapped as GRTs
                            val fieldCtx = Ctx(
                                f.type,
                                // performance optimization: parentContext is only needed when converting GlobalID's when wrapGRTs is true
                                // since we're in a context where wrapGRTs is false, we can elide a parentContext to save on allocations
                                parentContext = None,
                                wrapGRTs = false,
                                selectionSet = null
                            )
                            f.name to mk(fieldCtx)
                        }
                        EngineValueConv.obj(ctx.type.name, fieldConvs)
                    }

                    // When wrapGRTs is true, the inputData map is wrapped in an additional GRT layer
                    if (ctx.wrapGRTs) {
                        grtMemo.buildIfAbsent(ctx.type.name) {
                            inputGRT(
                                internalContext,
                                internalContext.reflectionLoader.reflectionFor(ctx.type.name) as Type<Input>,
                                ctx.type,
                                inputDataConv
                            )
                        }
                    } else {
                        inputDataConv
                    }
                }

                ctx.type is GraphQLObjectType -> {
                    // At minimum, all objects require extracting values from an EngineObjectData
                    val engineDataConv = engineValueMemo.memoizeIf(
                        ctx.type.name,
                        keyMapping == KeyMapping.FieldNameToFieldName
                    ) {
                        val convs = if (keyMapping == KeyMapping.FieldNameToFieldName) {
                            // when neither side of a KeyMapping is selection-based, then generate a conv for each schema field
                            ctx.type.mappableFields.associate { f ->
                                // When KeyMapping is FieldnameToFieldname, we're mapping purely based on types. Since
                                // GraphQL objects may be recursive, this traversal through types may cause an infinite loop.
                                // We use ConvMemo to memoize the Conv for this type, allowing it to be reused if we encounter
                                // this type elsewhere in the traversal.
                                val fieldCtx = Ctx(
                                    f.type,
                                    // performance optimization: parentContext is only needed when converting GlobalID's when wrapGRTs is true
                                    // since we're in a context where wrapGRTs is false, we can elide a parentContext to save on allocations
                                    parentContext = None,
                                    // values for inner objects should not be wrapped as GRTs
                                    // Set wrapGRTs to false for the convs in this types subtree
                                    wrapGRTs = false,
                                    selectionSet = null
                                )
                                val fieldConv = mk(fieldCtx)
                                f.name to fieldConv
                            }
                        } else {
                            // Getting into this else block means that at least one side of the KeyMapping is selection-based.
                            // Generate a conv for each selection
                            requireNotNull(ctx.selectionSet)
                                .selections()
                                .associate { sel ->
                                    val coord = (sel.typeCondition to sel.fieldName).gj
                                    val fieldDef = internalContext.schema.schema.getFieldDefinition(coord)

                                    val subselections = ctx.selectionSet
                                        .takeIf { fieldDef.type.supportsSelections }
                                        ?.selectionSetForSelection(sel.typeCondition, sel.selectionName)

                                    // values for inner objects should not be wrapped as GRTs
                                    // Set wrapGRTs to false for the convs in this types subtree
                                    val fieldCtx = Ctx(
                                        fieldDef.type,
                                        parentContext = OutputParentContext(
                                            parentType = ctx.type,
                                            parentField = fieldDef,
                                        ),
                                        wrapGRTs = false,
                                        selectionSet = subselections
                                    )
                                    sel.selectionName to mk(fieldCtx)
                                }
                        }

                        EngineValueConv.engineObjectData(
                            ctx.type,
                            EngineValueConv.obj(
                                ctx.type.name,
                                convs,
                                KeyMapping.map(keyMapping, ctx.selectionSet)
                            )
                        )
                    }

                    // When wrapGRTs is true (ie we are generating for an outer type in a type tree), then objects
                    // are represented with an additional layer of GRT wrapping, which need to be Conv'd
                    if (ctx.wrapGRTs) {
                        // when wrapGRTs is true, wrap engineDataConv in an additional conv that will wrap/unwrap GRTs
                        grtMemo.buildIfAbsent(ctx.type.name) {
                            objectGRT(
                                internalContext,
                                internalContext.reflectionLoader.reflectionFor(ctx.type.name) as Type<Object>,
                                engineDataConv
                            )
                        }
                    } else {
                        engineDataConv
                    }
                }

                ctx.type is GraphQLCompositeType -> {
                    if (ctx.wrapGRTs) {
                        grtMemo.memoizeIf(ctx.type.name, ctx.selectionSet == null) {
                            val impls = internalContext.schema.rels.possibleObjectTypes(ctx.type)
                                .toList()
                                .associate { obj ->
                                    val innerCtx = ctx.copy(type = obj)
                                    val conv = mk(innerCtx) as Conv<Object, IR.Value.Object>
                                    obj.name to conv
                                }
                            abstractGRT(impls)
                        }.asAnyConv
                    } else {
                        engineValueMemo.memoizeIf(ctx.type.name, ctx.selectionSet == null) {
                            val concretes = internalContext.schema.rels.possibleObjectTypes(ctx.type)
                                .associate { obj ->
                                    val innerCtx = ctx.copy(type = obj)
                                    @Suppress("UNCHECKED_CAST")
                                    val concrete = mk(innerCtx) as Conv<EngineObjectData.Sync, IR.Value.Object>
                                    obj.name to concrete
                                }
                            EngineValueConv.abstract(concretes)
                        }.asAnyConv
                    }
                }

                ctx.type is GraphQLEnumType -> {
                    if (ctx.wrapGRTs) {
                        grtMemo.memoizeIf(ctx.type.name, ctx.selectionSet == null) {
                            val reflectedType = internalContext.reflectionLoader.reflectionFor(ctx.type.name)
                            enumGRT(reflectedType as Type<Enum>)
                        }
                    } else {
                        engineValueMemo.buildIfAbsent(ctx.type.name) {
                            EngineValueConv.enum
                        }
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
                        grtMemo.buildIfAbsent("ID") {
                            globalIDGRT(internalContext)
                        }
                    }

                    /** handling for output GlobalID wrapping */
                    ctx.parentContext is OutputParentContext &&
                        ctx.parentContext.parentType is GraphQLObjectType &&
                        isGlobalID(ctx.parentContext.parentField, ctx.parentContext.parentType) &&
                        ctx.wrapGRTs -> {
                        grtMemo.buildIfAbsent("ID") {
                            globalIDGRT(internalContext)
                        }
                    }

                    /**
                     * We didn't match any of the GlobalID wrapping cases.
                     * Default to using the engine representation for this type.
                     */
                    else ->
                        engineValueMemo.buildIfAbsent(ctx.type.name) {
                            EngineValueConv(internalContext.schema, ctx.type, ctx.selectionSet)
                        }
                }

                else -> throw IllegalArgumentException(
                    "Cannot create a Conv for unsupported GraphQLType ${ctx.type}"
                )
            }
            return if (ctx.isNullable) {
                EngineValueConv.nullable(conv.asAnyConv)
            } else {
                conv.asAnyConv
            }
        }
    }
}
