package execution.testing

import graphql.GraphQLContext
import graphql.execution.ValuesResolver
import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.schema.InputValueWithState
import java.util.Locale
import model.Arguments
import model.InclusionCondition
import model.Selection
import model.SelectionForest
import model.lowering.SourceSchemaAdapter
import model.objectKey
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.graphql.schema.ViaductSchema as QPlanSchema
import viaduct.graphql.utils.ParsedSelections

/**
 * Exposes qplan output demand through the Engine API selection-set surface.
 *
 * [SelectionForest] is already a flattened semantic representation: it retains concrete
 * [Selection.possibleTypes] but not the inline-fragment nesting that produced those types. This
 * adapter therefore renders a canonical GraphQL-Java form with one inline fragment per applicable
 * concrete object type and lets the existing Engine API implementation provide its convenience
 * operations.
 *
 * This conversion preserves field coordinates, concrete applicability, nested demand, resolved
 * arguments, and statically excluded result keys. It cannot recover source aliases, named
 * fragments, or directive spelling.
 */
internal fun SelectionForest.toEngineSelectionSet(
    type: QPlanSchema.CompositeTypeDef,
    schema: EngineSchema,
    sourceSchema: SourceSchemaAdapter,
): EngineSelectionSet {
    require(schema.schema.getType(type.name) != null) {
        "Qplan selection type ${type.name} is absent from the Engine schema"
    }
    return createEngineSelectionSet(
        parsedSelections =
            ParsedSelections(
                typeName = type.name,
                selections = toConcreteSelectionSet(schema, sourceSchema),
                fragmentMap = emptyMap(),
            ),
        viaductSchema = schema,
        variables = emptyMap(),
    )
}

private fun SelectionForest.toConcreteSelectionSet(
    schema: EngineSchema,
    sourceSchema: SourceSchemaAdapter,
): SelectionSet {
    val fieldsByConcreteType = linkedMapOf<String, MutableList<Field>>()
    forEach { selection ->
        selection.possibleTypes
            .sortedBy(QPlanSchema.Object::name)
            .forEach { concreteType ->
                fieldsByConcreteType
                    .getOrPut(concreteType.name, ::mutableListOf)
                    .add(selection.toField(concreteType, schema, sourceSchema))
            }
    }

    val fragments =
        fieldsByConcreteType
            .toSortedMap()
            .map { (typeName, fields) ->
                InlineFragment.newInlineFragment()
                    .typeCondition(TypeName(typeName))
                    .selectionSet(
                        SelectionSet(
                            fields.sortedBy(AstPrinter::printAstCompact),
                        ),
                    ).build()
            }
    return SelectionSet(fragments)
}

private fun Selection.toField(
    concreteType: QPlanSchema.Object,
    schema: EngineSchema,
    sourceSchema: SourceSchemaAdapter,
): Field {
    val loweredFieldName = key.field.name
    val concreteField = objectKey(concreteType).field
    val sourceObject = requireNotNull(schema.schema.getObjectType(concreteType.name))
    val sourceField =
        sourceObject.fieldDefinitions.singleOrNull { candidate ->
            sourceSchema.field(concreteType.name, candidate.name) == concreteField
        }
    val fieldName =
        if (loweredFieldName == LOWERED_TYPENAME_FIELD) {
            "__typename"
        } else {
            requireNotNull(sourceField) {
                "Qplan field ${concreteType.name}.$loweredFieldName is absent from the Engine schema"
            }.name
        }
    val arguments =
        key.arguments as? Arguments.Resolved
            ?: throw IllegalArgumentException(
                "EngineSelectionSet demand requires resolved arguments for " +
                    "${key.field.containingDef.name}.$loweredFieldName",
            )
    val field = Field.newField(fieldName)
    if (inclusionCondition === InclusionCondition.Never) {
        field.directive(
            Directive.newDirective()
                .name("skip")
                .argument(
                    Argument.newArgument()
                        .name("if")
                        .value(BooleanValue.newBooleanValue(true).build())
                        .build(),
                ).build(),
        )
    }
    if (fieldName == "__typename") {
        require(arguments.fieldValues.isEmpty()) {
            "Lowered __typename demand must be argumentless"
        }
        return field.build()
    }
    requireNotNull(sourceField)
    field.arguments(
        arguments.fieldValues
            .toSortedMap()
            .map { (name, value) ->
                val sourceArgument =
                    sourceField.getArgument(name)
                        ?: throw IllegalArgumentException(
                            "Qplan argument ${concreteType.name}.$fieldName($name:) " +
                                "is absent from the Engine schema",
                        )
                Argument.newArgument()
                    .name(name)
                    .value(
                        ValuesResolver.valueToLiteral(
                            InputValueWithState.newInternalValue(value),
                            sourceArgument.type,
                            GraphQLContext.getDefault(),
                            Locale.getDefault(),
                        ),
                    ).build()
            },
    )
    val children = subselections.toConcreteSelectionSet(schema, sourceSchema)
    if (children.selections.isNotEmpty()) {
        field.selectionSet(children)
    }
    return field.build()
}

private const val LOWERED_TYPENAME_FIELD = "V_A_typename"
