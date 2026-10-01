package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromQueryField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom

/** Contract for variables produced by exact paths in the defining Query fragment. */
interface FromQueryFieldResolverContract : ResolverContract {
    @TestFactory
    fun `Query field bindings support every fragment consumer`() =
        listOf(
            Triple("Query fragment", false, true),
            Triple("object fragment", true, false),
            Triple("both fragments", true, true),
        ).map { (name, consumeInObjectFragment, consumeInQueryFragment) ->
            dynamicTest(name) {
                assertFromQueryFieldConsumers(
                    consumeInObjectFragment = consumeInObjectFragment,
                    consumeInQueryFragment = consumeInQueryFragment,
                )
            }
        }

    private fun assertFromQueryFieldConsumers(
        consumeInObjectFragment: Boolean,
        consumeInQueryFragment: Boolean,
    ) {
        val objectFragmentSource =
            if (consumeInObjectFragment) {
                "fragment ConsumerObject on Query { objectSide: consume(value: ${'$'}provided) }"
            } else {
                ""
            }
        val queryConsumer =
            if (consumeInQueryFragment) " querySide: consume(value: ${'$'}provided)" else ""
        val queryFragmentSource =
            "fragment ConsumerQuery on Query { providedSource: provider$queryConsumer }"
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      provider: Int!
                      consume(value: Int!): Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    val provider = schema.loweredSchema.requireObjectField("Query", "provider")
                    val consume = schema.loweredSchema.requireObjectField("Query", "consume")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        provider to fieldResolverOf(empty) { _, _ -> 7 },
                        consume to
                            fieldResolverOf(empty) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment =
                                    objectFragmentSource
                                        .takeIf(String::isNotEmpty)
                                        ?.let(schema::fragmentFrom) ?: empty,
                                queryFragment = schema.fragmentFrom(queryFragmentSource),
                            ) { input, queryValue, _ ->
                                (input.selectionValues()["objectSide"] as? Int ?: 0) +
                                    (queryValue.selectionValues()["querySide"] as? Int ?: 0)
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "provided") to
                            schema.fromQueryField(
                                queryFragmentSource,
                                listOf("providedSource"),
                                variableField = consumer,
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val consumerKey = world.schema.contractKey("Query", "consumer")

        val resolved = resolveAndValidate(testWorld, "query { consumer }")

        assertEquals(
            if (consumeInObjectFragment && consumeInQueryFragment) 14 else 7,
            resolved.getCell(consumerKey).get(),
        )
    }
}
