package viaduct.graphql.schema.graphqljava

import graphql.GraphQLError
import graphql.language.OperationTypeDefinition
import graphql.language.SchemaDefinition
import graphql.language.TypeName
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaTypeChecker
import graphql.schema.idl.TypeDefinitionRegistry
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.TypeDefinitionRegistryOptions
import viaduct.graphql.schema.graphqljava.extensions.toRegistry

/**
 * Validates [schema] using graphql-java's GraphQL schema rules.
 *
 * The returned list is empty when the schema is valid.
 */
fun graphqlValidate(schema: ViaductSchema): List<GraphQLError> {
    val registry = schema.toRegistry(TypeDefinitionRegistryOptions.NO_STUBS)
    registry.addSchemaDefinition(schema)
    return SchemaTypeChecker().checkTypeRegistry(
        registry.readOnly(),
        RuntimeWiring.MOCKED_WIRING,
    )
}

private fun TypeDefinitionRegistry.addSchemaDefinition(schema: ViaductSchema) {
    val operationTypes =
        buildList {
            schema.queryTypeDef?.let { add(operationType("query", it.name)) }
            schema.mutationTypeDef?.let { add(operationType("mutation", it.name)) }
            schema.subscriptionTypeDef?.let { add(operationType("subscription", it.name)) }
        }
    if (operationTypes.isNotEmpty()) {
        add(
            SchemaDefinition
                .newSchemaDefinition()
                .operationTypeDefinitions(operationTypes)
                .build()
        )
    }
}

private fun operationType(
    operation: String,
    typeName: String,
): OperationTypeDefinition =
    OperationTypeDefinition
        .newOperationTypeDefinition()
        .name(operation)
        .typeName(TypeName(typeName))
        .build()
