package viaduct.engine.runtime2.model.testing

import graphql.language.FragmentDefinition
import graphql.language.Node
import graphql.language.VariableReference
import graphql.parser.Parser
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.materializeSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

fun ViaductAndGJSchema.fragmentFrom(
    source: String,
    bindings: Map<String, EngineInputData?> = emptyMap(),
    variableField: ViaductSchema.ObjectField? = null,
    variableTarget: ResolverTarget? = null,
    preserveSourceResponseKeys: Boolean = false,
): Fragment {
    val (nominalType, selections) =
        materializeSelectionsFrom(
            source = source,
            bindings = bindings,
            variableField = variableField,
            variableTarget = variableTarget ?: variableField?.let(ResolverTarget::FieldValueResolverTarget) ?: fixtureVariableTarget(source, bindings),
            preserveSourceResponseKeys = preserveSourceResponseKeys,
        )
    return Fragment.of(
        nominalType = nominalType,
        materializeSelections = selections,
    )
}

private fun ViaductAndGJSchema.fixtureVariableTarget(
    source: String,
    bindings: Map<String, EngineInputData?>,
): ResolverTarget? {
    val document = Parser.parse(source)
    if (!document.hasUnboundVariable(bindings)) return null
    val definition = document.definitions.singleOrNull() as? FragmentDefinition
        ?: throw IllegalArgumentException("Expected exactly one named fragment definition")
    val type = loweredSchema.requireType(definition.typeCondition.name) as ViaductSchema.CompositeTypeDef
    return ResolverTarget.FieldValueResolverTarget(type.possibleObjectTypes.first().fields.first())
}

private fun Node<*>.hasUnboundVariable(bindings: Map<String, EngineInputData?>): Boolean = (this is VariableReference && name !in bindings) || children.any { it.hasUnboundVariable(bindings) }

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun Assumptions.emptyFragmentOf(typeName: String): Fragment = schema.emptyFragmentOf(typeName)
