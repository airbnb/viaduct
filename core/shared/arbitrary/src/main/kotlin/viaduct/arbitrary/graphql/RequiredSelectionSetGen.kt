package viaduct.arbitrary.graphql

import graphql.language.FragmentDefinition
import graphql.language.TypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime.select.allCoords
import viaduct.engine.runtime.select.reachableObjects
import viaduct.engine.runtime.tenantloading.RequiredSelectionSetGraph
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName

internal interface RequiredSelectionSetGen {
    fun gen(
        tfc: TypeOrFieldCoordinate,
        typeCondition: String,
        forChecker: Boolean,
        depth: Int
    ): RequiredSelectionSet?

    @VisibleForTest
    val graph: RequiredSelectionSetGraph

    companion object {
        operator fun invoke(env: ViaductGenEnv): RequiredSelectionSetGen = RequiredSelectionSetGenImpl(env)
    }
}

private class RequiredSelectionSetGenImpl(private val env: ViaductGenEnv) : RequiredSelectionSetGen {
    private val engineSelectionSetFactory = EngineSelectionSetFactoryImpl(env.schemas.viaductSchema)
    override val graph = RequiredSelectionSetGraph()

    init {
        // Pre-populate resolver and checker nodes for all field resolvers and object types
        // so that getBlockedCoordinates can detect cycles to nodes that haven't been generated yet.
        //
        // Without pre-population, edges to resolver/checker nodes only get established in
        // getAbstractEdges when the target node is already in resolverNodes/checkerNodes.
        // Since RSSes are generated sequentially, nodes later in the sequence would otherwise
        // be invisible to cycle detection for earlier nodes.
        env.resolverCoordinates.fieldResolvers.forEach { coord ->
            graph.addResolverNode(coord, emptySet())
            graph.addCheckerNode(coord, emptySet())
        }
        // Pre-populate ALL object coordinates as potential field checker nodes, not just field
        // resolvers. Checkers can be generated for any objectCoordinate (see FieldCheckerWeight
        // in Viaducts.kt), so non-resolver fields like Obj.id must be pre-populated to ensure
        // edges are established when resolver RSSes select them.
        env.schemas.viaductSchema.objectCoordinates
            .filter { it !in env.resolverCoordinates.fieldResolvers }
            .forEach { coord -> graph.addCheckerNode(coord, emptySet()) }
        env.schemas.schema.typeMap.values
            .filterIsInstance<GraphQLObjectType>()
            .forEach { type -> graph.addCheckerNode(type.name to null, emptySet()) }
    }

    override fun gen(
        tfc: TypeOrFieldCoordinate,
        typeCondition: String,
        forChecker: Boolean,
        depth: Int
    ): RequiredSelectionSet? {
        val cw = env.cfg[RequiredSelectionSetWeight]
        if (depth >= cw.max || !env.rs.sampleWeight(cw.weight)) return null

        val state = genDocumentState(tfc, typeCondition, forChecker)
        val selections = state.toParsedSelections(typeCondition)
        // Register the generated RSS in the graph before generating variable resolver RSSes,
        // so that cycle prevention applies transitively to any recursive calls below.
        val rawSs = engineSelectionSetFactory.engineSelectionSet(selections, emptyMap())
        val selectedCoords = buildSet<TypeOrFieldCoordinate> {
            addAll(rawSs.allCoords(env.schemas.viaductSchema))
            rawSs.reachableObjects(env.schemas.viaductSchema).forEach {
                    typeName ->
                add(typeName to null)
            }
        }
        if (forChecker) graph.addCheckerNode(tfc, selectedCoords) else graph.addResolverNode(tfc, selectedCoords)

        val variablesResolvers = state.variables.variables.map { vdef ->
            env.variablesResolverGen.gen(tfc, vdef, forChecker, depth + 1)
        }

        val rss = RequiredSelectionSet(
            selections = selections,
            variablesResolvers = variablesResolvers,
            forChecker = forChecker
        )

        return rss
    }

    private fun genDocumentState(
        tfc: TypeOrFieldCoordinate,
        typeCondition: String,
        forChecker: Boolean
    ): DocumentGenCtx {
        val state = DocumentGenCtx(
            env.schemas,
            env.schemas.schema.getTypeAs(typeCondition)
        )

        val blockedCoordinates = expandBlockedCoordinates(
            env.cfg[BanSelectionCoordinates] + graph.getBlockedCoordinates(tfc, forChecker)
        )
        val docGenEnv = DocumentGenEnv(
            env.schemas,
            env.cfg + (BanSelectionCoordinates to blockedCoordinates),
            env.rs
        )

        // apply the generator to the state
        docGenEnv.selectionSetGen.gen(state)

        // return the mutated state
        return state
    }

    /**
     * Expand a ban set so that selections resolving to a banned semantic coordinate are also banned
     * from the syntactic contexts where the document generator chooses fields.
     */
    private fun expandBlockedCoordinates(blocked: Set<TypeOrFieldCoordinate>): Set<TypeOrFieldCoordinate> =
        blocked +
            expandBlockedFieldCoordinates(blocked) +
            expandBlockedTypeCoordinates(blocked)

    private fun expandBlockedFieldCoordinates(blocked: Set<TypeOrFieldCoordinate>): Set<TypeOrFieldCoordinate> =
        blocked.flatMap { (typeName, fieldName) ->
            if (fieldName == null) return@flatMap emptyList()
            val obj = env.schemas.schema.getType(typeName) as? GraphQLObjectType
                ?: return@flatMap emptyList()
            // Unions don't declare fields, so only interfaces need expansion.
            env.schemas.rels.spreadableTypes(obj)
                .filterIsInstance<GraphQLInterfaceType>()
                .filter { it.getFieldDefinition(fieldName) != null }
                .map { it.name to fieldName }
        }
            .toSet()

    private fun expandBlockedTypeCoordinates(blocked: Set<TypeOrFieldCoordinate>): Set<TypeOrFieldCoordinate> {
        val blockedObjectTypes = blocked
            .filter { (_, fieldName) -> fieldName == null }
            .mapNotNull { (typeName, _) ->
                (env.schemas.schema.getType(typeName) as? GraphQLObjectType)?.name
            }
            .toSet()
        if (blockedObjectTypes.isEmpty()) return emptySet()

        return env.schemas.schema.typeMap.values
            .filterIsInstance<GraphQLImplementingType>()
            .flatMap { type ->
                type.fields
                    .filter { field ->
                        val fieldType = GraphQLTypeUtil.unwrapAll(field.type)
                        fieldType is GraphQLCompositeType &&
                            env.schemas.rels.possibleObjectTypes(fieldType).any { it.name in blockedObjectTypes }
                    }
                    .map { field -> type.name to field.name }
            }
            .toSet()
    }

    private fun DocumentGenCtx.toParsedSelections(typeCondition: String): ParsedSelections {
        // DocumentGenCtx describes a naked selection set (state.sb) and a list of fragment definitions.
        // Repackage these naked selection set into a "Main" fragment that the engine knows how to execute
        val fragmentDefs = fragments.fragments.map { it.def }
        val mainFragment = FragmentDefinition.newFragmentDefinition()
            .name(EntryPointFragmentName)
            .typeCondition(TypeName(typeCondition))
            .selectionSet(sb.build())
            .build()

        val allFragments = (fragmentDefs + mainFragment).associateBy { it.name }

        return ParsedSelections(
            typeCondition,
            sb.build(),
            allFragments
        )
    }
}
