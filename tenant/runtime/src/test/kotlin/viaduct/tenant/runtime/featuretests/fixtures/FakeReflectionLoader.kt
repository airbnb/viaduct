package viaduct.tenant.runtime.featuretests.fixtures

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import kotlin.reflect.KClass
import viaduct.api.reflect.Type
import viaduct.api.types.Enum
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.runtime.FakeArguments
import viaduct.tenant.runtime.FakeBackwardConnectionArguments
import viaduct.tenant.runtime.FakeConnectionArguments
import viaduct.tenant.runtime.FakeMultidirectionalConnectionArguments
import viaduct.tenant.runtime.FakeMutation
import viaduct.tenant.runtime.FakeObject
import viaduct.tenant.runtime.FakeQuery

/**
 * Reflection loader that returns Fake GRT types instead of looking up real generated classes.
 */
internal class FakeReflectionLoader(private val schema: ViaductSchema) : viaduct.api.internal.ReflectionLoader {
    /** Coordinates of fields registered as connection resolvers, used to determine argument types. */
    val connectionCoordinates = mutableSetOf<Pair<String, String>>()

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
            if (isConnectionArguments(name)) connectionArgsClassFor(name) else FakeArguments::class
        } else {
            reflectionFor(name).kcls
        }

    /**
     * Parses an Arguments GRT name (format: `TypeName_FieldName_Arguments`) into its
     * (typeName, fieldName) components. Returns null if the name cannot be parsed.
     */
    private fun parseArgumentTypeName(argTypeName: String): Pair<String, String> {
        val parts = argTypeName.split('_')
        require(parts.size == 3)
        return parts[0] to parts[1].replaceFirstChar { it.lowercase() }
    }

    /**
     * Determines whether an argument type name corresponds to a registered connection field.
     */
    private fun isConnectionArguments(argTypeName: String): Boolean {
        val (typeName, fieldName) = parseArgumentTypeName(argTypeName) ?: return false
        return (typeName to fieldName) in connectionCoordinates
    }

    /**
     * Detects the appropriate fake connection arguments class by inspecting the schema field's
     * argument names: forward-only (`first`/`after`), backward-only (`last`/`before`), or both.
     */
    private fun connectionArgsClassFor(argTypeName: String): KClass<*> {
        val (typeName, fieldName) = parseArgumentTypeName(argTypeName) ?: return FakeConnectionArguments::class
        val fieldType = schema.schema.queryType.takeIf { it.name == typeName }
            ?: schema.schema.getObjectType(typeName)
            ?: return FakeConnectionArguments::class
        val argNames = fieldType.getFieldDefinition(fieldName)?.arguments?.map { it.name }?.toSet() ?: emptySet()
        val hasForward = "first" in argNames || "after" in argNames
        val hasBackward = "last" in argNames || "before" in argNames
        return when {
            hasForward && hasBackward -> FakeMultidirectionalConnectionArguments::class
            hasBackward -> FakeBackwardConnectionArguments::class
            else -> FakeConnectionArguments::class
        }
    }
}
