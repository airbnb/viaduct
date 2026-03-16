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
                BackingDataFieldsRule()
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
