@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.graphql.schema.ViaductSchema as VSchema
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

class ViaductSchemasTest : KotestPropertyBase() {
    @Test
    fun `Arb_viaductSchema`(): Unit =
        runBlocking {
            Arb.viaductSchema().checkAll {
                markSuccess()
            }
        }

    @Test
    fun `Arb_viaductSchema generates valid VSchema`(): Unit =
        runBlocking {
            val cfg = Config.default + (UndeclaredNamespaceTypeWeight to 0.5)
            Arb.viaductSchema(cfg).checkInvariants { schema, check ->
                checkViaductSchemaInvariants(VSchema.fromGraphQLSchema(schema.schema), check)
            }
        }

    @Test
    fun `UndeclaredNamespaceTypeWeight`(): Unit =
        runBlocking {
            val gjSchema = """
                | type Query { foo: Foo }
                | type Foo { bar: Bar }
                | type Bar { x: Int }
            """.trimMargin().asSchema

            // disabled
            Arb.viaductSchema(
                gjSchema,
                cfg = Config.default + (UndeclaredNamespaceTypeWeight to 0.0)
            ).checkAll { schema ->
                val foo = schema.schema.getObjectType("Foo")
                val bar = schema.schema.getObjectType("Bar")
                assertFalse(foo.hasAppliedDirective("namespaceType"))
                assertFalse(bar.hasAppliedDirective("namespaceType"))
            }

            // enabled
            Arb.viaductSchema(
                gjSchema,
                cfg = Config.default + (UndeclaredNamespaceTypeWeight to 1.0)
            ).checkAll { schema ->
                val foo = schema.schema.getObjectType("Foo")
                val bar = schema.schema.getObjectType("Bar")
                assertTrue(foo.hasAppliedDirective("namespaceType"))
                assertTrue(bar.hasAppliedDirective("namespaceType"))
            }
        }

    @Test
    fun `AddNamespaceTypes rejects operation roots`(): Unit =
        runBlocking {
            val gjSchema = """
                | type Query { query: Query, foo: Foo }
                | type Foo { x: Int }
            """.trimMargin().asSchema

            Arb.viaductSchema(
                gjSchema,
                cfg = Config.default + (UndeclaredNamespaceTypeWeight to 1.0)
            ).checkAll { schema ->
                val query = schema.schema.queryType
                val foo = schema.schema.getObjectType("Foo")
                assertFalse(query.hasAppliedDirective("namespaceType"))
                assertTrue(foo.hasAppliedDirective("namespaceType"))
            }
        }

    @Test
    fun `AddNamespaceTypes ignores unreachable types`(): Unit =
        runBlocking {
            val gjSchema = """
                | type Query { foo: Foo }
                | type Foo { x: Int }
                | type Bar { y: Int }
            """.trimMargin().asSchema

            Arb.viaductSchema(
                gjSchema,
                cfg = Config.default + (UndeclaredNamespaceTypeWeight to 1.0)
            ).checkAll { schema ->
                val foo = schema.schema.getObjectType("Foo")
                val bar = schema.schema.getObjectType("Bar")
                assertTrue(foo.hasAppliedDirective("namespaceType"))
                assertFalse(bar.hasAppliedDirective("namespaceType"))
            }
        }

    @Test
    fun `AddNamespaceTypes ignores interface implementations`(): Unit =
        runBlocking {
            val gjSchema = """
                | type Query { foo: Foo }
                | interface Bar { x: Int }
                | type Foo implements Bar { x: Int }
            """.trimMargin().asSchema

            Arb.viaductSchema(
                gjSchema,
                cfg = Config.default + (UndeclaredNamespaceTypeWeight to 1.0)
            ).checkAll { schema ->
                val foo = schema.schema.getObjectType("Foo")
                assertFalse(foo.hasAppliedDirective("namespaceType"))
            }
        }

    @Test
    fun `AddNamespaceTypes extends declared namespaces`(): Unit =
        runBlocking {
            val gjSchema = """
                | directive @namespaceType on OBJECT
                | type Query { foo: Foo }
                | type Foo @namespaceType { bar: Bar }
                | type Bar { x: Int }
            """.trimMargin().asSchema

            Arb.viaductSchema(
                gjSchema,
                cfg = Config.default + (UndeclaredNamespaceTypeWeight to 1.0)
            ).checkAll { schema ->
                val bar = schema.schema.getObjectType("Bar")
                assertTrue(bar.hasAppliedDirective("namespaceType"))
            }
        }
}
