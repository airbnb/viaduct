package viaduct.tenant.runtime.execution.batchresolver.errorhandling

import kotlin.collections.get
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.SelectiveResolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.batchresolver.errorhandling.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class BatchResolverErrorHandlingFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | extend type Query {
        |   foo(id: ID! @idOf(type: "Foo")): Foo @resolver
        | }
        |
        | type Foo implements Node @resolver {
        |   id: ID!
        |   a: String
        |   b: String
        |   c: String
        | }
        | #END_SCHEMA
        """.trimMargin()

    @Resolver
    class Query_FooResolver : QueryResolvers.Foo() {
        override suspend fun resolve(ctx: Context): Foo {
            return ctx.nodeFor(ctx.arguments.id)
        }
    }

    class FooResolver : NodeResolvers.Foo(), SelectiveResolver {
        companion object {
            var shouldReturnWrongNumberOfResults = false
        }

        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Foo>> {
            val results = contexts.map { ctx ->
                val selections = ctx.selections().toString()
                FieldValue.Companion.ofValue(
                    Foo.Builder(ctx)
                        .id(ctx.id)
                        .a(selections)
                        .b("test-b-value")
                        .c("test-c-value")
                        .build()
                )
            }

            return if (shouldReturnWrongNumberOfResults) {
                results.take(results.size - 1)
            } else {
                results
            }
        }
    }

    @Test
    fun `batch resolver returning wrong number of results causes error`() {
        FooResolver.shouldReturnWrongNumberOfResults = true

        val id1 = createGlobalIdString(Foo.Reflection, "1")
        val id2 = createGlobalIdString(Foo.Reflection, "2")

        execute(
            query = """
            query {
                f1: foo(id: "$id1") {
                    id
                    a
                }
                f2: foo(id: "$id2") {
                    id
                    a
                    b
                    c
                }
            }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "f1" to null
                "f2" to null
            }
            "errors" to arrayOf(
                {
                    "message" to "java.lang.IllegalStateException: The batchResolve function in the Node resolver for Foo was given a batch of size 2 but returned 1 elements"
                    "locations" to arrayOf(
                        {
                            "line" to 2
                            "column" to 5
                        }
                    )
                    "path" to listOf("f1")
                    "extensions" to {
                        "fieldName" to "foo"
                        "parentType" to "Foo"
                        "isFrameworkError" to "false"
                        "resolvers" to "Foo"
                        "classification" to "DataFetchingException"
                    }
                },
                {
                    "message" to "java.lang.IllegalStateException: The batchResolve function in the Node resolver for Foo was given a batch of size 2 but returned 1 elements"
                    "locations" to arrayOf(
                        {
                            "line" to 6
                            "column" to 5
                        }
                    )
                    "path" to listOf("f2")
                    "extensions" to {
                        "fieldName" to "foo"
                        "parentType" to "Foo"
                        "isFrameworkError" to "false"
                        "resolvers" to "Foo"
                        "classification" to "DataFetchingException"
                    }
                }
            )
        }

        FooResolver.shouldReturnWrongNumberOfResults = false
    }

    @Test
    fun `batch resolver contexts contain correct client selections`() {
        FooResolver.shouldReturnWrongNumberOfResults = false

        val id1 = createGlobalIdString(Foo.Reflection, "1")
        val id2 = createGlobalIdString(Foo.Reflection, "2")

        val result = execute(
            query = """
            query {
                f1: foo(id: "$id1") {
                    id
                    a
                }
                f2: foo(id: "$id2") {
                    id
                    a
                    b
                    c
                }
            }
            """.trimIndent()
        )

        assert(result.errors.isEmpty()) { "Query should execute without errors, got: ${result.errors}" }

        val data = result.getData()!!
        val f1Data = data["f1"] as Map<*, *>
        val f2Data = data["f2"] as Map<*, *>
        val f1Selections = f1Data["a"] as String
        val f2Selections = f2Data["a"] as String

        assert(f1Selections.contains("Foo.id") && f1Selections.contains("Foo.a")) {
            "f1 selections should contain Foo.id and Foo.a, got: $f1Selections"
        }
        assert(!f1Selections.contains("Foo.b") && !f1Selections.contains("Foo.c")) {
            "f1 selections should NOT contain Foo.b or Foo.c, got: $f1Selections"
        }

        assert(
            f2Selections.contains("Foo.id") && f2Selections.contains("Foo.a") &&
                f2Selections.contains("Foo.b") && f2Selections.contains("Foo.c")
        ) {
            "f2 selections should contain all fields (Foo.id, Foo.a, Foo.b, Foo.c), got: $f2Selections"
        }
    }
}
