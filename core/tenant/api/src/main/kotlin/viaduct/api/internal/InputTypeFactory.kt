package viaduct.api.internal

import graphql.schema.GraphQLInputObjectType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineSchema
import viaduct.tenant.runtime.jvm.InputTypeFactory as SharedInputTypeFactory

/** Retains the JVM entry points used by previously generated builders. */
@InternalApi
object InputTypeFactory {
    @JvmStatic
    fun argumentsInputType(
        name: String,
        typeName: String,
        fieldName: String,
        schema: EngineSchema
    ): GraphQLInputObjectType = SharedInputTypeFactory.argumentsInputType(name, typeName, fieldName, schema)

    @JvmStatic
    fun inputObjectInputType(
        name: String,
        schema: EngineSchema
    ): GraphQLInputObjectType = SharedInputTypeFactory.inputObjectInputType(name, schema)
}
