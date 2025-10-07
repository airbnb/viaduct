package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLUnionType
import kotlin.collections.plus
import viaduct.engine.api.Coordinate
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl
import viaduct.engine.runtime.validation.Validator

/**
 * Validates that a graph formed by required selection sets contains no cycles. In this graph,
 * there's an edge from RSS V to W if W is a RSS for a field coordinate in V's selections.
 * Checker RSS's only depend on resolver RSS's, whereas resolver RSS's depend on both checker
 * and resolver RSS's.
 *
 * This validator biases towards strict cycle validation and will declare some topologies
 * to be cyclic even if they are potentially acyclic at runtime.
 *
 * For example, consider this recursive type that is mediated by an interface:
 *   ```graphql
 *     interface Interface { x: Int }
 *     type Obj implements Interface { x: Int, iface: Interface }
 *   ```
 *     Where Obj.x requires "{ iface { x } }"
 *
 * Even though this graph is only cyclic when the concrete type of `Obj.iface` is Obj, this
 * Validator declares this topology to be a cycle and always invalid.
 */
class RequiredSelectionsAreAcyclic(
    private val schema: ViaductSchema,
) : Validator<RequiredSelectionsValidationCtx> {
    private val rawSelectionSetFactory = RawSelectionSetFactoryImpl(schema)

    /**
     * Validates all RSS's of the given field.
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        val registry = ctx.requiredSelectionSetRegistry
        if (ctx.fieldName != null) {
            registry.getFieldResolverRequiredSelectionSets(ctx.typeName, ctx.fieldName).forEach { rss ->
                validate(FieldResolverRSSNode(rss, ctx.typeName to ctx.fieldName), registry)
            }
            registry.getFieldCheckerRequiredSelectionSets(ctx.typeName, ctx.fieldName, executeAccessChecksInModstrat = true).forEach { rss ->
                validate(CheckerRSSNode(rss, ctx.typeName to ctx.fieldName), registry)
            }
        } else {
            registry.getTypeCheckerRequiredSelectionSets(ctx.typeName, executeAccessChecksInModstrat = true).forEach { rss ->
                validate(CheckerRSSNode(rss, ctx.typeName to ctx.fieldName), registry)
            }
        }
    }

    private fun validate(
        root: RSSNode,
        registry: RequiredSelectionSetRegistry
    ) {
        dfs(root, mutableListOf(), mutableSetOf(), mutableSetOf(), registry)?.let { cyclePath ->
            throw RequiredSelectionsCycleException(cyclePath.map { it.toString() })
        }
    }

    /**
     * Finds cycles using depth-first search from the given [node]
     *
     * @param node The node to search for cycles
     * @param path The nodes in the path that's being searched
     * @param visiting The set version of [path]
     * @param visited The nodes that have been fully explored, to avoid recomputation
     */
    private fun dfs(
        node: RSSNode,
        path: MutableList<RSSNode>,
        visiting: MutableSet<RSSNode>,
        visited: MutableSet<RSSNode>,
        registry: RequiredSelectionSetRegistry
    ): List<RSSNode>? {
        // If we encounter a node currently being visited (in the current path), we have a cycle
        if (node in visiting) {
            val cycleStart = path.indexOf(node)
            return path.subList(cycleStart, path.size) + node
        }

        path.add(node)
        visiting.add(node)

        node.edges(registry).forEach { edge ->
            if (edge !in visited) {
                val cycle = dfs(edge, path, visiting, visited, registry)
                if (cycle != null) return cycle
            }
        }

        path.removeLast()
        visiting.remove(node)
        visited.add(node)
        return null
    }

    /**
     * Represents a RequiredSelectionSet for a field
     */
    private abstract inner class RSSNode(
        protected val rss: RequiredSelectionSet,
        private val typeOrFieldCoordinate: TypeOrFieldCoordinate
    ) {
        /**
         * Returns the edges from this node as a set of destination nodes
         */
        fun edges(registry: RequiredSelectionSetRegistry): List<RSSNode> {
            val coords = rss.objectCoords(registry)
            return buildList {
                coords.forEach { coord ->
                    addAll(edges(registry, coord))
                }
            }
        }

        /**
         * Returns the edges contributed by the given type or field coordinate
         */
        protected abstract fun edges(
            registry: RequiredSelectionSetRegistry,
            typeOrFieldCoordinate: TypeOrFieldCoordinate
        ): List<RSSNode>

        /**
         * Returns all field coordinates and field object types referenced by this [RequiredSelectionSet].
         * All interface and union coordinates and types are replaced by coordinates of its object type implementations.
         */
        private fun RequiredSelectionSet.objectCoords(registry: RequiredSelectionSetRegistry): Set<TypeOrFieldCoordinate> {
            val coords = rawSelectionSetFactory.rawSelectionSet(selections, emptyMap()).objectCoords().toMutableSet()
            variablesResolvers.forEach { variablesResolver ->
                variablesResolver.requiredSelectionSet?.objectCoords(registry)?.let { coords.addAll(it) }
            }
            return coords
        }

        /**
         * Returns all field coordinates and field object types referenced by this [RawSelectionSet].
         * All interface and union coordinates and types are replaced by coordinates of its object type implementations.
         */
        private fun RawSelectionSet.objectCoords(): Set<TypeOrFieldCoordinate> =
            buildSet {
                // start with all selections. This will include scalar fields and other selections
                // that do not support sub-selections
                selections().forEach {
                    objectTypes(it.typeCondition).forEach { objectTypeName ->
                        if (!it.fieldName.startsWith("__")) {
                            add(objectTypeName to it.fieldName)
                        }
                    }
                }
                // "traversable" fields support sub-selections. Recurse through each traversable
                // field and extract its coords
                traversableSelections().forEach { sel ->
                    val nestedSelectionSet = selectionSetForField(sel.typeCondition, sel.fieldName)
                    val typeName = nestedSelectionSet.type
                    objectTypes(typeName).forEach { objectTypeName -> add(objectTypeName to null) }
                    addAll(nestedSelectionSet.objectCoords())
                }
            }

        /**
         * Given [typeName], a composite output type, return all possible object type names.
         */
        private fun objectTypes(typeName: String): List<String> {
            val type = schema.schema.getType(typeName)
            return when (type) {
                is GraphQLObjectType -> listOf(typeName)
                is GraphQLInterfaceType -> schema.schema.getImplementations(type).map { it.name }
                is GraphQLUnionType -> type.types.map { it.name }
                else -> throw IllegalArgumentException("Unexpected non-composite type $type")
            }
        }

        override fun hashCode(): Int {
            return 31 * rss.hashCode() + typeOrFieldCoordinate.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            return javaClass == other?.javaClass &&
                other is RSSNode &&
                typeOrFieldCoordinate == other.typeOrFieldCoordinate &&
                rss == other.rss
        }

        override fun toString(): String {
            if (typeOrFieldCoordinate.second == null) return typeOrFieldCoordinate.first
            return "${typeOrFieldCoordinate.first}.${typeOrFieldCoordinate.second}"
        }
    }

    /**
     * A field resolver's RSS
     */
    private inner class FieldResolverRSSNode(
        rss: RequiredSelectionSet,
        coordinate: Coordinate,
    ) : RSSNode(rss, coordinate) {
        override fun edges(
            registry: RequiredSelectionSetRegistry,
            typeOrFieldCoordinate: TypeOrFieldCoordinate
        ): List<RSSNode> =
            buildList {
                val (typeName, fieldName) = typeOrFieldCoordinate
                if (fieldName != null) {
                    val resolverRequiredSelections = registry.getFieldResolverRequiredSelectionSets(typeName, fieldName)
                    resolverRequiredSelections.forEach {
                        add(FieldResolverRSSNode(it, typeName to fieldName))
                    }
                    val fieldCheckerRequiredSelections = registry.getFieldCheckerRequiredSelectionSets(typeName, fieldName, executeAccessChecksInModstrat = true)
                    fieldCheckerRequiredSelections.forEach {
                        add(CheckerRSSNode(it, typeOrFieldCoordinate))
                    }
                } else {
                    val typeCheckerRequiredSelections = registry.getTypeCheckerRequiredSelectionSets(typeName, executeAccessChecksInModstrat = true)
                    typeCheckerRequiredSelections.forEach {
                        add(CheckerRSSNode(it, typeOrFieldCoordinate))
                    }
                }
            }
    }

    /**
     * A field checker's RSS
     */
    private inner class CheckerRSSNode(
        rss: RequiredSelectionSet,
        typeOrFieldCoordinate: TypeOrFieldCoordinate
    ) : RSSNode(rss, typeOrFieldCoordinate) {
        override fun edges(
            registry: RequiredSelectionSetRegistry,
            typeOrFieldCoordinate: TypeOrFieldCoordinate
        ): List<RSSNode> {
            val (typeName, fieldName) = typeOrFieldCoordinate
            if (fieldName == null) return emptyList()

            val resolverRequiredSelections = registry.getFieldResolverRequiredSelectionSets(typeName, fieldName)
            return resolverRequiredSelections.map { FieldResolverRSSNode(it, typeName to fieldName) }
        }
    }
}

class RequiredSelectionsCycleException(val path: List<String>) : Exception() {
    override val message: String
        get() {
            val pathString = path.joinToString(" -> ") { it }
            return "Cyclic @Resolver selections detected in path: $pathString"
        }
}
