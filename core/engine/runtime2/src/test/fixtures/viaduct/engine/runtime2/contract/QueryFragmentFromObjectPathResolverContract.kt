package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromObjectField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom

/** Contract for consuming an object-fragment provider binding from a Query fragment. */
interface QueryFragmentFromObjectPathResolverContract : ResolverContract {
    @Test
    fun `object fragment provider binding can be used by the query fragment`() {
        val providerFragment =
            "fragment ConsumerProvider on Query { provided: provider }"
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      provider: Int!
                      source(value: Int!): Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val provider = schema.loweredSchema.requireObjectField("Query", "provider")
                    val source = schema.loweredSchema.requireObjectField("Query", "source")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        provider to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        source to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.fragmentFrom(providerFragment),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { querySide: source(value: ${'$'}providedValue) }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("querySide")
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "providedValue") to
                            schema.fromObjectField(providerFragment, listOf("provided")),
                    )
                },
            )
        val world = testWorld.assumptions
        val consumerKey = world.schema.contractKey("Query", "consumer")

        val resolved = resolveAndValidate(testWorld, "query { consumer }")

        assertEquals(7, resolved.getCell(consumerKey).get())
    }
}
