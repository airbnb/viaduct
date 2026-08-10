package viaduct.arbitrary.graphql

import graphql.language.FragmentDefinition
import graphql.language.TypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.RequiredSelectionSet
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName

internal interface RequiredSelectionSetGen {
    fun gen(
        tfc: TypeOrFieldCoordinate,
        typeCondition: String,
        forChecker: Boolean,
        depth: Int
    ): RequiredSelectionSet?

    companion object {
        operator fun invoke(env: ViaductGenEnv): RequiredSelectionSetGen = RequiredSelectionSetGenImpl(env)
    }
}

private class RequiredSelectionSetGenImpl(private val env: ViaductGenEnv) : RequiredSelectionSetGen {
    override fun gen(
        tfc: TypeOrFieldCoordinate,
        typeCondition: String,
        forChecker: Boolean,
        depth: Int
    ): RequiredSelectionSet? {
        val cw = env.cfg[RequiredSelectionSetWeight]
        if (depth >= cw.max || !env.rs.sampleWeight(cw.weight)) return null

        val state = genDocumentState(tfc, typeCondition) ?: return null
        val selections = state.toParsedSelections(typeCondition)

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
        typeCondition: String
    ): DocumentGenCtx? {
        val state = DocumentGenCtx(
            env.schemas,
            env.schemas.schema.getTypeAs(typeCondition)
        )

        val blockedCoordinates = expandBlockedCoordinates(
            env.cfg[BanSelectionCoordinates] +
                // Block selections whose index is greater than or equal to the current coordinate.
                // This ensures that RSS's always form a DAG and do not create cycles.
                // ResolverValueGen uses a similar check to keep the combined graph acyclic.
                env.coordinateIndex.after(tfc) +
                tfc
        )
        val cfg = env.cfg + (BanSelectionCoordinates to blockedCoordinates)

        if (state.selectableFields(cfg).isEmpty()) {
            return null
        }

        val docGenEnv = DocumentGenEnv(
            env.schemas,
            cfg,
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
