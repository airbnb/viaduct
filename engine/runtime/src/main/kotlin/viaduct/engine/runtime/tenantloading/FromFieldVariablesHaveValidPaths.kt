package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.Validated
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl
import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.GraphQLTypeRelation
import viaduct.graphql.utils.VariableUsageInfo
import viaduct.graphql.utils.collectAllVariableUsages

class FromFieldVariablesHaveValidPaths(
    private val schema: ViaductSchema
) : Validator<RequiredSelectionsValidationCtx> {
    private val rawSelectionSetFactory = RawSelectionSetFactoryImpl(schema)

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        // For the supplied coordinate, we want to look up all of its RequiredSelectionSets and validate
        // every FromFieldVariablesResolver therein.
        //
        // This is complicated by a coordinate being able to have multiple RequiredSelectionSets
        // and being able to use a FromField variable in one RSS that refers to a path selected in another.
        //
        // To manage this, we:
        //   1. Create a mapping of all FromField variable resolvers: "variableName" -> FromFieldVariablesResolver
        //   1. Create a mapping of all variable sources: "variableName" -> RequiredSelectionSet
        //   2. Create a mapping of all variable sinks: "variableName" -> List<VariableUsageInfo>
        //   3. For each variable sink, lookup its source and validate that the source and sink are compatible
        val allSets = if (ctx.fieldName != null) {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForField(ctx.typeName, ctx.fieldName, true)
        } else {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForType(ctx.typeName, true)
        }
        val coord = ctx.typeName to ctx.fieldName

        val variableResolversByName = mutableMapOf<String, FromFieldVariablesResolver>()
        val variableSourceByName = mutableMapOf<String, RequiredSelectionSet>()

        allSets.forEach { rss ->
            rss.variablesResolvers
                .mapNotNull(::extractFromFieldVariables)
                .forEach { vr ->
                    variableResolversByName[vr.name] = vr

                    // a variable's data source may be selected in a different RSS, find it
                    val setsThatSelectPath = allSets.filter { rss ->
                        rss.selections.filterToPath(vr.path) != null
                    }
                    when (setsThatSelectPath.size) {
                        1 -> variableSourceByName[vr.name] = setsThatSelectPath[0]
                        0 -> throw InvalidVariableException(
                            coord,
                            vr.name,
                            "No source found for variable path ${vr.path}"
                        )
                        else -> throw InvalidVariableException(
                            coord,
                            vr.name,
                            "Ambiguous source: multiple selection sets provide value for variable path ${vr.path}"
                        )
                    }
                }
        }

        val variableSinksByName: Map<String, List<VariableUsageInfo>> = allSets.flatMap { rss ->
            try {
                rss.selections.selections
                    .collectAllVariableUsages(schema.schema, rss.selections.typeName, rss.selections.fragmentMap)
                    .toList()
                    .flatMap { (name, usages) ->
                        if (name in variableSourceByName) {
                            usages.map { usage -> name to usage }
                        } else {
                            emptyList()
                        }
                    }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Variable usage collection failed for RSS with attribution ${rss.attribution}",
                    e
                )
            }
        }.groupBy({ it.first }, { it.second })

        variableSinksByName.forEach { (name, usages) ->
            val source = checkNotNull(variableSourceByName[name])
            val sourceRawSelections = rawSelectionSetFactory.rawSelectionSet(source.selections, emptyMap())
            val selectionPath = checkNotNull(variableResolversByName[name]).path

            for (usage in usages) {
                validateFromFieldVariable(
                    coord = coord,
                    variableName = name,
                    selections = sourceRawSelections,
                    selectionPath = selectionPath,
                    usage = usage
                )
            }
        }
    }

    private fun extractFromFieldVariables(resolver: VariablesResolver): FromFieldVariablesResolver? =
        when (resolver) {
            is Validated -> extractFromFieldVariables(resolver.delegate)
            is FromFieldVariablesResolver -> resolver
            else -> null
        }

    private fun validateFromFieldVariable(
        coord: TypeOrFieldCoordinate,
        variableName: String,
        selections: RawSelectionSet,
        selectionPath: List<String>,
        usage: VariableUsageInfo
    ) {
        val locationType = Type(usage)
        val variableType = buildVariableType(
            coord,
            variableName,
            currentType = Type(
                // wrap current type in NonNull, since we will always have an instance of it
                // when we resolve variables
                GraphQLNonNull.nonNull(schema.schema.getTypeAs(selections.type))
            ),
            selections = selections,
            selectionPath,
        )

        if (!areTypesCompatible(locationType, variableType)) {
            throw InvalidVariableException(
                coord,
                variableName,
                "Types not compatible resolvedType $variableType cannot be applied to location $usage at location $selectionPath"
            )
        }
    }

    /**
     * Build a [Type] that describes the effective type of the variable at [selectionPath].
     * A key part of this is that the effective nullability of Type depends on the selections in selectionPath,
     * which will ultimately affect the nullability of the produced variable.
     */
    private tailrec fun buildVariableType(
        coord: TypeOrFieldCoordinate,
        variableName: String,
        currentType: Type,
        selections: RawSelectionSet,
        selectionPath: List<String>,
        selectionPathIndex: Int = 0
    ): Type {
        val isTerminal = selectionPathIndex == selectionPath.size - 1

        val segment = selectionPath[selectionPathIndex]
        val selection = selections.selections()
            .firstOrNull { it.selectionName == segment }
            ?: throw InvalidVariableException(coord, variableName, "No selection found for $segment in path $selectionPath")

        val selectionType = schema.schema.getFieldDefinition(selection.coord.gj).type
        if (isTerminal) {
            val unwrappedSelectionType = GraphQLTypeUtil.unwrapAll(selectionType)
            if (unwrappedSelectionType !is GraphQLScalarType && unwrappedSelectionType !is GraphQLEnumType) {
                throw InvalidVariableException(coord, variableName, "Path $selectionPath must terminate on a scalar or enum type")
            }
        } else if (selectionType.isListish) {
            throw InvalidVariableException(coord, variableName, "Cannot traverse list type at coordinate ${selection.coord} for variable path $selectionPath")
        }

        // if this selection has a type condition that is narrower than the containing selection set type, then
        // the variable may become null if the runtime type does not match our type condition
        // For simplicity, reject traversals through lossy type conditions.
        when (selections.relation(schema, selection)) {
            GraphQLTypeRelation.Coparent,
            GraphQLTypeRelation.WiderThan ->
                throw InvalidVariableException(
                    coord,
                    variableName,
                    "Cannot traverse a lossy type condition: `${selections.type}` to `${selection.typeCondition}`"
                )
            else -> {}
        }

        val newEffectivelyNullable = currentType.effectivelyNullable || GraphQLTypeUtil.isNullable(selectionType)
        val newResolvedType = Type(selectionType).let {
            if (newEffectivelyNullable) {
                it + Type.Property.NullableTraversalPath
            } else {
                it
            }
        }
        return if (isTerminal) {
            newResolvedType
        } else {
            buildVariableType(
                coord,
                variableName,
                currentType = newResolvedType,
                selections = selections.selectionSetForSelection(selection.typeCondition, selection.selectionName),
                selectionPath = selectionPath,
                selectionPathIndex = selectionPathIndex + 1
            )
        }
    }
}
