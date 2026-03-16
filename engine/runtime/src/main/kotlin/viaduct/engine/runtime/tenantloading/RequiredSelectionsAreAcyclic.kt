package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLUnionType
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime.validation.Validator

/**
 * Validates that a graph formed by required selection sets contains no cycles. In this graph,
 * there's an edge from RSS V to W if W is a RSS for a field coordinate in V's required selections.
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
    private val engineSelectionSetFactory = EngineSelectionSetFactoryImpl(schema)

    /**
     * Validates all RSS's of the given field or type by building a [RequiredSelectionSetGraph]
     * of all transitively reachable RSSes and checking it for cycles.
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        val registry = ctx.requiredSelectionSetRegistry
        val graph = RequiredSelectionSetGraph()
        val visited = mutableSetOf<Pair<RequiredSelectionSet, TypeOrFieldCoordinate>>()

        if (ctx.fieldName != null) {
            registry.getFieldResolverRequiredSelectionSets(ctx.typeName, ctx.fieldName).forEach { rss ->
                populate(graph, registry, rss, ctx.typeName to ctx.fieldName, visited)
            }
            registry.getFieldCheckerRequiredSelectionSets(ctx.typeName, ctx.fieldName, executeAccessChecksInModstrat = true).forEach { rss ->
                populate(graph, registry, rss, ctx.typeName to ctx.fieldName, visited)
            }
        } else {
            registry.getTypeCheckerRequiredSelectionSets(ctx.typeName, executeAccessChecksInModstrat = true).forEach { rss ->
                populate(graph, registry, rss, ctx.typeName to ctx.fieldName, visited)
            }
        }

        graph.assertAcyclic()
    }

    /** Recursively populates [graph] from nodes in [rss] and all transitively reachable nodes */
    private fun populate(
        graph: RequiredSelectionSetGraph,
        registry: RequiredSelectionSetRegistry,
        rss: RequiredSelectionSet,
        coord: TypeOrFieldCoordinate,
        visited: MutableSet<Pair<RequiredSelectionSet, TypeOrFieldCoordinate>>,
    ) {
        if (!visited.add(rss to coord)) return

        val objCoords = rss.objectCoords(registry)
        if (rss.forChecker) {
            graph.addCheckerNode(coord, objCoords)
        } else {
            graph.addResolverNode(coord, objCoords)
        }

        for (c in objCoords) {
            val (typeName, fieldName) = c
            if (fieldName != null) {
                registry.getFieldResolverRequiredSelectionSets(typeName, fieldName).forEach { childRss ->
                    populate(graph, registry, childRss, c, visited)
                }
                registry.getFieldCheckerRequiredSelectionSets(typeName, fieldName, executeAccessChecksInModstrat = true).forEach { childRss ->
                    populate(graph, registry, childRss, c, visited)
                }
            } else {
                registry.getTypeCheckerRequiredSelectionSets(typeName, executeAccessChecksInModstrat = true).forEach { childRss ->
                    populate(graph, registry, childRss, c, visited)
                }
            }
        }
    }

    /**
     * Returns all field coordinates and field object types referenced by this [RequiredSelectionSet].
     * All interface and union coordinates and types are replaced by coordinates of its object type implementations.
     */
    private fun RequiredSelectionSet.objectCoords(registry: RequiredSelectionSetRegistry): Set<TypeOrFieldCoordinate> {
        val coords = engineSelectionSetFactory.engineSelectionSet(selections, emptyMap()).objectCoords().toMutableSet()
        variablesResolvers.forEach { variablesResolver ->
            variablesResolver.requiredSelectionSet?.objectCoords(registry)?.let { coords.addAll(it) }
        }
        return coords
    }

    /**
     * Returns all field coordinates and field object types referenced by this [EngineSelectionSet].
     * All interface and union coordinates and types are replaced by coordinates of its object type implementations.
     */
    private fun EngineSelectionSet.objectCoords(): Set<TypeOrFieldCoordinate> =
        buildSet {
            selections().forEach {
                objectTypes(it.typeCondition).forEach { objectTypeName ->
                    if (!it.fieldName.startsWith("__")) {
                        add(objectTypeName to it.fieldName)
                    }
                }
            }
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
}
