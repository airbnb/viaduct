package viaduct.arbitrary.graphql

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.string
import viaduct.api.internal.EngineValueConv
import viaduct.arbitrary.common.Config
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Generate arbitrary values suitable for a Viaduct resolver at the provided coordinate.
 * The returned values will be bounded by the resolvers output selection set.
 *
 * @param schema the schema that the resolver inhabits
 * @param coord the Coordinate of the resolver to generate a value for
 * @param selections the selection set that the resolver was called with. This is required to be non-null
 * for resolvers of composite types
 * @param ctx an EngineCtx that the Resolver was called with (see [EngineCtx.Companion.invoke])
 * @param selective a flag determining if the resolver is selected. If true, the resolver will
 * return only values for selections in [selections]
 * @param cfg a [Config] to control generation
 */
fun Arb.Companion.fieldResolverValue(
    schema: ViaductSchema,
    coord: Coordinate,
    selections: EngineSelectionSet?,
    ctx: EngineCtx,
    selective: Boolean = false,
    cfg: Config = Config.default
): Arb<Any?> =
    arbitrary { rs ->
        val gen = ResolverValueGen(
            schema,
            ResolverConfig(schema, cfg, rs),
            cfg,
            CoordinateIndex(schema, rs),
            rs
        )
        gen.gen(coord = coord, selective = selective, selections = selections, ctx = ctx)
    }

fun interface FieldResolverValueGen {
    fun gen(
        coord: Coordinate,
        selective: Boolean,
        selections: EngineSelectionSet?,
        ctx: EngineCtx
    ): Any?

    companion object {
        internal operator fun invoke(env: ViaductGenEnv): FieldResolverValueGen = ResolverValueGen(env)
    }
}

/**
 * Generate arbitrary values suitable for a Viaduct Node resolver for the provided type.
 * The returned values will be bounded by the resolvers output selection set.
 *
 * @param schema the schema that the resolver inhabits
 * @param type the type name of the Node to generate a value for
 * @param selections the selection set that the resolver was called with
 * @param ctx an EngineCtx that the Resolver was called with (see [EngineCtx.Companion.invoke])
 * @param selective a flag determining if the resolver is selected. If true, the resolver will
 * return only values for selections in [selections]
 * @param cfg a [Config] to control generation
 */
fun Arb.Companion.nodeResolverValue(
    schema: ViaductSchema,
    type: String,
    selections: EngineSelectionSet,
    ctx: EngineCtx,
    selective: Boolean = false,
    cfg: Config = Config.default
): Arb<Any?> =
    arbitrary { rs ->
        val gen = ResolverValueGen(
            schema,
            ResolverConfig(schema, cfg, rs),
            cfg,
            CoordinateIndex(schema, rs),
            rs
        )
        gen.gen(type = type, selective = selective, selections = selections, ctx = ctx)
    }

interface NodeResolverValueGen {
    fun gen(
        type: String,
        selective: Boolean,
        selections: EngineSelectionSet,
        ctx: EngineCtx
    ): EngineObjectData

    companion object {
        internal operator fun invoke(env: ViaductGenEnv): NodeResolverValueGen = ResolverValueGen(env)
    }
}

internal class ResolverValueGen(
    private val schema: ViaductSchema,
    private val resolverConfig: ResolverConfig,
    private val cfg: Config,
    private val coordinateIndex: CoordinateIndex,
    private val rs: RandomSource
) : FieldResolverValueGen, NodeResolverValueGen {
    private val enumGen = EnumValueGen(rs)
    private val scalarGen = ScalarValueGen(schema, cfg, rs)

    constructor(env: ViaductGenEnv) :
        this(
            env.schemas.viaductSchema,
            env.resolverConfig,
            env.cfg,
            env.coordinateIndex,
            env.rs
        )

    override fun gen(
        coord: Coordinate,
        selective: Boolean,
        selections: EngineSelectionSet?,
        ctx: EngineCtx
    ): Any? {
        val field = schema.schema.getFieldDefinition(coord.gj)
        val parent = schema.schema.getType(coord.first)
        val typeCtx = TypeCtx(field.type, field, parent)
        return genValue(rootCtx(typeCtx, resolverCoordinate = coord), selective, selections, ctx)
    }

    override fun gen(
        type: String,
        selective: Boolean,
        selections: EngineSelectionSet,
        ctx: EngineCtx,
    ): EngineObjectData {
        val def = schema.schema.getObjectType(type)
        val value = genValue(
            rootCtx(
                TypeCtx(def),
                resolverCoordinate = type to null,
                nonNullable = true,
                genForNodeResolver = true
            ),
            selective,
            selections,
            ctx
        )
        return value as EngineObjectData
    }

    private data class Ctx(
        val tc: TypeCtx,
        val resolverCoordinate: TypeOrFieldCoordinate,
        val depth: Int = 0,
        val maxDepth: Int = MaxValueDepth.default,
        val nonNullable: Boolean = false,
        val genForNodeResolver: Boolean = false,
    ) {
        fun traverse(field: GraphQLFieldDefinition): Ctx = push(tc.traverse(field)).copy(genForNodeResolver = false)

        fun traverse(type: GraphQLType): Ctx = copy(tc = tc.traverse(type))

        private fun push(type: TypeCtx): Ctx = copy(tc = type, depth = depth + 1, nonNullable = type.type is GraphQLNonNull)

        val nullable: Boolean get() = !nonNullable
        val overBudget: Boolean get() = depth >= maxDepth
    }

    private fun rootCtx(
        tc: TypeCtx,
        resolverCoordinate: TypeOrFieldCoordinate,
        nonNullable: Boolean = false,
        genForNodeResolver: Boolean = false
    ): Ctx =
        Ctx(
            tc = tc,
            resolverCoordinate = resolverCoordinate,
            maxDepth = cfg[MaxValueDepth],
            nonNullable = nonNullable,
            genForNodeResolver = genForNodeResolver,
        )

    private fun genValue(
        valueCtx: Ctx,
        selective: Boolean,
        selections: EngineSelectionSet?,
        ctx: EngineCtx
    ): Any? {
        if (GraphQLTypeUtil.isNullable(valueCtx.tc.type) && valueCtx.nullable) {
            return if (valueCtx.overBudget || rs.sampleWeight(cfg[ExplicitNullValueWeight])) {
                null
            } else {
                genValue(valueCtx.copy(nonNullable = true), selective, selections, ctx)
            }
        }

        return when (val type = valueCtx.tc.type) {
            is GraphQLNonNull -> genValue(
                valueCtx.traverse(type.wrappedType).copy(nonNullable = true),
                selective,
                selections,
                ctx
            )
            is GraphQLList -> {
                val innerCtx = valueCtx.copy(
                    tc = valueCtx.tc.traverse(type.wrappedType),
                    nonNullable = type.wrappedType is GraphQLNonNull,
                    depth = valueCtx.depth + 1,
                )
                val listSize = if (innerCtx.overBudget) 0 else Arb.int(cfg[ListValueSize]).next(rs)
                List(listSize) {
                    genValue(
                        innerCtx,
                        selective,
                        selections,
                        ctx
                    )
                }
            }
            is GraphQLObjectType -> {
                require(selections != null)

                // try to return a root field reference if possible
                val ref = maybeGenRootFieldRef(valueCtx, ctx, type)
                if (ref != null) {
                    return ref
                }

                // return a node reference if asked to generate a type for a node with a resolver
                // and we're not currently generating a value for that very resolver
                if (type.name in resolverConfig.nodeResolvers && !valueCtx.genForNodeResolver) {
                    val globalId = ctx.globalIDCodec.serialize(
                        type.name,
                        Arb.string(cfg[StringValueSize]).next(rs),
                    )
                    return ctx.createNodeReference(globalId, type)
                }

                // build the set of fields for which we need to generate a value.
                // start by considering all fields in the current object, minus the fields with resolvers
                var coords = type.objectCoordinates - resolverConfig.fieldResolvers

                // if we're in a node resolver, don't generate a value for the id field -- they are
                // defined to be outside a node resolvers output selection set.
                if (valueCtx.genForNodeResolver) {
                    coords -= type.name to "id"
                }
                var data = coords.associate { coord ->
                    val subSelections = if (coord.supportsSubselections(schema)) {
                        selections.selectionSetForField(coord.first, coord.second)
                    } else {
                        null
                    }

                    val field = type.getFieldDefinition(coord.second)
                    val value = genValue(
                        valueCtx.traverse(field),
                        selective,
                        subSelections,
                        ctx
                    )

                    coord.second to value
                }

                if (selective) {
                    // For selective resolvers, it's important to always generate the full output selection set
                    // before applying selective filtering.
                    //
                    // This ensures that generated values are stable and don't vary by selection set.
                    // For example, if we generate a value for `{ a, foo { x } }` and then
                    // subsequently generate a value for `{ b, foo { y } }`,
                    // then the value of foo can change between invocations, because each access to the
                    // `rs` will mutate the internal state of the random source
                    //
                    // This is not an issue if we generate a value for `{a, b, foo { x, y }}` twice, and then filter
                    // each result to the requested selection set.
                    data = data.filterKeys { k ->
                        selections.containsField(type.name, k)
                    }
                }

                ResolvedEngineObjectData(type, data)
            }
            is GraphQLUnionType, is GraphQLInterfaceType -> {
                require(selections != null)
                var objs = schema.rels.possibleObjectTypes(type as GraphQLCompositeType).toList()

                // Selective resolvers may be executed multiple times, and may require that, for the same seed,
                // a selective value is always a subset of the non-selective value for the same seed.
                //
                // In order to ensure this property, this code needs to generate values for all possible
                // concrete types before it touches the RandomSource to determine which value to return.
                //
                // This is not efficient but also not terribly expensive, since this generator only generates
                // for an output selection set

                val objValues = objs.associateWith { obj ->
                    genValue(
                        valueCtx = valueCtx.traverse(obj),
                        selective,
                        selections = selections.selectionSetForType(obj.name),
                        ctx
                    )
                }

                // Note that SelectedTypeBias uses the selection set to steer the generated value, which breaks
                // the property that generated results are independent of selected shape. While the value for a
                // concrete type will be stable, the choice of which type will be generated inherently depends
                // on which types are selected.
                if (rs.sampleWeight(cfg[SelectedTypeBias])) {
                    val selectedTypes = objs.filter { selections.requestsType(it.name) }
                    // if selectedTypes is empty, then a future call to Arb.element will throw.
                    // In this case, fallback to the list of concrete types
                    objs = selectedTypes.ifEmpty { objs }
                }
                objValues.getValue(Arb.element(objs).next(rs))
            }

            is GraphQLEnumType ->
                EngineValueConv(schema, type, null)
                    .invert(enumGen.gen(type))

            is GraphQLScalarType ->
                EngineValueConv(schema, type, null)
                    .invert(scalarGen.gen(valueCtx.tc))

            else -> throw IllegalArgumentException("Cannot generate value for unsupported type: ${SchemaPrinter().print(type)} ")
        }
    }

    private fun maybeGenRootFieldRef(
        valueCtx: Ctx,
        ctx: EngineCtx,
        type: GraphQLObjectType
    ): RootFieldReference? {
        // try to return a root field reference if possible
        val availRefs = ctx.fieldRefs.refFieldsFor(type)
            .filter { ref ->
                // restrict refs to those that have an index lower than the current coordinate
                // This ensures that references always form a DAG and do not create cycles.
                // RequiredSelectionSetGen must use the same ordering to keep the combined graph acyclic.
                coordinateIndex.comparator.compare(
                    ref.refFieldCoord,
                    valueCtx.resolverCoordinate
                ) < 0
            }

        if (availRefs.isEmpty() || !rs.sampleWeight(cfg[ResolverFieldRefWeight])) {
            return null
        }

        val ref = Arb.element(availRefs).next(rs)

        val args = ref.refField.arguments.associate { arg ->
            val conv = EngineValueConv(schema, arg.type, null)
            val argValue = Arb.ir(schema, arg.type, cfg)
                .map(conv::invert)
                .next(rs)
            arg.name to argValue
        }
        return ctx.createRootFieldReference(ref.rootFieldPath, ref.refType, args)
    }
}

interface EngineCtx {
    val globalIDCodec: GlobalIDCodec
    val fieldRefs: FieldRefs

    fun createNodeReference(
        id: String,
        objectType: GraphQLObjectType
    ): NodeReference

    fun createRootFieldReference(
        rootFieldPath: List<String>,
        type: GraphQLObjectType,
        args: Map<String, Any?>
    ): RootFieldReference

    private class AdaptedEngineExecutionContext(val ctx: EngineExecutionContext) : EngineCtx {
        override val globalIDCodec: GlobalIDCodec get() = ctx.globalIDCodec
        override val fieldRefs: FieldRefs = FieldRefs(ctx.fullSchema)

        override fun createNodeReference(
            id: String,
            objectType: GraphQLObjectType
        ): NodeReference = ctx.createNodeReference(id, objectType)

        override fun createRootFieldReference(
            rootFieldPath: List<String>,
            type: GraphQLObjectType,
            args: Map<String, Any?>
        ): RootFieldReference = ctx.createRootFieldReference(rootFieldPath, type, args)
    }

    companion object {
        /** Project an [EngineExecutionContext] into an [EngineCtx] */
        operator fun invoke(ctx: EngineExecutionContext): EngineCtx = AdaptedEngineExecutionContext(ctx)
    }
}

data class FieldRef(
    val rootFieldPath: List<String>,
    val refFieldCoord: Coordinate,
    val refField: GraphQLFieldDefinition,
    val refType: GraphQLObjectType,
) {
    init {
        require(refField.name == rootFieldPath.last())
    }
}

interface FieldRefs {
    fun refFieldsFor(type: GraphQLOutputType): List<FieldRef>

    private class Impl(val map: Map<TypeExpr, List<FieldRef>>) : FieldRefs {
        override fun refFieldsFor(type: GraphQLOutputType): List<FieldRef> = map[TypeExpr(type)] ?: emptyList()
    }

    companion object {
        val empty: FieldRefs = object : FieldRefs {
            override fun refFieldsFor(type: GraphQLOutputType): List<FieldRef> = emptyList()
        }

        operator fun invoke(schema: ViaductSchema): FieldRefs = Impl(buildRefMap(schema.schema.queryType))

        private fun buildRefMap(root: GraphQLObjectType): Map<TypeExpr, List<FieldRef>> {
            val map = mutableMapOf<TypeExpr, MutableList<FieldRef>>()

            fun walk(
                path: List<String>,
                obj: GraphQLObjectType
            ) {
                obj.fields
                    .forEach { field ->
                        val baseType = GraphQLTypeUtil.unwrapAll(field.type)
                        val isNamespaceField = baseType is GraphQLObjectType &&
                            baseType.hasAppliedDirective("namespaceType")

                        // Namespace fields may not be used as resolvers; traverse them to find resolvers beneath them.
                        if (isNamespaceField) {
                            walk(path + field.name, baseType as GraphQLObjectType)
                            return@forEach
                        }

                        if (baseType !is GraphQLObjectType) {
                            return@forEach
                        }

                        val expressions = buildList {
                            add(TypeExpr(field.type))

                            // A non-null reference can also satisfy a call site requesting its nullable type.
                            if (field.type is GraphQLNonNull) {
                                add(TypeExpr(GraphQLTypeUtil.unwrapNonNullAs(field.type)))
                            }
                        }
                        expressions.forEach { expression ->
                            val ref = FieldRef(
                                rootFieldPath = path + field.name,
                                refFieldCoord = obj.name to field.name,
                                refField = field,
                                refType = baseType
                            )
                            map.getOrPut(expression, ::mutableListOf).add(ref)
                        }
                    }
            }

            walk(emptyList(), root)
            return map
        }
    }
}
