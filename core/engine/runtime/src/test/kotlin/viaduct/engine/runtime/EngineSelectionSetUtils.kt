package viaduct.engine.runtime

import graphql.schema.GraphQLSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl

/** Creates a [EngineSelectionSet] from a type name, selections string, variables and schema. */
fun createEngineSelectionSet(
    type: String,
    selections: String,
    variables: Map<String, Any?> = emptyMap(),
    schema: GraphQLSchema
): EngineSelectionSet {
    return createEngineSelectionSet(type, selections, variables, ViaductSchema(schema))
}

/** Creates a [EngineSelectionSet] from a type name, selections string, variables and schema. */
fun createEngineSelectionSet(
    type: String,
    selections: String,
    variables: Map<String, Any?> = emptyMap(),
    schema: ViaductSchema
): EngineSelectionSet {
    val factory = EngineSelectionSetFactoryImpl(schema)
    return factory.engineSelectionSet(
        SelectionsParser.parse(type, selections),
        variables
    )
}
