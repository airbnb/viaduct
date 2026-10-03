package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.registry.fromObjectField
import viaduct.engine.runtime2.model.registry.fromQueryField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf

/** Contract for Query-fragment variables on a resolver reached through diagonal parent demand. */
interface ParentQueryFragmentVariableResolverContract : ResolverContract {
    @TestFactory
    fun `diagonal parent demand supports every variable source in a Query fragment`() =
        ParentQueryVariableSource.entries.map { source ->
            dynamicTest(source.displayName) {
                assertDiagonalParentQueryFragmentVariable(source)
            }
        }

    private fun assertDiagonalParentQueryFragmentVariable(source: ParentQueryVariableSource) {
        val bridgeObjectFragment =
            "fragment BridgeObject on Branch { parent { rootValue } objectProvided }"
        val bridgeQueryFragment =
            "fragment BridgeQuery on Query { " +
                "providedSource: queryProvided querySide: consume(value: ${'$'}provided) }"
        val leafResultFragment =
            "fragment LeafResult on Leaf { parent { bridge(seed: 5) } }"
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION

                    type Query {
                      root: Root!
                      queryProvided: Int!
                      consume(value: Int!): Int!
                    }

                    type Root {
                      rootValue: Int!
                      branch: Branch!
                    }

                    type Branch {
                      parent: Root @parent
                      objectProvided: Int!
                      leaf: Leaf!
                      bridge(seed: Int!): Int!
                    }

                    type Leaf {
                      parent: Branch @parent
                      result: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val emptyQuery = schema.loweredSchema.emptyFragmentOf("Query")
                    val emptyRoot = schema.loweredSchema.emptyFragmentOf("Root")
                    val emptyBranch = schema.loweredSchema.emptyFragmentOf("Branch")
                    val root = schema.loweredSchema.requireObjectField("Query", "root")
                    val queryProvided = schema.loweredSchema.requireObjectField("Query", "queryProvided")
                    val consume = schema.loweredSchema.requireObjectField("Query", "consume")
                    val branch = schema.loweredSchema.requireObjectField("Root", "branch")
                    val leaf = schema.loweredSchema.requireObjectField("Branch", "leaf")
                    val bridge = schema.loweredSchema.requireObjectField("Branch", "bridge")
                    val result = schema.loweredSchema.requireObjectField("Leaf", "result")
                    mapOf(
                        root to
                            fieldResolverOf(emptyQuery) { _, _ ->
                                schema.loweredSchema.objectOf("Root") { "rootValue" setTo 100 }
                            },
                        queryProvided to fieldResolverOf(emptyQuery) { _, _ -> 11 },
                        consume to
                            fieldResolverOf(emptyQuery) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        branch to
                            fieldResolverOf(emptyRoot) { _, _ ->
                                schema.loweredSchema.objectOf("Branch") { "objectProvided" setTo 7 }
                            },
                        leaf to
                            fieldResolverOf(emptyBranch) { _, _ -> schema.loweredSchema.objectOf("Leaf") },
                        bridge to
                            fieldResolverOf(
                                objectFragment = schema.fragmentFrom(bridgeObjectFragment),
                                queryFragment = schema.fragmentFrom(bridgeQueryFragment),
                            ) { input, queryValue, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                val rootValue = assertIs<Int>(parent.outputValue("rootValue"))
                                val querySide =
                                    assertIs<Int>(queryValue.outputValue("querySide"))
                                rootValue + querySide
                            },
                        result to
                            fieldResolverOf(schema.fragmentFrom(leafResultFragment)) { input, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                parent.outputValue("bridge")
                            },
                    )
                },
                variableProviders = { schema ->
                    val bridge = schema.loweredSchema.requireObjectField("Branch", "bridge")
                    mapOf(
                        Arguments.Variable.of(bridge, "provided") to
                            when (source) {
                                ParentQueryVariableSource.ARGUMENT ->
                                    schema.loweredSchema.fromArgument(bridge, "seed")
                                ParentQueryVariableSource.OBJECT_FIELD ->
                                    schema.fromObjectField(
                                        bridgeObjectFragment,
                                        listOf("objectProvided"),
                                    )
                                ParentQueryVariableSource.QUERY_FIELD ->
                                    schema.fromQueryField(
                                        bridgeQueryFragment,
                                        listOf("providedSource"),
                                        variableField = bridge,
                                    )
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val rootKey = world.schema.contractKey("Query", "root")
        val branchKey = world.schema.contractKey("Root", "branch")
        val leafKey = world.schema.contractKey("Branch", "leaf")
        val resultKey = world.schema.contractKey("Leaf", "result")

        val resolved = resolveAndValidate(testWorld, "query { root { branch { leaf { result } } } }")
        val root = assertIs<viaduct.engine.runtime2.model.ObjectEngineResult>(resolved.getCell(rootKey).get())
        val branch = assertIs<viaduct.engine.runtime2.model.ObjectEngineResult>(root.getCell(branchKey).get())
        val leaf = assertIs<viaduct.engine.runtime2.model.ObjectEngineResult>(branch.getCell(leafKey).get())

        assertEquals(100 + source.providedValue, leaf.getCell(resultKey).get())
    }
}

private enum class ParentQueryVariableSource(
    val displayName: String,
    val providedValue: Int,
) {
    ARGUMENT("FromArgument", 5),
    OBJECT_FIELD("FromObjectField", 7),
    QUERY_FIELD("FromQueryField", 11),
}
