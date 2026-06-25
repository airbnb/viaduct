package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.SchemaValidator

/**
 * Default schema validator with all standard Viaduct rules.
 *
 * Bundles the following rules into a single validation phase:
 * - [NoSubscriptionsRule]: Disallows subscription type definitions
 * - [NoCustomScalarsRule]: Only allows built-in GraphQL scalars
 * - [ApplicationOnlyDefinitionsRule]: Directives and scalars must be defined at application level
 * - [BackingDataFieldsRule]: BackingData type and @backingData directive must be used together
 * - [IdOfTypeValidationRule]: @idOf type parameter must reference an existing Node type
 * - [NamespaceTypeConstraintsRule]: @namespaceType types must have no-arg, non-list fields and a single parent
 * - [FieldArgumentsRequireResolverRule]: Object fields with arguments must have @resolver
 * - [ConnectionTypeStructureRule]: @connection types must have a valid 'edges' field and a non-null 'pageInfo' field
 * - [ConnectionEdgeStructureRule]: @edge types must have a 'node' field
 * - [ConnectionPageInfoRule]: PageInfo types must have hasNextPage/hasPreviousPage as Boolean! and nullable cursors
 * - [ConnectionArgumentsNullabilityRule]: Pagination args (first, after, last, before) must be nullable
 * - [NoCrossModuleInputExtensionsRule]: Input types (enum, input) may not be extended across module partitions
 * - [StructuralDirectivesOnBaseTypeRule]: @connection, @edge, @namespaceType must be on the base type definition
 * - [CrossModuleExtensionFieldsResolverRule]: Fields added by cross-module extend type must have @resolver
 * - [NoResolverOnInterfaceFieldsRule]: Interface fields cannot declare @resolver
 */
object DefaultSchemaValidator {
    private val allowedScalarNames = GraphQLBuiltIns.SCALARS + GraphQLBuiltIns.VIADUCT_SCALARS
    private val modulePartitionPathPrefix = "partition/"

    private val validator = SchemaValidator(
        phases = listOf(
            listOf(
                NoSubscriptionsRule(),
                NoCustomScalarsRule(allowedScalarNames),
                ApplicationOnlyDefinitionsRule(modulePartitionPathPrefix),
                BackingDataFieldsRule(),
                IdOfTypeValidationRule(),
                NamespaceTypeConstraintsRule(),
                FieldArgumentsRequireResolverRule(),
                ConnectionTypeStructureRule(),
                ConnectionEdgeStructureRule(),
                ConnectionPageInfoRule(),
                ConnectionArgumentsNullabilityRule(),
                NoCrossModuleInputExtensionsRule(modulePartitionPathPrefix),
                StructuralDirectivesOnBaseTypeRule(),
                CrossModuleExtensionFieldsResolverRule(modulePartitionPathPrefix),
                NoResolverOnInterfaceFieldsRule(),
            )
        )
    )

    /**
     * Returns a [SchemaValidator] with all standard Viaduct validation rules.
     */
    fun create(): SchemaValidator = validator

    /**
     * Validates a schema using all standard rules.
     *
     * @return list of validation errors (empty if valid)
     */
    fun validate(schema: ViaductSchema): List<SchemaValidationError> {
        return validator.validate(schema)
    }
}
