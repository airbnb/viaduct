package viaduct.engine.runtime2.model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.bootstrap.fromObjectField
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelection
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.instantiatedVariables
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireArg
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.engine.runtime2.model.usedVariables

class ResolverVariableInstantiationTest {
    @Test
    fun `registry normalizes both base roots while retaining symbolic keys and materialization aliases`() {
        val world = TestWorld.fromSDL(
            schemaSDL = """
                type Query { item: Item echo(value: Int): Int box: Box }
                type Item { result(seed: Int, other: Int): Int echo(value: Int): Int box: Box }
                type Box { value: Int }
            """.trimIndent(),
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val result = schema.requireObjectField("Item", "result")

                fun input(type: String) =
                    schemas.fragmentFrom(
                        """
                    fragment Input on $type {
                      literal: echo(value: 3)
                      first: echo(value: ${'$'}seed)
                      duplicate: echo(value: ${'$'}seed)
                      other: echo(value: ${'$'}other)
                      box { first: value }
                      box { second: value }
                    }
                        """.trimIndent(),
                        variableField = result,
                    )
                mapOf(result to fieldResolverOf(input("Item"), input("Query")) { _, _, _ -> null })
            },
            variableProviders = { schemas ->
                val result = schemas.loweredSchema.requireObjectField("Item", "result")
                listOf("seed", "other").associate { name ->
                    Arguments.Variable.of(result, name) to schemas.loweredSchema.fromArgument(result, name)
                }
            },
        )
        val result = world.schema.requireObjectField("Item", "result")
        val resolver = world.resolverRegistry.resolver(result)
        val occurrence = ResolverOccurrenceId.at(world.schema.testRoot(), listOf(ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 3, "other" to 3))))
        val materializations = listOf(
            resolver.instantiateObjectMaterializationSelections(occurrence),
            resolver.instantiateQueryMaterializationSelections(occurrence),
        )
        assertEquals(result.containingDef, resolver.objectFragment.type)
        assertEquals(world.schema.requireQueryTypeDef(), resolver.queryFragment.type)

        listOf(resolver.objectFragment, resolver.queryFragment).zip(materializations).forEach { (demand, materialization) ->
            assertEquals(4, demand.size)
            assertEquals(
                setOf(3, Arguments.Variable.of(result, "seed"), Arguments.Variable.of(result, "other")),
                demand.keys().filter { it.field.name == "echo" }.map { it.arguments.fieldExpressions().getValue("value") }.toSet(),
            )
            val box = demand.byKey().values.single { it.key.field.name == "box" }
            assertEquals(2, box.subselections.size)
            assertEquals(setOf("literal", "first", "duplicate", "other", "box"), materialization.collect(demand.type).responseKeys())
            assertEquals(6, materialization.size)
        }
    }

    @Test
    fun `normalized demand retains variables used only by materialization conditions on either root`() {
        val schemas = TestWorld.fromSDL("type Query { echo: Int } type Item { result(flag: Boolean!): Int echo: Int }").schemas
        val field = schemas.loweredSchema.requireObjectField("Item", "result")
        val variable = Arguments.Variable.of(field, "flag")

        fun input(type: String) =
            schemas.fragmentFrom(
                "fragment Input on $type { always: echo conditional: echo @include(if: ${'$'}flag) }",
                variableField = field,
            ).materializeSelections
        val resolver = FieldValueResolver.of(
            field,
            ResolverFragmentTemplates(input("Item"), input("Query"), mapOf(variable to VariableDefinition.FromArgument.of(field.requireArg("flag")))),
            schemas.loweredSchema.requireQueryTypeDef(),
            function = { _, _, _, _ -> null },
        )
        val occurrence = ResolverOccurrenceId.at(schemas.loweredSchema.testRoot(), listOf(ObjectEngineResult.GroundKey.of(field, mapOf("flag" to false))))
        val fragments = resolver.instantiateFragments(occurrence)
        listOf(fragments.objectFragment, fragments.queryFragment).forEach { fragment ->
            assertEquals(InclusionCondition.Always, fragment.constructionSelections.single().inclusionCondition)
            assertTrue(fragment.constructionSelections.usedVariables().isEmpty())
            assertEquals(variable.instantiate(occurrence), fragment.variableDefinitions.single().variable)
        }
        listOf(
            resolver.instantiateObjectMaterializationSelections(occurrence),
            resolver.instantiateQueryMaterializationSelections(occurrence),
        ).forEach { materialization ->
            assertEquals(2, materialization.size)
            val selections = mutableListOf<MaterializeSelection>()
            materialization.forEach(selections::add)
            assertEquals(setOf(variable.instantiate(occurrence)), selections.single { it.responseKey == "conditional" }.inclusionCondition.usedVariables())
        }
    }

    @Test
    fun `resolver occurrence identity distinguishes symbolic variables`() {
        val fragment =
            """
            fragment Result on Query {
              literal: consume(value: 3)
              symbolic: consume(value: ${'$'}seed)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int): Int!
                      consume(value: Int): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(fragment)) { _, _ ->
                                1
                            },
                        schema.loweredSchema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.loweredSchema.fromArgument(result, "seed"),
                    )
                },
            )
        val result = testWorld.schema.requireObjectField("Query", "result")
        val resolver = testWorld.resolverRegistry.resolver(result)
        val firstPath =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 3)),
            )
        val secondPath =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 4)),
            )

        val root = testWorld.schema.testRoot()
        val firstFragment =
            resolver.instantiateFragmentsAt(root, firstPath).objectFragment.constructionSelections
        val equalFirstFragment =
            resolver.instantiateFragmentsAt(root, firstPath).objectFragment.constructionSelections
        val otherFragment =
            resolver.instantiateFragmentsAt(root, secondPath).objectFragment.constructionSelections
        val firstOccurrence = ResolverOccurrenceId.at(root, firstPath)

        assertTrue(
            firstFragment.instantiatedVariables().all { variable ->
                variable.instanceId?.resolverOccurrenceId == firstOccurrence
            },
        )
        assertEquals(
            firstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            equalFirstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertNotEquals(
            firstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            otherFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertEquals(
            firstFragment
                .filter { selection -> selection.key.instantiatedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
            otherFragment
                .filter { selection -> selection.key.instantiatedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
        )
    }

    @Test
    fun `instantiates definition fragment and provider path at one occurrence`() {
        val source =
            """
            fragment Provider on Query {
              source(value: ${'$'}seed)
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int): Int!
                      source(value: Int): Int!
                      consume(value: Int): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(source)) { _, _ ->
                                1
                            },
                        schema.loweredSchema.requireObjectField("Query", "source") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                        schema.loweredSchema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.loweredSchema.fromArgument(result, "seed"),
                        Arguments.Variable.of(result, "value") to
                            schema.fromObjectField(source, listOf("source"), variableField = result),
                    )
                },
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.requireObjectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.requireObjectField("Query", "result"),
                mapOf("seed" to 3),
            )
        val sitePath = listOf(resultKey)
        val root = testWorld.schema.testRoot()
        val resolverOccurrenceId = ResolverOccurrenceId.at(root, sitePath)
        val objectFragment = resolver.instantiateFragments(resolverOccurrenceId).objectFragment
        assertEquals(
            setOf("source", "consume"),
            resolver
                .instantiateObjectMaterializationSelections(resolverOccurrenceId)
                .collect(testWorld.schema.requireQueryTypeDef())
                .responseKeys(),
        )
        val definition = objectFragment.pathVariableDefinitions.single()
        val seed =
            Arguments.Variable.of(
                testWorld.schema.requireObjectField("Query", "result"),
                "seed",
            ).instantiate(resolverOccurrenceId)

        assertEquals(
            setOf(seed),
            definition.path.single().key.instantiatedVariables(),
        )
        assertEquals(
            setOf(seed, definition.variable),
            objectFragment.constructionSelections.instantiatedVariables(),
        )
        val reconstructed = resolver.instantiatedFieldPathVariableDefinitions(resolverOccurrenceId)
            .single { it.providerFragment == ProviderFragment.OBJECT }
        assertEquals(definition.variable, reconstructed.variable)
        assertEquals(definition.path.map { it.key }, reconstructed.path.map { it.key })
    }

    @Test
    fun `variable instantiation preserves generic field directives`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @consumerPolicy on FIELD

                    type Query {
                      result(seed: Int): Int!
                      consume(value: Int): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Result on Query {
                                      renamed: consume(value: ${'$'}seed) @consumerPolicy
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ -> 1 },
                        schema.loweredSchema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.loweredSchema.fromArgument(result, "seed"),
                    )
                },
            )
        val result = testWorld.schema.requireObjectField("Query", "result")
        val path =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 3)),
            )

        val resolver = testWorld.resolverRegistry.resolver(result)
        val fragment = resolver.instantiateFragmentsAt(testWorld.schema.testRoot(), path).objectFragment
        val selection =
            resolver
                .instantiateObjectMaterializationSelections(fragment.resolverOccurrenceId)
                .collect(testWorld.schema.requireQueryTypeDef())["renamed"]
        val variables = selection.key.arguments.instantiatedVariables()

        assertTrue(checkNotNull(selection.fieldDirectives).hasDirective("consumerPolicy"))
        assertEquals(1, variables.size)
        assertTrue(
            variables.all { variable ->
                variable.instanceId?.resolverOccurrenceId ==
                    ResolverOccurrenceId.at(testWorld.schema.testRoot(), path)
            },
        )
    }
}
