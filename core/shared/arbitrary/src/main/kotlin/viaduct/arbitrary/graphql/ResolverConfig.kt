package viaduct.arbitrary.graphql

import graphql.language.BooleanValue
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import io.kotest.property.RandomSource
import viaduct.arbitrary.common.Config
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj

interface ResolverConfig {
    /** Returns the combined set of field and node resolvers */
    fun resolvers(): Set<TypeOrFieldCoordinate>

    /** returns the set of field resolvers in [Coordinate] form */
    val fieldResolvers: Set<Coordinate>
        get() = resolvers().mapNotNull { (type, field) -> field?.let { type to it } }.toSet()

    /** returns the set of type names with configured node resolvers */
    val nodeResolvers: Set<String>
        get() = resolvers().mapNotNull { (type, field) -> if (field == null) type else null }.toSet()

    /**
     * Returns true if the provided coordinate is selective.
     * Throws IllegalArgumentException if there is no resolver configured for [coord]
     */
    fun isSelective(coord: TypeOrFieldCoordinate): Boolean

    /** Returns true if every configured resolver is inhabited */
    fun isInhabited(): Boolean

    companion object {
        /**
         * Creates a [ResolverConfig].
         *
         * The returned [ResolverConfig] will include resolvers at `@resolver` directive locations plus 0 or
         * more resolvers at locations not declared in the provided schema.
         * Resolvers will always be configured for query/mutation/subscription root fields when
         * [IncludeRequiredResolvers] is enabled.
         *
         * When [IncludeRequiredResolvers] is enabled, the returned [ResolverConfig] will be guaranteed
         * to be inhabited (see Output Selection Set Inhabitability below).
         * Generated resolvers will respect the configuration of [SelectiveResolverWeight], but any extra resolvers
         * added only to restore inhabitability are always non-selective.
         *
         * ## Output Selection Set Inhabitability
         * An output selection set has the property of being 'uninhabited' if there are no values that can be
         * produced for it.
         *
         * For example, in this configuration:
         * ```graphql
         *   type Foo { bar:Bar @resolver(selective=false) }
         *   type Bar { bar:Bar }
         * ```
         * The resolver for `Foo.bar` is uninhabited because it is responsible for producing an infinitely recursive
         * value for `Bar`, which cannot be done in Viaduct.
         *
         * In this example, this configuration can be made inhabited in multiple ways:
         * 1. Insert a non-selective field resolver at `Bar.bar`
         * 1. Make the `Foo.bar` selective
         * 1. Convert `Bar` to an implementation of `Node` and annotate with `@resolver`
         *
         * This factory, when modifying a ResolverConfig to be inhabited, will always inject non-selective field resolvers.
         *
         * @see IncludeRequiredResolvers
         * @see UndeclaredFieldResolverWeight
         * @see UndeclaredNodeResolverWeight
         * @see SelectiveResolverWeight
         */
        operator fun invoke(
            schema: ViaductSchema,
            cfg: Config,
            rs: RandomSource
        ): ResolverConfig = ResolverConfigImpl(schema, cfg, rs)
    }
}

class ResolverConfigImpl private constructor(
    private val schema: ViaductSchema,
    private val resolvers: Map<TypeOrFieldCoordinate, Boolean>,
) : ResolverConfig {
    constructor(
        schema: ViaductSchema,
        fieldResolvers: Set<Coordinate>,
        nodeResolvers: Set<String>
    ) : this(
        schema,
        buildMap {
            fieldResolvers.forEach { put(it, false) }
            nodeResolvers.forEach { put(it to null, false) }
        }
    )

    override fun resolvers(): Set<TypeOrFieldCoordinate> = resolvers.keys

    override fun isSelective(coord: TypeOrFieldCoordinate): Boolean =
        resolvers[coord]
            ?: throw IllegalArgumentException("No resolver configured for $coord")

    override fun isInhabited(): Boolean = firstResolverInsertionPoint() == null

    fun plus(other: ResolverConfigImpl): ResolverConfigImpl {
        require(schema === other.schema)
        return ResolverConfigImpl(
            schema = schema,
            resolvers = resolvers + other.resolvers,
        )
    }

    fun fieldResolverOutputSelectionSet(coord: Coordinate): Set<Coordinate> {
        require(coord in fieldResolvers)
        return buildOutputSelectionSet(setOf(coord))
    }

    fun nodeResolverOutputSelectionSet(typeName: String): Set<Coordinate> {
        require(typeName in nodeResolvers)
        val type = schema.schema.getType(typeName)
        require(type is GraphQLObjectType)
        return buildOutputSelectionSet(
            schema.objectCoordinates(type)
                .filter { it !in fieldResolvers }
                .filter { it != typeName to "id" }
                .toSet()
        )
    }

    fun containsUninhabitedResolvers(): Boolean = firstResolverInsertionPoint() != null

    /** Returns a rebuilt [ResolverConfigImpl], with resolvers at all insertion points. */
    private fun inhabited(): ResolverConfigImpl {
        tailrec fun loop(acc: ResolverConfigImpl): ResolverConfigImpl {
            val insertionPoint = acc.firstResolverInsertionPoint() ?: return acc
            check(insertionPoint !in acc.fieldResolvers) {
                "Cannot make resolver graph inhabited by inserting a resolver at " +
                    "$insertionPoint: field already has a resolver"
            }
            return loop(
                ResolverConfigImpl(
                    schema = acc.schema,
                    resolvers = acc.resolvers + (insertionPoint to false),
                )
            )
        }
        return loop(this)
    }

    private fun buildOutputSelectionSet(roots: Set<Coordinate>): Set<Coordinate> {
        tailrec fun loop(
            acc: Set<Coordinate>,
            seen: Set<GraphQLCompositeType>,
            pending: Set<Coordinate>
        ): Set<Coordinate> {
            val coord = pending.firstOrNull() ?: return acc
            val fieldType = GraphQLTypeUtil.unwrapAll(
                schema.schema.getFieldDefinition(coord.gj).type
            )
            return if (fieldType.name in nodeResolvers || fieldType !is GraphQLCompositeType) {
                loop(acc + coord, seen, pending - coord)
            } else {
                fieldType as GraphQLCompositeType
                val addToPending = schema.rels.possibleObjectTypes(fieldType)
                    .filter { it !in seen && it.name !in nodeResolvers }
                    .flatMap { obj ->
                        schema.objectCoordinates(obj)
                    }
                    .filter { it !in acc && it !in fieldResolvers }

                loop(
                    acc + coord,
                    seen + fieldType,
                    pending - coord + addToPending
                )
            }
        }

        return loop(emptySet(), emptySet(), roots)
    }

    private fun firstResolverInsertionPoint(): Coordinate? {
        fieldResolvers.sortedWith(compareBy({ it.first }, { it.second })).forEach { coord ->
            if (isSelective(coord)) return@forEach
            findInsertionPointForField(coord, emptySet())?.let { return it }
        }

        nodeResolvers.sorted().forEach { typeName ->
            if (isSelective(typeName to null)) return@forEach
            val type = schema.schema.getObjectType(typeName) ?: return@forEach
            findInsertionPointForObject(type, emptySet())?.let { return it }
        }

        return null
    }

    private fun findInsertionPointForObject(
        type: GraphQLObjectType,
        seen: Set<GraphQLObjectType>
    ): Coordinate? {
        val nextSeen = seen + type
        type.objectCoordinates
            .sortedWith(compareBy({ it.first }, { it.second }))
            .filter { it !in fieldResolvers }
            .forEach { coord ->
                findInsertionPointForField(coord, nextSeen)?.let { return it }
            }
        return null
    }

    private fun findInsertionPointForField(
        coord: Coordinate,
        seen: Set<GraphQLObjectType>
    ): Coordinate? {
        val fieldType = schema.schema.getFieldDefinition(coord.gj).type
        val unwrapped = (GraphQLTypeUtil.unwrapAll(fieldType) as? GraphQLCompositeType) ?: return null

        schema.rels.possibleObjectTypes(unwrapped)
            .sortedBy { it.name }
            .forEach { targetType ->
                if (targetType.name in nodeResolvers) return@forEach
                if (targetType in seen) return coord
                findInsertionPointForObject(targetType, seen)?.let { return it }
            }
        return null
    }

    companion object {
        operator fun invoke(
            schema: ViaductSchema,
            cfg: Config,
            rs: RandomSource
        ): ResolverConfigImpl {
            val config = ResolverConfigImpl(
                schema = schema,
                resolvers = initialResolvers(schema, cfg, rs),
            )
            val result = if (cfg[IncludeRequiredResolvers]) {
                config.inhabited()
            } else {
                config
            }

            require(result.isInhabited() || !cfg[IncludeRequiredResolvers]) {
                "ResolverConfig should be inhabited when IncludeRequiredResolvers is enabled"
            }
            return result
        }

        private fun initialResolvers(
            schema: ViaductSchema,
            cfg: Config,
            rs: RandomSource
        ): Map<TypeOrFieldCoordinate, Boolean> {
            val resolvers = mutableMapOf<TypeOrFieldCoordinate, Boolean>()

            schema.objectCoordinates
                .sortedWith(compareBy({ it.first }, { it.second }))
                .forEach { coord ->
                    val field = schema.schema.getFieldDefinition(coord.gj)
                    val declaredResolver = field.declaredResolver()
                    val shouldGenerate =
                        declaredResolver != null ||
                            (cfg[IncludeRequiredResolvers] && schema.isRootField(coord)) ||
                            rs.sampleWeight(cfg[UndeclaredFieldResolverWeight])

                    if (shouldGenerate) {
                        resolvers[coord] = declaredResolver?.selective ?: rs.sampleWeight(cfg[SelectiveResolverWeight])
                    }
                }

            schema.nodeImpls
                .sorted()
                .forEach { typeName ->
                    val obj = requireNotNull(schema.schema.getObjectType(typeName))
                    val declaredResolver = obj.declaredResolver()
                    val shouldGenerate =
                        declaredResolver != null ||
                            rs.sampleWeight(cfg[UndeclaredNodeResolverWeight])

                    if (shouldGenerate) {
                        resolvers[typeName to null] = declaredResolver?.selective ?: rs.sampleWeight(cfg[SelectiveResolverWeight])
                    }
                }

            return resolvers
        }
    }
}

private data class DeclaredResolver(val selective: Boolean)

private fun GraphQLDirectiveContainer.declaredResolver(): DeclaredResolver? {
    val dir = appliedDirectives.firstOrNull { it.name == "resolver" } ?: return null
    val value = dir.getArgument("isSelective")
        ?.argumentValue
        ?.value

    val selective = when (value) {
        null -> false
        is BooleanValue -> value.isValue
        is Boolean -> value
        else -> throw IllegalArgumentException("Expected @resolver(selective:) to decode as a boolean")
    }
    return DeclaredResolver(selective)
}

private fun ViaductSchema.isRootField(coord: Coordinate): Boolean =
    when (coord.first) {
        schema.queryType.name -> true
        schema.mutationType?.name -> true
        schema.subscriptionType?.name -> true
        else -> false
    }
