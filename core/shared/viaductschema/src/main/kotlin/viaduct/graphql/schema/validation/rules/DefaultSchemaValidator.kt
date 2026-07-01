package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationRule

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
 * - [ParentFieldConstraintsRule]: @parent fields must be declared on objects and be no-arg, non-list composite fields without selective parent producers
 * - [FieldArgumentsRequireResolverRule]: Object fields with arguments must have @resolver
 * - [ConnectionTypeStructureRule]: @connection types must have a valid 'edges' field and a non-null 'pageInfo' field
 * - [ConnectionEdgeStructureRule]: @edge types must have a 'node' field
 * - [ConnectionPageInfoRule]: PageInfo types must have hasNextPage/hasPreviousPage as Boolean! and nullable cursors
 * - [ConnectionArgumentsNullabilityRule]: Pagination args (first, after, last, before) must be nullable
 * - [NoCrossModuleInputExtensionsRule]: Input types (enum, input) may not be extended across module partitions
 * - [StructuralDirectivesOnBaseTypeRule]: @connection, @edge, @namespaceType must be on the base type definition
 * - [CrossModuleExtensionFieldsResolverRule]: Fields added by cross-module extend type must have @resolver
 * - [NoResolverOnInterfaceFieldsRule]: Interface fields cannot declare @resolver
 * - [PageInfoLocationRule]: PageInfo must not be defined inside a module partition
 * - [NodeInterfaceIdConsistencyRule]: Interfaces with an id field implemented alongside Node must implement Node
 *
 * When [strictMode] is true, also enforces [StrictConnectionPageInfoRule]: PageInfo must not implement
 * interfaces or be a union member.
 */
class DefaultSchemaValidator(strictMode: Boolean = false) {
    private val allowedScalarNames = GraphQLBuiltIns.SCALARS + GraphQLBuiltIns.VIADUCT_SCALARS
    private val modulePartitionPathPrefix = "partition/"

    private val standardRules: List<ValidationRule> = buildList {
        add(NoSubscriptionsRule())
        add(NoCustomScalarsRule(allowedScalarNames))
        add(ApplicationOnlyDefinitionsRule(modulePartitionPathPrefix))
        add(BackingDataFieldsRule())
        add(IdOfTypeValidationRule())
        add(NamespaceTypeConstraintsRule())
        add(ParentFieldConstraintsRule())
        add(FieldArgumentsRequireResolverRule())
        add(ConnectionTypeStructureRule())
        add(ConnectionEdgeStructureRule())
        add(ConnectionPageInfoRule())
        if (strictMode) add(StrictConnectionPageInfoRule())
        add(ConnectionArgumentsNullabilityRule())
        add(NoCrossModuleInputExtensionsRule(modulePartitionPathPrefix))
        add(StructuralDirectivesOnBaseTypeRule())
        add(CrossModuleExtensionFieldsResolverRule(modulePartitionPathPrefix))
        add(NoResolverOnInterfaceFieldsRule())
        add(PageInfoLocationRule(modulePartitionPathPrefix))
        add(NodeInterfaceIdConsistencyRule())
    }

    private val validator = SchemaValidator(phases = listOf(standardRules))

    /**
     * Returns a [SchemaValidator] with all standard Viaduct validation rules.
     */
    fun create(): SchemaValidator = validator

    /**
     * Returns a [SchemaValidator] with all standard Viaduct validation rules and, when
     * [validScopes] is non-empty, an additional [ScopeUsageRule] appended to the same phase that
     * enforces schema-scoping directive correctness against the supplied scope universe.
     *
     * Callers should pass the universe configured via `viaductApplication { declareScoping { scopes(...) } }`
     * (forwarded through `ViaductApplicationExtension.schemaScoping.scopeUniverse`). Passing `null`
     * or an empty set yields the unscoped validator — the same instance returned by [create] —
     * which preserves behavior for projects that have not opted into schema scoping.
     */
    fun create(validScopes: Set<String>?): SchemaValidator =
        if (validScopes.isNullOrEmpty()) {
            validator
        } else {
            SchemaValidator(phases = listOf(standardRules + ScopeUsageRule(validScopes)))
        }

    /**
     * Validates a schema using all standard rules.
     *
     * @return list of validation errors (empty if valid)
     */
    fun validate(schema: ViaductSchema): List<SchemaValidationError> = validator.validate(schema)
}
