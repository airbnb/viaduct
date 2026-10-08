@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.ExecutionInput
import graphql.language.AstPrinter
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.idl.SchemaParser
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.ConfigKey
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.WeightValidator
import viaduct.graphql.utils.allChildrenOfType

class UtilTest : KotestPropertyBase() {
    @Test
    fun `builtinDirectives includes graphql-java defaults`() {
        val schema = "type Query { value: String }".asSchema
        val missing = schema.directives.map { it.name }.toSet() - builtinDirectives.keys

        assertTrue(missing.isEmpty(), "Missing builtin directives: $missing")
    }

    @Test
    fun `printAsSDLFragment preserves custom directives and omits builtins`() {
        val schema = """
            directive @custom on OBJECT
            type Query @custom { value: String }
        """.trimIndent().asSchema

        val fragment = SchemaParser().parse(schema.printAsSDLFragment())

        assertEquals(setOf("custom"), fragment.getDirectiveDefinitions().keys)
    }

    @Test
    fun `GraphQLTypes can be roundtripped through Type`() {
        val sdl = """
            type Query {
              a:Int
              b:Enum
              c:[[Int]]
              d:[[Int!]!]!
              e: U
              f(inp:Inp!):Int
            }
            union U = Query
            enum Enum { A, B }
            input Inp {
                x:Int!
                inp:Inp
            }
        """.trimIndent()
        val doc = sdl.asDocument
        val schema = sdl.asSchema

        val types = doc.allChildrenOfType<Type<*>>()
        // sanity
        assertTrue(types.isNotEmpty())

        types.forEach { t1 ->
            val t2 = t1.asSchemaType(schema).asAstType()
            assertTypesEqual(t1, t2)
        }
    }

    @Test
    fun `DocumentComparator and ExecutionInputComparator -- sorts documents by node count`() {
        val q1 = "{ someVeryLongFieldName }"
        val q2 = "{ x { y } }"

        // DocumentComparator
        let {
            val d1 = q1.asDocument
            val d2 = q2.asDocument
            assertEquals(-1, DocumentComparator.compare(d1, d2))
        }

        // ExecutionInputComparator
        let {
            val e1 = ExecutionInput.newExecutionInput(q1).build()
            val e2 = ExecutionInput.newExecutionInput(q2).build()
            assertEquals(-1, ExecutionInputComparator.compare(e1, e2))
        }
    }

    @Test
    fun `String asSchema`() {
        val schema = "type Query { x:Int }".asSchema
        assertNotNull(schema.queryType.getField("x"))
    }

    @Test
    fun `String asDocument`() {
        val sdl = "type Query {x: Int}"
        assertEquals(sdl, AstPrinter.printAstCompact(sdl.asDocument))
    }

    @Test
    fun `maybeThrowResolverException`(): Unit =
        runBlocking {
            // does not throw
            val key = object : ConfigKey<Double>(0.0, WeightValidator) {}
            assertDoesNotThrow {
                maybeThrowResolverException(Config.default, key, randomSource)
            }

            // throws
            val err = assertThrows<ResolverException> {
                maybeThrowResolverException(Config.default + (key to 1.0), key, randomSource)
            }
            assertEquals(key, err.key)
            assertTrue(key.javaClass.name in err.message)
        }

    @Test
    fun `ViaductSchema objects`() {
        val schema = """
            extend type Query { x:Int }
            type Obj { x:Int }
            union Union = Obj
            interface I { x:Int }
        """.asViaductSchema

        val objNames = schema.objects.map { it.name }.toSet()
        assertTrue(objNames.containsAll(setOf("Obj", "Query")))
        assertTrue(objNames.intersect(setOf("Union", "I", "Int", "__Type")).isEmpty())
    }

    @Test
    fun `ViaductSchema objectCoordinates`() {
        val schema = """
            extend type Query { x:Int }
            type Obj implements I { x:Int }
            union Union = Obj
            interface I { x:Int }
        """.asViaductSchema

        val coords = schema.objectCoordinates
        assertTrue(
            coords.containsAll(
                setOf(
                    "Query" to "x",
                    "Obj" to "x"
                )
            )
        )

        assertTrue(
            coords.intersect(
                setOf(
                    "I" to "x",
                    "Obj" to "__typename",
                    "__Type" to "name"
                )
            ).isEmpty()
        )

        // interface
        assertEquals(
            setOf("Obj" to "x"),
            schema.objectCoordinates(schema.schema.getTypeAs("I"))
                .toSet()
        )

        // union
        assertEquals(
            setOf("Obj" to "x"),
            schema.objectCoordinates(schema.schema.getTypeAs("Union"))
                .toSet()
        )
    }
}

private fun assertTypesEqual(
    t1: Type<*>,
    t2: Type<*>
) {
    when (t1) {
        is TypeName -> {
            t2.shouldBeInstanceOf<TypeName>()
            assertEquals(t1.name, t2.name)
        }
        is NonNullType -> {
            t2.shouldBeInstanceOf<NonNullType>()
            assertTypesEqual(t1.type, t2.type)
        }
        is ListType -> {
            t2.shouldBeInstanceOf<ListType>()
            assertTypesEqual(t1.type, t2.type)
        }
        else -> throw IllegalArgumentException("unknown Type: $t1")
    }
}
