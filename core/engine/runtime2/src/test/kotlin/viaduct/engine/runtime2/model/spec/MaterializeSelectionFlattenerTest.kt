package viaduct.engine.runtime2.model.spec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.FieldDirectives
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.ResolverTarget
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.schema.GJSelectionParser
import viaduct.graphql.schema.ViaductSchema

class MaterializeSelectionFlattenerTest {
    @Test
    fun `Boolean literal directives lower with include and skip semantics`() {
        val world = TestWorld.fromSDL("type Query { value: String }")
        val schema = world.schemas
        val (_, selections) =
            GJSelectionParser(schema.graphQLSchema, schema.loweredSchema, emptyMap())
                .materializeSelectionsFrom(
                    """
                    fragment ResolverInput on Query {
                      includeTrue: value @include(if: true)
                      includeFalse: value @include(if: false)
                      skipTrue: value @skip(if: true)
                      skipFalse: value @skip(if: false)
                    }
                    """.trimIndent(),
                )
        val conditions =
            buildMap {
                selections.forEach { selection ->
                    put(selection.responseKey, selection.inclusionCondition)
                }
            }

        assertEquals(InclusionCondition.Always, conditions.getValue("includeTrue"))
        assertEquals(InclusionCondition.Never, conditions.getValue("includeFalse"))
        assertEquals(InclusionCondition.Never, conditions.getValue("skipTrue"))
        assertEquals(InclusionCondition.Always, conditions.getValue("skipFalse"))
    }

    @Test
    fun `fragment and field directives flatten into one conjunctive condition`() {
        val world = TestWorld.fromSDL("type Query { value: String }")
        val schema = world.schemas
        val (_, selections) =
            GJSelectionParser(
                schema.graphQLSchema,
                schema.loweredSchema,
                emptyMap(),
                variableTarget = ResolverTarget.FieldValueResolverTarget(schema.loweredSchema.requireObjectField("Query", "value")),
            )
                .materializeSelectionsFrom(
                    """
                    fragment ResolverInput on Query {
                      ... @include(if: ${'$'}outer) {
                        value @skip(if: ${'$'}inner)
                      }
                    }
                    """.trimIndent(),
                )
        val condition = selections.single().inclusionCondition
        val variables = condition.usedVariables().associateBy(Arguments.Variable::variableName)

        assertEquals(setOf("outer", "inner"), variables.keys)
        assertTrue(
            condition.include(
                mapOf(
                    variables.getValue("outer") to true,
                    variables.getValue("inner") to false,
                ),
            ),
        )
        assertFalse(
            condition.include(
                mapOf(
                    variables.getValue("outer") to true,
                    variables.getValue("inner") to true,
                ),
            ),
        )
        assertFalse(condition === InclusionCondition.Always)
    }

    @Test
    fun `aliases remain response keys while construction retains canonical fields`() {
        val fixture = SchemaFixture()
        val selections =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field("Query", "version", alias = "release"),
                    fixture.field("Query", "version"),
                ),
            )

        val collected = selections.collect(fixture.query)
        assertEquals(setOf("release", "version"), collected.responseKeys())
        assertEquals(collected["release"].key, collected["version"].key)
        assertTrue(
            selections.constructionSelections().all { selection ->
                selection.key.field == fixture.schema.requireField("Query", "version")
            },
        )
    }

    @Test
    fun `duplicate response keys collect once and combine nested source occurrences`() {
        val fixture = SchemaFixture()
        val selections =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        "Query",
                        "item",
                        alias = "chosen",
                        subselections = listOf(fixture.field("Item", "name")),
                    ),
                    fixture.field(
                        "Query",
                        "item",
                        alias = "chosen",
                        subselections = listOf(fixture.field("Item", "code")),
                    ),
                ),
            )

        assertEquals(2, selections.size)
        val collected = selections.collect(fixture.query)
        assertEquals(1, collected.size)

        val chosen = collected["chosen"]
        assertEquals(2, chosen.subselections.size)
        assertEquals(
            setOf("name", "code"),
            chosen.subselections.collect(fixture.item).responseKeys(),
        )
    }

    @Test
    fun `collected response key exposes a directive only when every occurrence exposes it`() {
        val fixture = SchemaFixture()
        val directed = testFieldDirectives("consumerPolicy")
        val undirected = testFieldDirectives()
        val mixed =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field("Query", "version", alias = "result", fieldDirectives = directed),
                    fixture.field("Query", "version", alias = "result", fieldDirectives = undirected),
                ),
            )
        val unanimous =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field("Query", "version", alias = "result", fieldDirectives = directed),
                    fixture.field("Query", "version", alias = "result", fieldDirectives = directed),
                ),
            )

        assertFalse(checkNotNull(mixed.collect(fixture.query)["result"].fieldDirectives).hasDirective("consumerPolicy"))
        assertTrue(checkNotNull(unanimous.collect(fixture.query)["result"].fieldDirectives).hasDirective("consumerPolicy"))
    }

    @Test
    fun `co-applicable response-key conflicts fail before open arguments are bound`() {
        val fixture = SchemaFixture()
        val firstVariable =
            Arguments.Variable.of(
                field = fixture.schema.requireObjectField("Query", "version"),
                variableName = "first",
            )
        val secondVariable =
            Arguments.Variable.of(
                field = fixture.schema.requireObjectField("Query", "version"),
                variableName = "second",
            )
        val conflictingArguments =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        "Query",
                        "search",
                        alias = "result",
                        arguments = mapOf("term" to firstVariable),
                    ),
                    fixture.field(
                        "Query",
                        "search",
                        alias = "result",
                        arguments = mapOf("term" to secondVariable),
                    ),
                ),
            )
        val conflictingFields =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field("Query", "version", alias = "result"),
                    fixture.field("Query", "release", alias = "result"),
                ),
            )

        assertFailsWith<IllegalArgumentException> {
            conflictingArguments.collect(fixture.query)
        }
        assertFailsWith<IllegalArgumentException> {
            conflictingFields.collect(fixture.query)
        }
    }

    @Test
    fun `open argument groups remain open through collection`() {
        val fixture = SchemaFixture()
        val variable =
            Arguments.Variable.of(
                field = fixture.schema.requireObjectField("Query", "version"),
                variableName = "term",
            )
        val selections =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        "Query",
                        "search",
                        alias = "result",
                        arguments = mapOf("term" to variable),
                    ),
                ),
            )

        val result = selections.collect(fixture.query)["result"]
        assertFalse(result.key is ObjectEngineResult.GroundKey)
        assertEquals(setOf(variable), result.key.arguments.usedVariables())
    }

    @Test
    fun `mutually exclusive alternatives share a response key after concrete filtering`() {
        val fixture = SchemaFixture()
        val selections =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        "Query",
                        "pet",
                        subselections =
                            listOf(
                                fixture.inlineFragment(
                                    fixture.dog,
                                    listOf(
                                        fixture.field(
                                            "Dog",
                                            "barkVolume",
                                            alias = "sound",
                                        ),
                                    ),
                                ),
                                fixture.inlineFragment(
                                    fixture.cat,
                                    listOf(
                                        fixture.field(
                                            "Cat",
                                            "lives",
                                            alias = "sound",
                                        ),
                                    ),
                                ),
                            ),
                    ),
                ),
            )

        val alternatives =
            selections.collect(fixture.query)["pet"].subselections
        assertEquals(2, alternatives.size)
        assertEquals(
            fixture.schema.requireObjectField("Dog", "barkVolume"),
            alternatives.collect(fixture.dog)["sound"].key.field,
        )
        assertEquals(
            fixture.schema.requireObjectField("Cat", "lives"),
            alternatives.collect(fixture.cat)["sound"].key.field,
        )
    }

    @Test
    fun `nested list selections retain response keys at every object level`() {
        val fixture = SchemaFixture()
        val selections =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        "Query",
                        "packs",
                        alias = "bundles",
                        subselections =
                            listOf(
                                fixture.field(
                                    "Pack",
                                    "pets",
                                    alias = "animals",
                                    subselections =
                                        listOf(
                                            fixture.field(
                                                "Pet",
                                                "name",
                                                alias = "label",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val bundles = selections.collect(fixture.query)["bundles"]
        assertEquals(fixture.pack, bundles.key.field.type.baseTypeDef)
        val animals = bundles.subselections.collect(fixture.pack)["animals"]
        assertEquals(fixture.pet, animals.key.field.type.baseTypeDef)
        assertEquals(
            setOf("label"),
            animals.subselections.collect(fixture.dog).responseKeys(),
        )
    }

    @Test
    fun `lowered Node fields retain their source response key`() {
        val world =
            TestWorld.fromSDL(
                """
                interface Node {
                  id: ID!
                }

                type User implements Node {
                  id: ID!
                }

                type Query {
                  user: User!
                }
                """.trimIndent(),
            )
        val schema = world.schemas
        val parsed =
            GJSelectionParser(schema.graphQLSchema, schema.loweredSchema, emptyMap())
                .specSelectionsFrom(
                    "fragment ResolverInput on Query { account: user { id } }",
                )
        val selections =
            flattenForMaterialization(schema.loweredSchema, parsed.nominalType, parsed.selections)

        val account = selections.collect(schema.loweredSchema.requireQueryTypeDef())["account"]
        assertEquals("user", account.key.field.name)
        assertEquals(
            setOf("user"),
            selections
                .constructionSelections()
                .merge(schema.loweredSchema.requireQueryTypeDef())
                .keys()
                .mapTo(linkedSetOf()) { key -> key.field.name },
        )

        assertEquals(
            setOf("id"),
            account.subselections
                .collect(schema.loweredSchema.requireType("User") as ViaductSchema.Object)
                .responseKeys(),
        )
    }

    private class SchemaFixture {
        private val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema

        val query = schema.requireQueryTypeDef()
        val item = schema.requireType("Item") as ViaductSchema.Object
        val pack = schema.requireType("Pack") as ViaductSchema.Object
        val dog = schema.requireType("Dog") as ViaductSchema.Object
        val cat = schema.requireType("Cat") as ViaductSchema.Object
        val pet = schema.requireType("Pet") as ViaductSchema.Interface

        fun field(
            containingType: String,
            fieldName: String,
            alias: String? = null,
            arguments: Map<String, Any?> = emptyMap(),
            subselections: List<SpecSelection>? = null,
            fieldDirectives: FieldDirectives? = null,
        ): SpecSelection.Field {
            val field = schema.requireField(containingType, fieldName)
            return SpecSelection.Field.of(
                alias = alias,
                field = field,
                arguments = arguments,
                subselections = subselections,
                fieldDirectives = fieldDirectives,
            )
        }

        fun inlineFragment(
            typeCondition: ViaductSchema.CompositeTypeDef?,
            selections: List<SpecSelection>,
        ): SpecSelection.InlineFragment = SpecSelection.InlineFragment.of(typeCondition, selections)

        fun flatten(
            typeInScope: ViaductSchema.CompositeTypeDef,
            selectionSet: List<SpecSelection>,
        ): MaterializeSelectionForest = flattenForMaterialization(schema, typeInScope, selectionSet)
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Pet {
              name: String!
            }

            type Dog implements Pet {
              name: String!
              barkVolume: Int
            }

            type Cat implements Pet {
              name: String!
              lives: Int
            }

            type Item {
              name: String
              code: String
            }

            type Pack {
              pets: [Pet!]!
            }

            type Query {
              version: String
              release(channel: String): String
              search(term: String): String
              item: Item
              pet: Pet
              packs: [Pack!]!
            }
            """.trimIndent()
    }
}

private fun testFieldDirectives(vararg names: String): FieldDirectives =
    object : FieldDirectives {
        override fun hasDirective(
            name: String,
            args: ((Map<String, Any?>) -> Boolean)?,
        ): Boolean = name in names && (args == null || args(emptyMap()))
    }
