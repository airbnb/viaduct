package viaduct.tenant.runtime.execution.filebased

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

@TestSchema(
    """
    type Item implements Node @resolver {
        id: ID!
        name: String!
    }
"""
)
abstract class FileBasedBootstrapContractTest : KotlinFeatureAppTestContractBase() {
    private val codec = GlobalIDCodecDefault

    @BeforeEach
    fun skipIfRegistryAbsent() {
        val pkg = this::class.java.packageName
        val resource = "META-INF/viaduct/modules/$pkg.json"
        // Without the registry JSON, file-based bootstrapping cannot run — no point executing these tests.
        assumeTrue(
            Thread.currentThread().contextClassLoader.getResource(resource) != null,
            "Skipping: registry not on classpath ($resource)"
        )
    }

    protected fun itemGlobalId(internalId: String): String = codec.serialize("Item", internalId)

    @Test
    fun `node resolver loaded from classpath registry resolves via built-in node query`() {
        val globalId = itemGlobalId("item-1")
        execute(
            query = """
                query TestQuery {
                    node(id: "$globalId") {
                        ... on Item {
                            id
                            name
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "node" to {
                    "id" to globalId
                    "name" to "item-1"
                }
            }
        }
    }
}
