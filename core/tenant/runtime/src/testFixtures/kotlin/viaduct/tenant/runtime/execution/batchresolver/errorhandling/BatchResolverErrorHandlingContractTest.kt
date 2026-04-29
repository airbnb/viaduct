package viaduct.tenant.runtime.execution.batchresolver.errorhandling

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

@TestSchema(
    """
    extend type Query {
      foo(id: ID! @idOf(type: "Foo")): Foo @resolver
    }

    type Foo implements Node @resolver(isSelective: true,isBatching: true) {
      id: ID!
      a: String
      b: String
      c: String
    }
"""
)
abstract class BatchResolverErrorHandlingContractTest : KotlinFeatureAppTestContractBase() {
    /**
     * Controls whether the batch node resolver intentionally returns the wrong
     * number of results. Visible to resolvers via Guice injection of the test instance.
     */
    var shouldReturnWrongNumberOfResults = false

    private fun fooGlobalId(internalId: String) = GlobalIDCodecDefault.serialize("Foo", internalId)

    @Test
    fun `batch resolver returning wrong number of results causes error`() {
        shouldReturnWrongNumberOfResults = true

        val id1 = fooGlobalId("1")
        val id2 = fooGlobalId("2")

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
                    "message" to "viaduct.errors.TenantUsageException: The batchResolve function in the Node resolver for Foo was given a batch of size 2 but returned 1 elements"
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
                        "resolvers" to "Foo"
                        "isFrameworkError" to "false"
                        "fullyQualifiedErrorClass" to "viaduct.errors.TenantUsageException"
                        "classification" to "DataFetchingException"
                    }
                },
                {
                    "message" to "viaduct.errors.TenantUsageException: The batchResolve function in the Node resolver for Foo was given a batch of size 2 but returned 1 elements"
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
                        "resolvers" to "Foo"
                        "isFrameworkError" to "false"
                        "fullyQualifiedErrorClass" to "viaduct.errors.TenantUsageException"
                        "classification" to "DataFetchingException"
                    }
                }
            )
        }
    }

    @Test
    fun `batch resolver contexts contain correct client selections`() {
        shouldReturnWrongNumberOfResults = false

        val id1 = fooGlobalId("1")
        val id2 = fooGlobalId("2")

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
