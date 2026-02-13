package viaduct.engine.runtime

import graphql.schema.GraphQLSchema
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl

/** Creates a [RawSelectionSet] from a type name, selections string, variables and schema. */
fun createRss(
    type: String,
    selections: String,
    variables: Map<String, Any?> = emptyMap(),
    schema: GraphQLSchema
): RawSelectionSet {
    return createRss(type, selections, variables, ViaductSchema(schema))
}

/** Creates a [RawSelectionSet] from a type name, selections string, variables and schema. */
fun createRss(
    type: String,
    selections: String,
    variables: Map<String, Any?> = emptyMap(),
    schema: ViaductSchema
): RawSelectionSet {
    val factory = RawSelectionSetFactoryImpl(schema)
    return factory.rawSelectionSet(
        SelectionsParser.parse(type, selections),
        variables
    )
}
