package viaduct.tenant.runtime.featuretests.fixtures

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import kotlin.reflect.KClass
import viaduct.api.reflect.Type
import viaduct.api.types.Enum
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.runtime.FakeArguments
import viaduct.tenant.runtime.FakeMutation
import viaduct.tenant.runtime.FakeObject
import viaduct.tenant.runtime.FakeQuery

/**
 * Reflection loader that returns Fake GRT types instead of looking up real generated classes.
 */
internal class FakeReflectionLoader(private val schema: ViaductSchema) : viaduct.api.internal.ReflectionLoader {
    override fun reflectionFor(name: String): Type<*> {
        // Look up the type in the schema
        val graphQLType = requireNotNull(schema.schema.getType(name)) {
            "Type $name not found in schema"
        }

        // Return appropriate Fake type based on GraphQL type kind
        return when {
            name == schema.schema.queryType.name -> FakeQuery.Reflection
            name == schema.schema.mutationType?.name -> FakeMutation.Reflection
            graphQLType is GraphQLObjectType || graphQLType is GraphQLInputObjectType -> object : Type<FakeObject> {
                override val name: String = name
                override val kcls = FakeObject::class
            }
            graphQLType is GraphQLEnumType -> object : Type<Enum> {
                override val name: String = name
                @Suppress("UNCHECKED_CAST")
                override val kcls = Class.forName("viaduct.tenant.runtime.featuretests.fixtures.$name").kotlin as KClass<Enum>
            }
            else -> throw IllegalArgumentException("No reflection for $name ($graphQLType).")
        }
    }

    override fun getGRTKClassFor(name: String): KClass<*> =
        if (name.endsWith("_Arguments")) {
            FakeArguments::class
        } else {
            reflectionFor(name).kcls
        }
}
