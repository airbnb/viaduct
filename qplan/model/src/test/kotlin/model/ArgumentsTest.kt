package model

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import model.testing.testRoot
import model.registry.ResolverTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArgumentsTest {
    @Test
    fun `empty resolved arguments reuse one value without skipping defaults or required checks`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  argumentless: Int
                  optional(value: Int): Int
                  defaulted(value: Int = 7): Int
                  required(value: Int!): Int
                }
                """.trimIndent(),
            ).schema
        val argumentless = schema.requireObjectField("Query", "argumentless")
        val optional = schema.requireObjectField("Query", "optional")
        val defaulted = schema.requireObjectField("Query", "defaulted")
        val required = schema.requireObjectField("Query", "required")

        val empty = Arguments.Resolved.of(argumentless, emptyMap())
        val optionalEmpty = Arguments.Resolved.of(optional, emptyMap())
        val defaultedArguments = Arguments.Resolved.of(defaulted, emptyMap())

        assertSame(empty, Arguments.Resolved.of(argumentless, emptyMap()))
        assertSame(empty, optionalEmpty)
        assertTrue(empty.fieldValues.isEmpty())
        assertEquals(7, defaultedArguments.fieldValues.getValue("value"))
        assertFailsWith<ClassCastException> {
            Arguments.Resolved.of(required, emptyMap())
        }
    }

    @Test
    fun `resolved arguments retain natural input data`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  consume(value: Int!): Int
                }
                """.trimIndent(),
            ).schema
        val consume = schema.requireObjectField("Query", "consume")

        val arguments = Arguments.Resolved.of(consume, mapOf("value" to 7))

        assertEquals(7, arguments.fieldValues.getValue("value"))
        assertFailsWith<ClassCastException> {
            Arguments.Resolved.of(consume, mapOf("value" to EngineErrorData.of()))
        }
    }

    @Test
    fun `template substitution preserves a present null binding`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  source(value: Int): Int
                }
                """.trimIndent(),
            ).schema
        val source = schema.requireObjectField("Query", "source")
        val template = Arguments.Variable.of(source, "value")
        val arguments = Arguments.of(source, mapOf("value" to template))

        val substituted =
            arguments.substituteTemplates(
                source,
                mapOf(template to null),
            )

        assertIs<Arguments.Resolved>(substituted)
        assertEquals(null, substituted.fieldValues.getValue("value"))
    }

    @Test
    fun `template identity contains its name and resolver target`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.requireObjectField("Query", "first")
        val second = schema.requireObjectField("Query", "second")
        val template = Arguments.Variable.of(first, "value")

        assertEquals(Arguments.Variable.of(first, "value"), template)
        assertNotEquals(Arguments.Variable.of(first, "other"), template)
        assertNotEquals(Arguments.Variable.of(second, "value"), template)
        assertNotEquals(
            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(first), "value"),
            template,
        )
        assertTrue(template.isTemplate)
        assertFalse(template.isInstantiated)
        assertNull(template.instanceId)
        assertEquals("Variable.Template(name=value, target=field-value:Query/first)", "$template")
    }

    @Test
    fun `variable instance identity contains its template and resolver occurrence`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  first: Int
                  second: Int
                }
                """.trimIndent(),
            ).schema
        val first = schema.requireObjectField("Query", "first")
        val second = schema.requireObjectField("Query", "second")
        val template = Arguments.Variable.of(first, "value")
        val path = listOf(ListEngineResult.Index.of(0))
        val resolverOccurrenceId = ResolverOccurrenceId.at(schema.testRoot(), path)
        val instance = template.instantiate(resolverOccurrenceId)

        assertEquals(template.instantiate(resolverOccurrenceId), instance)
        assertNotEquals(Arguments.Variable.of(first, "other").instantiate(resolverOccurrenceId), instance)
        assertNotEquals(Arguments.Variable.of(second, "value").instantiate(resolverOccurrenceId), instance)
        assertNotEquals(
            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(first), "value")
                .instantiate(resolverOccurrenceId),
            instance,
        )
        assertNotEquals<Arguments.Variable>(template, instance)
        assertNotEquals(
            template.instantiate(ResolverOccurrenceId.at(schema.testRoot(), emptyList())),
            instance,
        )
        assertFalse(instance.isTemplate)
        assertTrue(instance.isInstantiated)
        assertEquals(resolverOccurrenceId, instance.instanceId?.resolverOccurrenceId)
        assertFailsWith<IllegalArgumentException> {
            instance.instantiate(resolverOccurrenceId)
        }
        assertEquals(
            "Variable.Instance(name=value, target=field-value:Query/first, " +
                "id=VariableInstanceId(resolver=ResolverOccurrenceId(" +
                "root=${System.identityHashCode(schema.testRoot())}, path=[index=0]), " +
                "target=field-value:Query/first, variable=value))",
            "$instance",
        )
    }

    @Test
    fun `symbolic equality uses variable instance identity and recursive position`() {
        val world =
            TestWorld.fromSDL(
                """
                input Filter {
                  direct: Int
                  nested: [Int]
                }

                type Query {
                  source: Int
                  consume(filter: Filter): Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val firstPath = listOf(ListEngineResult.Index.of(0))
        val secondPath = listOf(ListEngineResult.Index.of(1))
        val firstOccurrence = ResolverOccurrenceId.at(world.schema.testRoot(), firstPath)
        val secondOccurrence = ResolverOccurrenceId.at(world.schema.testRoot(), secondPath)
        val firstVariable = Arguments.Variable.of(source, "value").instantiate(firstOccurrence)
        val equalFirstVariable = Arguments.Variable.of(source, "value").instantiate(firstOccurrence)
        val secondVariable = Arguments.Variable.of(source, "value").instantiate(secondOccurrence)
        val differentlyNamedVariable = Arguments.Variable.of(source, "other").instantiate(firstOccurrence)

        fun arguments(variable: Arguments.Variable): Arguments =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to variable,
                            "nested" to listOf(1, variable),
                        ),
                ),
            )

        val first = arguments(firstVariable)
        val equalFirst = arguments(equalFirstVariable)
        val second = arguments(secondVariable)
        val differentlyNamed = arguments(differentlyNamedVariable)
        val literal =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to 7,
                            "nested" to listOf(1, 7),
                        ),
                ),
            )
        val differentPositions =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to 1,
                            "nested" to listOf(firstVariable, 1),
                        ),
                ),
            )
        val firstHash = first.hashCode()
        val secondHash = second.hashCode()

        assertEquals(first, equalFirst)
        assertEquals(firstHash, equalFirst.hashCode())
        assertEquals("first", mapOf(first to "first")[equalFirst])
        assertNotEquals(first, second)
        assertNotEquals(first, differentlyNamed)
        assertNotEquals(first, literal)
        assertNotEquals(first, differentPositions)

        assertEquals(firstHash, first.hashCode())
        assertEquals(secondHash, second.hashCode())
        assertNotEquals(first, second)
    }

    @Test
    fun `root-relative hash omits only variable occurrence root identity`() {
        val schema =
            TestWorld.fromSDL(
                """
                input Filter {
                  direct: Int
                  nested: [Int]
                }

                type Query {
                  source: Int
                  consume(filter: Filter): Int
                }
                """.trimIndent(),
            ).schema
        val source = schema.requireObjectField("Query", "source")
        val consume = schema.requireObjectField("Query", "consume")
        val firstRoot = ObjectEngineResult.of(schema.requireQueryTypeDef(), values = emptyMap())
        val secondRoot = ObjectEngineResult.of(schema.requireQueryTypeDef(), values = emptyMap())
        val path = listOf(ListEngineResult.Index.of(1))
        val firstVariable =
            Arguments.Variable.of(source, "value")
                .instantiate(ResolverOccurrenceId.at(firstRoot, path))
        val secondVariable =
            Arguments.Variable.of(source, "value")
                .instantiate(ResolverOccurrenceId.at(secondRoot, path))

        fun arguments(variable: Arguments.Variable): Arguments =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to variable,
                            "nested" to listOf(1, variable),
                        ),
                ),
            )

        val first = arguments(firstVariable)
        val second = arguments(secondVariable)

        assertNotEquals(firstVariable, secondVariable)
        assertNotEquals(first, second)
        assertTrue(first.hasSameRootRelativeStructureAs(second))
        assertEquals(first.rootRelativeHashCode(), second.rootRelativeHashCode())
    }

    @Test
    fun `argument defaults normalize before structural equality`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  consume(value: Int = 7): Int
                }
                """.trimIndent(),
            ).schema
        val consume = schema.requireObjectField("Query", "consume")
        val omitted = Arguments.of(consume, emptyMap())
        val explicit = Arguments.of(consume, mapOf("value" to 7))

        assertEquals(omitted, explicit)
        assertEquals(omitted.hashCode(), explicit.hashCode())
    }

    @Test
    fun `variable instantiation recursively instantiates variable templates`() {
        val world =
            TestWorld.fromSDL(
                """
                input Filter {
                  direct: Int
                  nested: [Int]
                }

                type Query {
                  first: Int
                  consume(filter: Filter, values: [Int]): Int
                }
                """.trimIndent(),
            ).assumptions
        val schema = world.schema
        val first = schema.requireObjectField("Query", "first")
        val consume = schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(first, "value")
        val arguments =
            Arguments.of(
                consume,
                mapOf(
                    "filter" to
                        mapOf(
                            "direct" to template,
                            "nested" to listOf(template, 1),
                        ),
                    "values" to listOf(template),
                ),
            )
        val path = listOf(ListEngineResult.Index.of(2))

        val resolverOccurrenceId = ResolverOccurrenceId.at(world.schema.testRoot(), path)
        val instantiatedArguments = arguments.instantiateVariables(consume, resolverOccurrenceId)
        val variableInstance = template.instantiate(resolverOccurrenceId)
        val grounded =
            instantiatedArguments.groundWithBindings(consume) {
                VariableBinding.of(9)
            }
        val groundedArguments = assertIs<Arguments.Resolved>(grounded)
        val filter =
            assertIs<EngineInputObjectData>(
                groundedArguments.fieldValues.getValue("filter"),
            )
        val nested =
            assertIs<EngineInputListData>(filter["nested"])
        val values =
            assertIs<EngineInputListData>(groundedArguments.fieldValues.getValue("values"))

        assertEquals(9, filter["direct"])
        assertEquals(9, nested[0])
        assertEquals(1, nested[1])
        assertEquals(9, values.single())
        assertEquals(setOf(template), arguments.variableTemplates())
        assertEquals(setOf(variableInstance), instantiatedArguments.instantiatedVariables())
    }

    @Test
    fun `grounding coerces a scalar variable binding through nested input lists`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int!
                  consume(values: [[Int!]!]!): Int!
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(source, "value")
        val path = listOf(ListEngineResult.Index.of(2))
        val resolverOccurrenceId = ResolverOccurrenceId.at(world.schema.testRoot(), path)
        val arguments =
            Arguments.of(
                consume,
                mapOf("values" to template),
            ).instantiateVariables(consume, resolverOccurrenceId)
        val grounded =
            arguments.groundWithBindings(consume) {
                VariableBinding.of(9)
            }

        val values =
            assertIs<EngineInputListData>(
                assertIs<Arguments.Resolved>(grounded).fieldValues.getValue("values"),
            )
        assertEquals(listOf(listOf(9)), values)
    }

    @Test
    fun `grounding coerces each element of a shallower list through a nested input list`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  source: [Int!]!
                  consume(values: [[Int!]!]!): Int!
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(source, "value")
        val resolverOccurrenceId = ResolverOccurrenceId.at(world.schema.testRoot(), emptyList())
        val arguments =
            Arguments.of(consume, mapOf("values" to template))
                .instantiateVariables(consume, resolverOccurrenceId)
        val grounded =
            arguments.groundWithBindings(consume) {
                VariableBinding.of(listOf(4, 5))
            }

        val values =
            assertIs<EngineInputListData>(
                assertIs<Arguments.Resolved>(grounded).fieldValues.getValue("values"),
            )
        assertEquals(listOf(listOf(4), listOf(5)), values)
    }

    @Test
    fun `grounding uses the supplied argument definition instead of carried metadata`() {
        val world =
            TestWorld.fromSDL(
                """
                input FirstFilter {
                  value: Int
                }

                input SecondFilter {
                  value: Int
                }

                type Query {
                  source: Int
                  first(filter: FirstFilter): Int
                  second(filter: SecondFilter): Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val first = world.schema.requireObjectField("Query", "first")
        val second = world.schema.requireObjectField("Query", "second")
        val template = Arguments.Variable.of(source, "value")
        val path = listOf(ListEngineResult.Index.of(1))
        val resolverOccurrenceId = ResolverOccurrenceId.at(world.schema.testRoot(), path)
        val arguments =
            Arguments
                .of(
                    first,
                    mapOf("filter" to mapOf("value" to template)),
                ).instantiateVariables(first, resolverOccurrenceId)

        val grounded =
            arguments.groundWithBindings(second) {
                VariableBinding.of(9)
            }
        val groundedArguments = assertIs<Arguments.Resolved>(grounded)
        val filter =
            assertIs<EngineInputObjectData>(
                groundedArguments.fieldValues.getValue("filter"),
            )

        assertEquals(
            mapOf("value" to 9),
            filter,
        )
        assertEquals(9, filter["value"])
    }

    @Test
    fun `symbolic keys distinguish variable instances and coalesce after grounding`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                  consume(value: Int): Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(source, "value")
        val arguments =
            Arguments.Template.of(
                consume,
                Arguments.of(consume, mapOf("value" to template)),
            )
        val firstPath = listOf(ListEngineResult.Index.of(1))
        val secondPath = listOf(ListEngineResult.Index.of(2))
        val firstOccurrence = ResolverOccurrenceId.at(world.schema.testRoot(), firstPath)
        val secondOccurrence = ResolverOccurrenceId.at(world.schema.testRoot(), secondPath)
        val firstVariable = template.instantiate(firstOccurrence)
        fun symbolicKey(resolverOccurrenceId: ResolverOccurrenceId): ObjectEngineResult.ObjectKey =
            ObjectEngineResult.Key.of(
                field = consume,
                arguments = arguments.instantiate(consume, resolverOccurrenceId),
            )

        fun ground(key: ObjectEngineResult.ObjectKey): ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                field = key.field,
                arguments =
                    key.arguments.groundWithBindings(key.field) {
                        VariableBinding.of(9)
                    },
            )

        val firstOpen = symbolicKey(firstOccurrence)
        val equalFirstOpen = symbolicKey(firstOccurrence)
        val secondOpen = symbolicKey(secondOccurrence)
        val first = ground(firstOpen)
        val equalFirst = ground(equalFirstOpen)
        val second = ground(secondOpen)
        val literal = ObjectEngineResult.GroundKey.of(consume, mapOf("value" to 9))
        val reapplied =
            selectionForestOf(
                Selection.of(
                    key = first,
                    possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                    subselections = selectionForestOf(),
                ),
            ).merge(world.schema.requireQueryTypeDef())
                .groundKeys()
                .single()

        assertEquals(setOf(firstVariable), firstOpen.instantiatedVariables())
        assertEquals(firstOpen, equalFirstOpen)
        assertNotEquals(firstOpen, secondOpen)
        assertIs<ObjectEngineResult.GroundKey>(first)
        assertEquals(first, equalFirst)
        assertEquals(first, reapplied)
        assertEquals(first, second)
        assertEquals<ObjectEngineResult.GroundKey>(first, literal)
        assertEquals(
            9,
            assertIs<Arguments.Resolved>(first.arguments).fieldValues.getValue("value"),
        )
    }

    @Test
    fun `argument template rejects an existing variable instance`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                  consume(value: Int): Int
                }
                """.trimIndent(),
            ).schema
        val source = schema.requireObjectField("Query", "source")
        val consume = schema.requireObjectField("Query", "consume")
        val variableInstance =
            Arguments.Variable
                .of(source, "value")
                .instantiate(ResolverOccurrenceId.at(schema.testRoot(), emptyList()))
        val arguments = Arguments.of(consume, mapOf("value" to variableInstance))

        assertFailsWith<IllegalArgumentException> {
            Arguments.Template.of(consume, arguments)
        }
    }

    @Test
    fun `argument template validates its expected argument definition`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  intConsumer(value: Int): Int
                  stringConsumer(value: String): Int
                }
                """.trimIndent(),
            ).schema
        val intConsumer = schema.requireObjectField("Query", "intConsumer")
        val stringConsumer = schema.requireObjectField("Query", "stringConsumer")
        val arguments = Arguments.of(intConsumer, mapOf("value" to 1))

        assertFailsWith<IllegalArgumentException> {
            Arguments.Template.of(stringConsumer, arguments)
        }
    }

    @Test
    fun `open arguments recursively fetch incomplete variable instances`(): Unit =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    """
                    input Filter {
                      values: [Int]
                    }

                    type Query {
                      source: Int
                      consume(filter: Filter): Int
                    }
                    """.trimIndent(),
                ).assumptions
            val source = world.schema.requireObjectField("Query", "source")
            val consume = world.schema.requireObjectField("Query", "consume")
            val path = listOf(ListEngineResult.Index.of(1))
            val variableTemplate = Arguments.Variable.of(source, "value")
            val openArguments =
                Arguments.of(
                    consume,
                    mapOf(
                        "filter" to
                            mapOf(
                                "values" to listOf(variableTemplate),
                            ),
                    ),
                )
            val resolverOccurrenceId = ResolverOccurrenceId.at(world.schema.testRoot(), path)
            val arguments =
                Arguments.Template
                    .of(consume, openArguments)
                    .instantiate(consume, resolverOccurrenceId)
            val binding = CompletableDeferred<VariableBinding>()

            val fetched =
                async {
                    arguments.fetchGroundWithBindings(consume) {
                        binding.await()
                    }
                }

            assertFalse(fetched.isCompleted)
            binding.complete(VariableBinding.of(9))
            val grounded = fetched.await()
            val groundedArguments = assertIs<Arguments.Resolved>(grounded)
            val filter =
                assertIs<EngineInputObjectData>(
                    groundedArguments.fieldValues.getValue("filter"),
                )
            val values =
                assertIs<EngineInputListData>(
                    filter["values"],
                )
            assertEquals(9, values.single())
        }
}
