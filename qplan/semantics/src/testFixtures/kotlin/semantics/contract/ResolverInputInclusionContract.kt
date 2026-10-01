package semantics.contract

import kotlin.test.assertEquals
import model.Arguments
import model.emptyFragmentOf
import model.fragmentFrom
import model.registry.fieldResolverOf
import model.registry.fromArgument
import model.requireObjectField
import model.testing.TestWorld
import org.junit.jupiter.api.Test

/** Contract for inclusion-aware materialization of field-resolver inputs. */
interface ResolverInputInclusionContract : ResolverContract {
    @Test
    fun `object and Query inputs honor fromArgument inclusion conditions`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      consumer(enabled: Boolean!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.loweredSchema.requireObjectField("Query", "source")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment ObjectInput on Query { objectValue: source @include(if: ${'$'}enabled) }",
                                    ),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment QueryInput on Query { queryValue: source @include(if: ${'$'}enabled) }",
                                    ),
                            ) { input, queryValue, arguments ->
                                val included = arguments.fieldValues.getValue("enabled") as Boolean
                                val expectedObjectKeys = if (included) setOf("objectValue") else emptySet()
                                val expectedQueryKeys = if (included) setOf("queryValue") else emptySet()
                                assertEquals(expectedObjectKeys, input.selectionValues().keys)
                                assertEquals(expectedQueryKeys, queryValue.selectionValues().keys)
                                if (included) {
                                    (input.selectionValues().getValue("objectValue") as Int) +
                                        (queryValue.selectionValues().getValue("queryValue") as Int)
                                } else {
                                    0
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "enabled") to
                            schema.loweredSchema.fromArgument(consumer, "enabled"),
                    )
                },
            )
        val world = testWorld.assumptions
        val excluded = world.schema.contractKey("Query", "consumer", mapOf("enabled" to false))
        val included = world.schema.contractKey("Query", "consumer", mapOf("enabled" to true))

        val resolved =
            resolveAndValidate(
                testWorld,
                "query { excluded: consumer(enabled: false) included: consumer(enabled: true) }",
            )

        assertEquals(0, resolved.getCell(excluded).get())
        assertEquals(14, resolved.getCell(included).get())
    }
}
