@file:Suppress("MatchingDeclarationName")

package viaduct.graphql.schema.graphqljava

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.invariants.FailureCollector

private val `schema def plus alternatives` = """
    schema {
       query: Foo
       mutation: Bar
       subscription: Baz
    }
    type Foo { blank: String }
    type Bar { blank: String }
    type Baz { blank: String }
    type AltFoo { blank: String }
    type AltBar { blank: String }
    type AltBaz { blank: String }
    interface BadFoo { blank: String }
    interface BadBar { blank: String }
    interface BadBaz { blank: String }
""".trimIndent()

/** A set of unit tests for the factory methods of
 *  GJSchemaRaw and FilteredSchema to make sure they
 *  correctly set the root type defs.
 */
interface RootTypeFactoryContractForBoth {
    fun makeSchema(schema: String): ViaductSchema

    @Test
    fun `schema def works`() {
        makeSchema(`schema def plus alternatives`)
            .apply {
                assertSame(this.types["Foo"], this.queryTypeDef)
                assertSame(this.types["Bar"], this.mutationTypeDef)
                assertSame(this.types["Baz"], this.subscriptionTypeDef)
            }
    }
}

private val `defaults plus alternatives` = """
    type Query { blank: String }
    type Mutation { blank: String }
    type Subscription { blank: String }
    type Foo { blank: String }
    type Bar { blank: String }
    type Baz { blank: String }
""".trimIndent()

private val `schema plus defaults plus alternatives` = """
    schema { query: Foo }
    type Query { blank: String }
    type Mutation { blank: String }
    type Subscription { blank: String }
    type Foo { blank: String }
    type Bar { blank: String }
    type Baz { blank: String }
""".trimIndent()

private val `schema def with missing names` = """
    schema {
      query: Foo
      mutation: Bar
      subscription: Baz
    }
    type AltFoo { blank: String }
    type AltBar { blank: String }
    type AltBaz { blank: String }
""".trimIndent()

private val `schema def with non object types` = """
    schema {
      query: Foo
      mutation: Bar
      subscription: Baz
    }
    interface Foo { blank: String }
    interface Bar { blank: String }
    interface Baz { blank: String }
    type AltFoo { blank: String }
    type AltBar { blank: String }
    type AltBaz { blank: String }
""".trimIndent()

/** GJSchemaRaw has more test cases. */
interface RootTypeFactoryContractForRaw : RootTypeFactoryContractForBoth {
    @Test
    fun `defaults work`() {
        makeSchema(`defaults plus alternatives`)
            .apply {
                assertSame(this.types["Query"], this.queryTypeDef)
                assertSame(this.types["Mutation"], this.mutationTypeDef)
                assertSame(this.types["Subscription"], this.subscriptionTypeDef)
            }
    }

    @Test
    fun `schema def takes precedence over defaults`() {
        makeSchema(`schema plus defaults plus alternatives`)
            .apply {
                // schema def says query: Foo, so Foo is used instead of Query
                assertSame(this.types["Foo"], this.queryTypeDef)
                // schema def doesn't specify mutation/subscription, so defaults are used
                assertSame(this.types["Mutation"], this.mutationTypeDef)
                assertSame(this.types["Subscription"], this.subscriptionTypeDef)
            }
    }

    @Test
    fun `missing names from schema def fail`() {
        val check = FailureCollector()
        check.doesThrow<IllegalArgumentException>("") {
            makeSchema(`schema def with missing names`)
        }
        check.assertEmpty("\n")
    }

    @Test
    fun `non object type from schema def fail`() {
        val check = FailureCollector()
        check.doesThrow<IllegalArgumentException>("") {
            makeSchema(`schema def with non object types`)
        }
        check.assertEmpty("\n")
    }

    @Test
    fun `no schema def and no defaults yields null root types`() {
        val schema = """
            type Foo { blank: String }
            type Bar { blank: String }
        """.trimIndent()
        makeSchema(schema).apply {
            assertNull(this.queryTypeDef)
            assertNull(this.mutationTypeDef)
            assertNull(this.subscriptionTypeDef)
        }
    }
}
