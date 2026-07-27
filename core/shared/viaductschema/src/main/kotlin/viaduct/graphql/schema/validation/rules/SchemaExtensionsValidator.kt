package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.SchemaValidator

/**
 * Schema validator for application-level schema extension files (schemabase + common),
 * validated in isolation without module partitions.
 *
 * Applies all standard Viaduct rules except those that require module partition context:
 * - [ApplicationOnlyDefinitionsRule] is excluded: it flags directives/scalars not in partitions,
 *   but the extensions context has no partitions by definition.
 * - [NoCrossModuleInputExtensionsRule] is excluded: no partition paths exist to evaluate.
 * - [CrossModuleExtensionFieldsResolverRule] is excluded: no partition paths exist to evaluate.
 * - [PageInfoLocationRule] is excluded: PageInfo in schemabase is the correct location.
 */
object SchemaExtensionsValidator {
    private val allowedScalarNames = GraphQLBuiltIns.SCALARS + GraphQLBuiltIns.VIADUCT_SCALARS

    private val validator = SchemaValidator(
        phases = listOf(
            listOf(
                NoSubscriptionsRule(),
                NoCustomScalarsRule(allowedScalarNames),
                BackingDataFieldsRule(),
                IdOfTypeValidationRule(),
                NamespaceTypeConstraintsRule(),
                FieldArgumentsRequireResolverRule(),
                ConnectionTypeStructureRule(),
                ConnectionEdgeStructureRule(),
                ConnectionPageInfoRule(),
                StrictConnectionPageInfoRule(),
                ConnectionArgumentsNullabilityRule(),
                StructuralDirectivesOnBaseTypeRule(),
                NoResolverOnInterfaceFieldsRule(),
                NodeInterfaceIdConsistencyRule(),
            )
        )
    )

    fun validate(schema: ViaductSchema): List<SchemaValidationError> = validator.validate(schema)
}
