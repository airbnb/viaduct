package viaduct.tenant.runtime.execution.batchresolver.errorhandling

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
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
    /** The internal ID whose context the batch node resolver should intentionally omit. */
    var internalIdToOmit: String? = null

    private fun fooGlobalId(internalId: String) = GlobalIDCodecDefault.serialize("Foo", internalId)

    @Test
    fun `batch resolver omission fails the entire invocation`() {
        internalIdToOmit = "1"

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

        assert(result.errors.size == 2)
        assert(
            result.errors.all {
                it.message ==
                    "viaduct.errors.TenantUsageException: The batchResolve function in the Node resolver for Foo was given a batch of size 2 but returned 1 elements"
            }
        )
        assert(result.errors.map { it.path }.toSet() == setOf(listOf("f1"), listOf("f2")))
        assert(result.errors.all { it.extensions["fullyQualifiedErrorClass"] == "viaduct.errors.TenantUsageException" })

        val data = result.getData()!!
        assert(data["f1"] == null)
        assert(data["f2"] == null)
    }

    @Test
    fun `batch resolver contexts contain correct client selections`() {
        internalIdToOmit = null

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
