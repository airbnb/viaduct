package viaduct.api.bootstrap.test.grts

import graphql.schema.GraphQLInputObjectType
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.types.Arguments

@Suppress("ClassName")
class TestType_ParameterizedField_Arguments internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Arguments {
    val experiment: Boolean? get() = get("experiment")
}
