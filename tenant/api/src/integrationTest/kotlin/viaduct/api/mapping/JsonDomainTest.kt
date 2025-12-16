@file:Suppress("ForbiddenImport")

package viaduct.api.mapping

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.reflect.Type
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.O1
import viaduct.api.types.Input
import viaduct.api.types.Object
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.mapping.graphql.IR
import viaduct.mapping.test.DomainValidator

class JsonDomainTest : KotestPropertyBase() {
    private val schema = SchemaUtils.getSchema()
    private val internal = MockInternalContext.mk(schema, grtPackage = "viaduct.api.testschema")
    private val executionContext = MockExecutionContext(internal)
    private val domain = JsonDomain(executionContext)
    private val validator = DomainValidator(domain, schema.schema)

    private val GraphQLNamedType.isIntrospectionType: Boolean get() = name.startsWith("__")

    private val objectTypes = schema.schema.allTypesAsList
        .filter { !it.isIntrospectionType && it is GraphQLObjectType }
    private val inputObjectTypes = schema.schema.allTypesAsList
        .filter { !it.isIntrospectionType && it is GraphQLInputObjectType }

    @Test
    fun `JsonDomain roundtrips arbitrary IR`() {
        validator.checkAll()
    }

    @Test
    fun `JsonDomain_forType can parse values for object types without __typename keys`(): Unit =
        runBlocking {
            Arb.of(objectTypes).forAll { type ->
                val reflection = internal.reflectionLoader.reflectionFor(type.name) as Type<Object>
                val conv = JsonDomain.forType(executionContext, reflection).conv

                val obj = conv("{}")
                obj.name == type.name
            }
        }

    @Test
    fun `JsonDomain_forType can parse values for input object types without __typename keys`(): Unit =
        runBlocking {
            Arb.of(inputObjectTypes).forAll { type ->
                val reflection = internal.reflectionLoader.reflectionFor(type.name) as Type<Input>
                val conv = JsonDomain.forType(executionContext, reflection).conv

                val obj = conv("{}")
                obj.name == type.name
            }
        }

    @Test
    fun `JsonDomain_forSelectionSet -- converts simple aliased values`() {
        val domain = JsonDomain.forSelectionSet(
            executionContext,
            mkSelectionSet(
                schema,
                O1.Reflection,
                "myId: id, myStringField:stringField"
            )
        )
        assertEquals(
            IR.Value.Object(O1.Reflection.name, "myId" to IR.Value.String("1")),
            domain.conv("""{"myId":"1"}""")
        )
    }

    @Test
    fun `JsonDomain can parse values with a __typename key`(): Unit =
        runBlocking {
            Arb.of(objectTypes + inputObjectTypes).forAll { type ->
                val str = """{"__typename": "${type.name}"}"""
                val obj = domain.conv(str)
                obj.name == type.name
            }
        }

    @Test
    fun `JsonDomain throws when __typename is a non-objectIsh type`(): Unit =
        runBlocking {
            val nonObjectIshTypes = schema.schema.allTypesAsList
                .filter { t ->
                    !t.isIntrospectionType &&
                        t !is GraphQLInputObjectType &&
                        t !is GraphQLObjectType
                }

            Arb.of(nonObjectIshTypes).forAll { type ->
                val str = """{"__typename": "${type.name}"}"""
                val result = runCatching { domain.conv(str) }
                val msg = result.exceptionOrNull()?.message
                msg?.contains("Expected an input or output") ?: false
            }
        }
}
