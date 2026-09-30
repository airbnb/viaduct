package model.testing

import model.Arguments
import model.EngineInputData
import model.Fragment
import model.ObjectEngineResult
import model.SourceSchemaAdapter
import model.isParentField
import model.parsing.GJSelectionParser
import model.registry.ProviderFragment
import model.registry.ResolverTarget
import model.requireField
import model.requireQueryTypeDef
import model.spec.SpecSelection
import model.spec.flattenForMaterialization
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.GraphQLTypeRelation

/** One external from-field declaration compiled for canonical registry construction. */
class FromField private constructor(
    val responsePath: List<String>,
    internal val providerFragment: ProviderFragment,
    internal val fragment: Fragment,
    internal val keyPath: List<ObjectEngineResult.Key>,
    internal val terminalType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    internal val nullableTraversal: Boolean,
) : VariableDeclaration {
    internal fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): FromField =
        FromField(
            responsePath = responsePath,
            providerFragment = providerFragment,
            fragment = fragment.mapVariables(transform),
            keyPath = keyPath.mapVariables(transform),
            terminalType = terminalType,
            nullableTraversal = nullableTraversal,
        )

    internal fun isCompatibleWith(
        locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
        locationHasDefault: Boolean,
    ): Boolean =
        compatibleTypes(
            locationType = locationType,
            sourceType = terminalType.asInputType(),
            nullableTraversal = nullableTraversal,
            locationHasDefault = locationHasDefault,
        )

    internal fun isCompatibleWithInclusionCondition(): Boolean = terminalType.isCompatibleWithInclusionCondition(nullableTraversal)

    companion object {
        internal fun compile(
            schema: ViaductAndGJSchema,
            fragmentSource: String,
            responsePath: List<String>,
            variableField: ViaductSchema.ObjectField?,
            bindings: Map<String, EngineInputData?>,
            providerFragment: ProviderFragment,
        ): FromField {
            val sourceName =
                when (providerFragment) {
                    ProviderFragment.OBJECT -> "fromObjectField"
                    ProviderFragment.QUERY -> "fromQueryField"
                }
            require(responsePath.isNotEmpty()) {
                "$sourceName path must contain at least one response key"
            }
            require(responsePath.none(String::isBlank)) {
                "$sourceName path cannot contain a blank response key"
            }
            val parsed =
                GJSelectionParser(
                    sourceSchema = schema.graphQLSchema,
                    schema = schema.loweredSchema,
                    variableValues = bindings,
                    variableTarget =
                        variableField?.let(ResolverTarget::FieldValueResolverTarget),
                ).specSelectionsFrom(fragmentSource)
            if (providerFragment == ProviderFragment.QUERY) {
                require(parsed.nominalType == schema.loweredSchema.requireQueryTypeDef()) {
                    "fromQueryField provider fragment must be rooted at Query"
                }
            }
            val path =
                schema.compilePath(
                    typeInScope = parsed.nominalType,
                    selections = parsed.selections,
                    responsePath = responsePath,
                )
            return FromField(
                responsePath = responsePath,
                providerFragment = providerFragment,
                fragment =
                    Fragment.of(
                        nominalType = parsed.nominalType,
                        materializeSelections =
                            flattenForMaterialization(
                                schema.loweredSchema,
                                parsed.nominalType,
                                parsed.selections,
                            ),
                    ),
                keyPath = path.keys,
                terminalType = path.terminalType,
                nullableTraversal = path.nullableTraversal,
            )
        }
    }
}

/** Compiles a production-shaped response-key path against an alias-preserving object fragment. */
fun ViaductAndGJSchema.fromObjectField(
    objectFragmentSource: String,
    responsePath: List<String>,
    variableField: ViaductSchema.ObjectField? = null,
    bindings: Map<String, EngineInputData?> = emptyMap(),
): FromField =
    FromField.compile(
        schema = this,
        fragmentSource = objectFragmentSource,
        responsePath = responsePath,
        variableField = variableField,
        bindings = bindings,
        providerFragment = ProviderFragment.OBJECT,
    )

/** Compiles a production-shaped response-key path against an alias-preserving Query fragment. */
fun ViaductAndGJSchema.fromQueryField(
    queryFragmentSource: String,
    responsePath: List<String>,
    variableField: ViaductSchema.ObjectField? = null,
    bindings: Map<String, EngineInputData?> = emptyMap(),
): FromField =
    FromField.compile(
        schema = this,
        fragmentSource = queryFragmentSource,
        responsePath = responsePath,
        variableField = variableField,
        bindings = bindings,
        providerFragment = ProviderFragment.QUERY,
    )

private data class CompiledPath(
    val keys: List<ObjectEngineResult.Key>,
    val terminalType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    val nullableTraversal: Boolean,
)

private data class MatchingField(
    val keys: List<ObjectEngineResult.Key>,
    val typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    val subselections: List<SpecSelection>,
    val lossyCondition: Pair<ViaductSchema.CompositeTypeDef, ViaductSchema.CompositeTypeDef>?,
)

private fun ViaductAndGJSchema.compilePath(
    typeInScope: ViaductSchema.CompositeTypeDef,
    selections: List<SpecSelection>,
    responsePath: List<String>,
    index: Int = 0,
    keys: List<ObjectEngineResult.Key> = emptyList(),
    nullableTraversal: Boolean = false,
): CompiledPath {
    val responseKey = responsePath[index]
    val matches =
        matchingFields(
            selections = selections,
            typeInScope = typeInScope,
            responseKey = responseKey,
        )
    require(matches.isNotEmpty()) {
        "from-field path ${responsePath.joinToString(".")} has no selection for response " +
            "key $responseKey"
    }

    matches.firstNotNullOfOrNull(MatchingField::lossyCondition)?.let { (from, to) ->
        throw IllegalArgumentException(
            "from-field path ${responsePath.joinToString(".")} traverses lossy type " +
                "condition ${from.name} to ${to.name}",
        )
    }

    val distinctKeys = matches.map(MatchingField::keys).distinct()
    require(distinctKeys.size == 1) {
        "from-field response key $responseKey does not identify one canonical field and " +
            "argument tuple"
    }
    val matchedKeys = distinctKeys.single()
    val key = matchedKeys.first()
    val distinctTypes = matches.map(MatchingField::typeExpr).distinct()
    require(distinctTypes.size == 1) {
        "from-field response key $responseKey does not identify one output type"
    }
    val typeExpr = distinctTypes.single()
    val isTerminal = index == responsePath.lastIndex

    if (isTerminal) {
        require(typeExpr.baseTypeDef is ViaductSchema.SimpleTypeDef) {
            "from-field path ${responsePath.joinToString(".")} must terminate at a scalar " +
                "or enum"
        }
        return CompiledPath(
            keys = keys + matchedKeys,
            terminalType = typeExpr,
            nullableTraversal = nullableTraversal,
        )
    }

    require(!typeExpr.isList && typeExpr.baseTypeDef is ViaductSchema.CompositeTypeDef) {
        "from-field path ${responsePath.joinToString(".")} cannot traverse list or simple " +
            "field ${key.field.containingDef.name}/${key.field.name}"
    }
    return compilePath(
        typeInScope = typeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef,
        selections = matches.flatMap(MatchingField::subselections),
        responsePath = responsePath,
        index = index + 1,
        keys = keys + matchedKeys,
        nullableTraversal =
            nullableTraversal ||
                (typeExpr.isNullable && !key.field.isParentField()),
    )
}

private fun ViaductAndGJSchema.matchingFields(
    selections: List<SpecSelection>,
    typeInScope: ViaductSchema.CompositeTypeDef,
    responseKey: String,
    lossyCondition: Pair<ViaductSchema.CompositeTypeDef, ViaductSchema.CompositeTypeDef>? = null,
): List<MatchingField> =
    selections.flatMap { selection ->
        when (selection) {
            is SpecSelection.Field -> {
                if ((selection.alias ?: selection.fieldName) != responseKey) {
                    emptyList()
                } else {
                    val field = loweredSchema.requireField(typeInScope.name, selection.fieldName)
                    listOf(
                        MatchingField(
                            keys = listOf(ObjectEngineResult.Key.of(field, selection.arguments)),
                            typeExpr = SourceSchemaAdapter(loweredSchema).typeExpr(field),
                            subselections = selection.subselections.orEmpty(),
                            lossyCondition = lossyCondition,
                        ),
                    )
                }
            }

            is SpecSelection.InlineFragment -> {
                val condition = selection.typeCondition
                val relation =
                    condition?.let {
                        typeRelations.relationUnwrapped(
                            sourceCompositeType(typeInScope),
                            sourceCompositeType(it),
                        )
                    }
                val nextLossyCondition =
                    lossyCondition
                        ?: condition
                            ?.takeIf {
                                relation in
                                    setOf(
                                        GraphQLTypeRelation.WiderThan,
                                        GraphQLTypeRelation.Coparent,
                                    )
                            }?.let { typeInScope to it }
                matchingFields(
                    selections = selection.selections,
                    typeInScope = condition ?: typeInScope,
                    responseKey = responseKey,
                    lossyCondition = nextLossyCondition,
                )
            }
        }
    }

@Suppress("UNCHECKED_CAST")
private fun ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>.asInputType(): ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef> {
    require(baseTypeDef is ViaductSchema.InputTypeDef)
    return this as ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>
}

internal tailrec fun compatibleTypes(
    locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    sourceType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    nullableTraversal: Boolean,
    locationHasDefault: Boolean,
): Boolean {
    val sourceEffectivelyNullable = nullableTraversal || sourceType.isNullable
    return when {
        locationHasDefault && sourceEffectivelyNullable ->
            compatibleTypes(
                locationType = locationType.withNullable(true),
                sourceType = sourceType,
                nullableTraversal = nullableTraversal,
                locationHasDefault = false,
            )

        !locationType.isNullable -> {
            val unwrappedLocation = locationType.withNullable(true)
            val unwrappedSource = sourceType.withNullable(true)
            if (sourceEffectivelyNullable) {
                false
            } else {
                compatibleTypes(
                    locationType = unwrappedLocation,
                    sourceType = unwrappedSource,
                    nullableTraversal = nullableTraversal,
                    locationHasDefault = false,
                )
            }
        }

        locationType.isList -> {
            val locationElement = checkNotNull(locationType.unwrapList())
            val sourceElement =
                sourceType.unwrapList()
                    ?: sourceType.withNullable(false)
            compatibleTypes(
                locationType = locationElement,
                sourceType = sourceElement,
                nullableTraversal = false,
                locationHasDefault = false,
            )
        }

        else ->
            !sourceType.isList &&
                locationType.baseTypeDef == sourceType.baseTypeDef
    }
}

private fun <T : ViaductSchema.TypeDef> ViaductSchema.TypeExpr<T>.withNullable(nullable: Boolean): ViaductSchema.TypeExpr<T> {
    return if (!isList) {
        ViaductSchema.TypeExpr(baseTypeDef, nullable)
    } else {
        val wrappers = listNullable.copy()
        if (nullable) wrappers.set(0) else wrappers.clear(0)
        ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, wrappers)
    }
}
