package viaduct.arbitrary.graphql

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
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
            ResolverCoordinates(schema, cfg, rs),
            cfg,
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
            ResolverCoordinates(schema, cfg, rs),
            cfg,
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
    private val resolverCoordinates: ResolverCoordinates,
    private val cfg: Config,
    private val rs: RandomSource
) : FieldResolverValueGen, NodeResolverValueGen {
    private val enumGen = EnumValueGen(rs)
    private val scalarGen = ScalarValueGen(schema, cfg, rs)

    constructor(env: ViaductGenEnv) :
        this(
            env.schemas.viaductSchema,
            env.resolverCoordinates,
            env.cfg,
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
        return genValue(typeCtx, selective, selections, ctx)
    }

    override fun gen(
        type: String,
        selective: Boolean,
        selections: EngineSelectionSet,
        ctx: EngineCtx,
    ): EngineObjectData {
        val def = schema.schema.getObjectType(type)
        val value = genValue(TypeCtx(def), selective, selections, ctx, nullable = false, genForNodeResolver = true)
        return value as EngineObjectData
    }

    private fun genValue(
        tc: TypeCtx,
        selective: Boolean,
        selections: EngineSelectionSet?,
        ctx: EngineCtx,
        nullable: Boolean = true,
        genForNodeResolver: Boolean = false
    ): Any? {
        if (GraphQLTypeUtil.isNullable(tc.type) && nullable) {
            return if (rs.sampleWeight(cfg[ExplicitNullValueWeight])) {
                null
            } else {
                genValue(tc, selective, selections, ctx, nullable = false, genForNodeResolver = genForNodeResolver)
            }
        }

        return when (val type = tc.type) {
            is GraphQLNonNull -> genValue(
                tc.traverse(type.wrappedType),
                selective,
                selections,
                ctx,
                nullable = false
            )
            is GraphQLList -> {
                val listSize = Arb.int(cfg[ListValueSize]).next(rs)
                val innerTc = tc.traverse(type.wrappedType)
                List(listSize) {
                    genValue(
                        innerTc,
                        selective,
                        selections,
                        ctx
                    )
                }
            }
            is GraphQLObjectType -> {
                require(selections != null)
                if (type.name in resolverCoordinates.nodeResolvers && !genForNodeResolver) {
                    val globalId = ctx.globalIDCodec.serialize(
                        type.name,
                        Arb.string(cfg[StringValueSize]).next(rs),
                    )
                    return ctx.createNodeReference(globalId, type)
                }

                var coords = type.objectCoordinates - resolverCoordinates.fieldResolvers
                if (selective) {
                    // if this resolver is selective, then drop any coordinates that are not selected
                    coords = coords
                        .filter { (type, field) -> selections.containsField(type, field) }
                        .toSet()
                }
                val data = coords.associate { coord ->
                    val subSelections = if (coord.supportsSubselections(schema)) {
                        selections.selectionSetForField(coord.first, coord.second)
                    } else {
                        null
                    }

                    val field = type.getFieldDefinition(coord.second)
                    val value = genValue(
                        tc.traverse(field),
                        selective,
                        subSelections,
                        ctx,
                        nullable,
                        genForNodeResolver = false
                    )

                    coord.second to value
                }
                ResolvedEngineObjectData(type, data)
            }
            is GraphQLUnionType, is GraphQLInterfaceType -> {
                require(selections != null)
                var objs = schema.rels.possibleObjectTypes(type as GraphQLCompositeType).toList()

                if (rs.sampleWeight(cfg[SelectedTypeBias])) {
                    val selectedTypes = objs.filter { selections.requestsType(it.name) }
                    // if selectedTypes is empty, then a future call to Arb.element will throw.
                    // In this case, fallback to the list of concrete types
                    objs = selectedTypes.ifEmpty { objs }
                }

                val obj = Arb.element(objs).next(rs)
                genValue(
                    tc = tc.traverse(obj),
                    selective,
                    selections = selections.selectionSetForType(obj.name),
                    ctx,
                    nullable,
                    genForNodeResolver
                )
            }

            is GraphQLEnumType ->
                EngineValueConv(schema, type, null)
                    .invert(enumGen.gen(type))

            is GraphQLScalarType ->
                EngineValueConv(schema, type, null)
                    .invert(scalarGen.gen(tc))

            else -> throw IllegalArgumentException("Cannot generate value for unsupported type: ${SchemaPrinter().print(type)} ")
        }
    }
}

interface EngineCtx {
    val globalIDCodec: GlobalIDCodec

    fun createNodeReference(
        id: String,
        objectType: GraphQLObjectType
    ): NodeReference

    @JvmInline
    private value class AdaptedEngineExecutionContext(val ctx: EngineExecutionContext) : EngineCtx {
        override val globalIDCodec: GlobalIDCodec get() = ctx.globalIDCodec

        override fun createNodeReference(
            id: String,
            objectType: GraphQLObjectType
        ): NodeReference = ctx.createNodeReference(id, objectType)
    }

    companion object {
        /** Project an [EngineExecutionContext] into an [EngineCtx] */
        operator fun invoke(ctx: EngineExecutionContext): EngineCtx = AdaptedEngineExecutionContext(ctx)
    }
}
