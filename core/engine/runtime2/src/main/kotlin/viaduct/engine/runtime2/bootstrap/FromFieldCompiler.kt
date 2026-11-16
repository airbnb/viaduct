package viaduct.engine.runtime2.bootstrap

import graphql.normalized.NormalizedField
import graphql.normalized.NormalizedSelectionScope
import graphql.parser.Parser
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.registry.FromField
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.schema.ParsedSelectionFragment
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.fragmentFromDocument
import viaduct.engine.runtime2.schema.sourceCompositeType
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.GraphQLTypeRelation

internal fun compileFromField(
    schema: ViaductAndGJSchema,
    fragmentSource: String,
    responsePath: List<String>,
    variableField: ViaductSchema.ObjectField?,
    bindings: Map<String, EngineInputData?>,
    providerFragment: ProviderFragment,
): FromField =
    compileFromField(
        schema = schema,
        parsed =
            schema.fragmentFromDocument(
                document = Parser.parse(fragmentSource),
                bindings = bindings,
                variableField = variableField,
            ),
        responsePath = responsePath,
        providerFragment = providerFragment,
    )

internal fun compileFromField(
    schema: ViaductAndGJSchema,
    parsed: ParsedSelectionFragment,
    responsePath: List<String>,
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
    if (providerFragment == ProviderFragment.QUERY) {
        require(parsed.nominalType == schema.loweredSchema.requireQueryTypeDef()) {
            "fromQueryField provider fragment must be rooted at Query"
        }
    }
    val path =
        schema.compilePath(
            typeInScope = parsed.nominalType,
            parsed = parsed,
            selections = listOf(parsed.rootId),
            responsePath = responsePath,
        )
    return FromField.ofCompiled(
        responsePath = responsePath,
        providerFragment = providerFragment,
        fragment = parsed.fragment,
        keyPath = path.keys,
        terminalType = path.terminalType,
        nullableTraversal = path.nullableTraversal,
    )
}

/** Compiles a production-shaped response-key path against an alias-preserving object fragment. */
fun ViaductAndGJSchema.fromObjectField(
    objectFragmentSource: String,
    responsePath: List<String>,
    variableField: ViaductSchema.ObjectField? = null,
    bindings: Map<String, EngineInputData?> = emptyMap(),
): FromField =
    compileFromField(
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
    compileFromField(
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
    val subselections: List<Int>,
    val lossyCondition: Pair<ViaductSchema.CompositeTypeDef, ViaductSchema.CompositeTypeDef>?,
)

private fun ViaductAndGJSchema.compilePath(
    typeInScope: ViaductSchema.CompositeTypeDef,
    parsed: ParsedSelectionFragment,
    selections: List<Int>,
    responsePath: List<String>,
    index: Int = 0,
    keys: List<ObjectEngineResult.Key> = emptyList(),
    nullableTraversal: Boolean = false,
): CompiledPath {
    val responseKey = responsePath[index]
    val matches =
        matchingFields(
            parsed = parsed,
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
        parsed = parsed,
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
    parsed: ParsedSelectionFragment,
    selections: List<Int>,
    typeInScope: ViaductSchema.CompositeTypeDef,
    responseKey: String,
    lossyCondition: Pair<ViaductSchema.CompositeTypeDef, ViaductSchema.CompositeTypeDef>? = null,
): List<MatchingField> =
    selections.flatMap { id -> if (id < 0) emptyList() else parsed.document.selectionSets[id].selections }.flatMap { selection ->
        when (selection) {
            is NormalizedField -> {
                if (selection.source.resultKey != responseKey) {
                    emptyList()
                } else {
                    val key = parsed.source.key(selection)
                    val field = key.field
                    listOf(
                        MatchingField(
                            keys = listOf(key),
                            typeExpr = SourceSchemaAdapter(loweredSchema).typeExpr(field),
                            subselections = listOf(selection.selectionSetId),
                            lossyCondition = lossyCondition,
                        ),
                    )
                }
            }

            is NormalizedSelectionScope -> {
                val condition = selection.typeCondition?.let { loweredSchema.types[it] as ViaductSchema.CompositeTypeDef }
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
                    parsed = parsed,
                    selections = listOf(selection.selectionSetId),
                    typeInScope = condition ?: typeInScope,
                    responseKey = responseKey,
                    lossyCondition = nextLossyCondition,
                )
            }
            else -> error("Unexpected normalized selection")
        }
    }
