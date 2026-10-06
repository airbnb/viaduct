package viaduct.engine.runtime2.model.spec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.EngineInputObjectData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.spec.flatten as flattenSpecSelections
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class SpecSelectionFlattenerTest {
    @Test
    fun `fields use object keys instead of response aliases`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(fixture.field("Query", "version", alias = "release")),
            )

        val version = result.single()
        assertEquals(fixture.schema.requireField("Query", "version"), version.key.field)
        assertEquals(emptyMap(), version.key.arguments.fieldExpressions())
        assertEquals(setOf(fixture.query), version.possibleTypes)
        assertTrue(version.isLeaf)
        assertTrue(version.subselections.isEmpty())
    }

    @Test
    fun `flattening preserves duplicate field occurrences without source order`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field("Query", "version"),
                    fixture.field("Query", "version"),
                ),
            )
        val versionField = fixture.schema.requireField("Query", "version")

        assertEquals(2, result.size)
        assertTrue(result.all { it.key.field == versionField })
    }

    @Test
    fun `field arguments become values of the canonical argument definition`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        containingType = "Query",
                        fieldName = "release",
                        arguments = mapOf("channel" to "beta"),
                    ),
                ),
            )

        val release = result.single()
        val field = fixture.schema.requireField("Query", "release")

        assertEquals(field, release.key.field)
        assertEquals(
            "beta",
            release.key.arguments.fieldExpressions()["channel"],
        )
    }

    @Test
    fun `selection keys may retain abstract nominal fields and cumulative possible types`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.query,
                listOf(
                    fixture.field(
                        containingType = "Query",
                        fieldName = "pet",
                        subselections =
                            listOf(
                                fixture.inlineFragment(
                                    typeCondition = fixture.dog,
                                    selections =
                                        listOf(
                                            fixture.inlineFragment(
                                                typeCondition = fixture.pet,
                                                selections =
                                                    listOf(
                                                        fixture.field("Pet", "name"),
                                                    ),
                                            ),
                                            fixture.inlineFragment(
                                                typeCondition = null,
                                                selections =
                                                    listOf(
                                                        fixture.field("Dog", "barkVolume"),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val pet = result.single()
        val name =
            pet.subselections.filter { it.key.field.name == "name" }.single()
        val barkVolume =
            pet.subselections.filter { it.key.field.name == "barkVolume" }.single()

        assertEquals(fixture.pet, name.key.field.containingDef)
        assertEquals(setOf(fixture.dog), name.possibleTypes)
        assertEquals(fixture.dog, barkVolume.key.field.containingDef)
        assertEquals(setOf(fixture.dog), barkVolume.possibleTypes)
    }

    @Test
    fun `descending through a field resets the child type context`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.pet,
                listOf(
                    fixture.inlineFragment(
                        typeCondition = fixture.dog,
                        selections =
                            listOf(
                                fixture.field(
                                    containingType = "Dog",
                                    fieldName = "friend",
                                    subselections =
                                        listOf(
                                            fixture.field("Pet", "name"),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val friend = result.single()
        val name = friend.subselections.single()

        assertEquals(fixture.dog, friend.key.field.containingDef)
        assertEquals(setOf(fixture.dog), friend.possibleTypes)
        assertEquals(fixture.pet, name.key.field.containingDef)
        assertEquals(setOf(fixture.dog, fixture.cat), name.possibleTypes)
    }

    @Test
    fun `pairwise-valid nested fragments may have no cumulative possible type`() {
        val fixture = SchemaFixture()
        val result =
            fixture.flatten(
                fixture.i1,
                listOf(
                    fixture.inlineFragment(
                        typeCondition = fixture.i2,
                        selections =
                            listOf(
                                fixture.inlineFragment(
                                    typeCondition = fixture.i3,
                                    selections =
                                        listOf(
                                            fixture.field("I3", "x"),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val x = result.single()
        assertEquals(fixture.i3, x.key.field.containingDef)
        assertTrue(x.possibleTypes.isEmpty())
    }

    private class SchemaFixture {
        private val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema

        val query = schema.requireQueryTypeDef()
        val dog = schema.requireType("Dog") as ViaductSchema.Object
        val cat = schema.requireType("Cat") as ViaductSchema.Object
        val pet = schema.requireType("Pet") as ViaductSchema.Interface
        val i1 = schema.requireType("I1") as ViaductSchema.Interface
        val i2 = schema.requireType("I2") as ViaductSchema.Interface
        val i3 = schema.requireType("I3") as ViaductSchema.Interface

        fun field(
            containingType: String,
            fieldName: String,
            alias: String? = null,
            arguments: EngineInputObjectData = emptyMap(),
            subselections: List<SpecSelection>? = null,
        ): SpecSelection.Field =
            schema
                .requireField(containingType, fieldName)
                .let { field ->
                    SpecSelection.Field.of(
                        alias = alias,
                        field = field,
                        arguments = arguments,
                        subselections = subselections,
                    )
                }

        fun inlineFragment(
            typeCondition: ViaductSchema.CompositeTypeDef?,
            selections: List<SpecSelection>,
        ): SpecSelection.InlineFragment = SpecSelection.InlineFragment.of(typeCondition, selections)

        fun flatten(
            typeInScope: ViaductSchema.CompositeTypeDef,
            selectionSet: List<SpecSelection>,
        ): SelectionForest = flattenSpecSelections(schema, typeInScope, selectionSet)
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
              friend: Pet
            }

            type Cat implements Pet {
              name: String!
            }

            interface I1 {
              i1: String
            }

            interface I2 {
              i2: String
            }

            interface I3 {
              x: String
            }

            type A implements I1 {
              i1: String
            }

            type B implements I1 & I2 {
              i1: String
              i2: String
            }

            type C implements I2 & I3 {
              i2: String
              x: String
            }

            type D implements I3 {
              x: String
            }

            type Query {
              version: String
              release(channel: String): String
              pet: Pet
              pairwise: I1
            }
            """.trimIndent()
    }
}
