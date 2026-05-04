package viaduct.tenant.runtime.execution.filebased

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
        label: String! @resolver
        echoTag(tag: String!): String! @resolver
    }

    extend type Query {
        item(id: String!): Item! @resolver
        echoWithTag(tag: String!): String! @resolver
        taggedLabel: String! @resolver
    }
"""
)
abstract class FileBasedBootstrapContractTest : KotlinFeatureAppTestContractBase() {
    override val useFileBasedBootstrap: Boolean = true

    private val codec = GlobalIDCodecDefault

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

    @Test
    fun `multiple field resolvers on the same type are all loaded from classpath registry`() {
        val globalId = itemGlobalId("item-2")
        execute(
            query = """
                query TestQuery {
                    item(id: "item-2") {
                        id
                        name
                        label
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "item" to {
                    "id" to globalId
                    "name" to "item-2"
                    "label" to "label:item-2"
                }
            }
        }
    }

    @Test
    fun `variable binding on queryValueFragment-only resolver is preserved in classpath registry`() {
        execute(
            query = """
                query TestQuery {
                    item(id: "item-3") {
                        echoTag(tag: "hello")
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "item" to {
                    "echoTag" to "hello"
                }
            }
        }
    }

    @Test
    fun `VariablesProvider nested class is detected from classpath registry and supplies variables at runtime`() {
        execute(
            query = """
                query TestQuery {
                    taggedLabel
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "taggedLabel" to "from-provider"
            }
        }
    }
}
