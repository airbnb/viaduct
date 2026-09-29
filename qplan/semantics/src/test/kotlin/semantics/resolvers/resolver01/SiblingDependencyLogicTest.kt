package semantics.resolvers.resolver01

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.Assumptions
import model.Fragment
import model.ObjectEngineResult
import model.Selection
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.FieldValueResolver
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverRegistry
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.testRoot
import model.toCanonicalMaterializeSelectionForest
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class SiblingDependencyLogicTest {
    @Test
    fun `field demands an applicable top-level sibling selected by its object fragment`() {
        val world = testWorld().assumptions
        val schema = world.schema
        val logic = siblingDependencies(world)
        val consumer = schema.key(schema.requireQueryTypeDef(), "consumer")

        assertTrue(
            logic.demandsFromSibling(
                consumer,
                schema.key(schema.requireQueryTypeDef(), "sibling", mapOf("input" to 1)),
            ),
        )
        assertFalse(
            logic.demandsFromSibling(
                consumer,
                schema.key(schema.requireQueryTypeDef(), "other"),
            ),
        )
        assertFalse(
            logic.demandsFromSibling(
                consumer,
                schema.key(schema.requireQueryTypeDef(), "sibling", mapOf("input" to 2)),
            ),
        )
    }

    @Test
    fun `field does not demand a sibling hidden by an inapplicable type condition`() {
        val world = testWorld(includeInapplicableSelection = true).assumptions
        val schema = world.schema

        assertFalse(
            siblingDependencies(world).demandsFromSibling(
                schema.key(schema.requireQueryTypeDef(), "consumer"),
                schema.key(schema.requireQueryTypeDef(), "other"),
            ),
        )
    }

    @Test
    fun `sibling demand is undefined across object types`() {
        val world = testWorld().assumptions
        val schema = world.schema

        assertFailsWith<IllegalArgumentException> {
            siblingDependencies(world).demandsFromSibling(
                schema.key(schema.requireQueryTypeDef(), "consumer"),
                schema.key(
                    schema.requireType("Payload") as ViaductSchema.Object,
                    "nested",
                ),
            )
        }
    }

    @Test
    fun `Query-side ordering rejects a direct exact-key dependency cycle`() {
        val fixture =
            cycleTemplateWorld(
                templateQueryDependency = "second",
            )
        val world = fixture.withQueryFragmentOverride(targetName = "second")
        val second = world.schema.key(world.schema.requireQueryTypeDef(), "second")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                querySiblingDependencies(world).order(setOf(second))
            }

        assertContains(failure.message.orEmpty(), "contain a cycle")
    }

    @Test
    fun `Query-side ordering rejects a mixed object and Query fragment cycle`() {
        val fixture =
            cycleTemplateWorld(
                firstObjectDependency = "second",
                templateQueryDependency = "first",
            )
        val world = fixture.withQueryFragmentOverride(targetName = "second")
        val query = world.schema.requireQueryTypeDef()
        val first = world.schema.key(query, "first")
        val second = world.schema.key(query, "second")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                querySiblingDependencies(world).order(setOf(first, second))
            }

        assertContains(failure.message.orEmpty(), "contain a cycle")
    }

    @Test
    fun `Query-side ordering keeps different arguments distinct`() {
        val world = testWorld(queryFragment = true).assumptions
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val consumer = schema.key(query, "consumer")
        val demanded = schema.key(query, "sibling", mapOf("input" to 1))
        val independent = schema.key(query, "sibling", mapOf("input" to 2))

        val ordered =
            querySiblingDependencies(world).order(
                linkedSetOf(consumer, demanded, independent),
            )

        assertEquals(setOf(consumer, demanded, independent), ordered.toSet())
        assertTrue(ordered.indexOf(demanded) < ordered.indexOf(consumer))
    }

    private fun siblingDependencies(world: Assumptions): SiblingDependencyLogic {
        val root = world.schema.testRoot()
        return SiblingDependencyLogic(
            SharedOperationContext.create(world),
            OEROccurrence(root, emptyList(), root),
        )
    }

    private fun querySiblingDependencies(world: Assumptions): SiblingDependencyLogic {
        val root = world.schema.testRoot()
        return SiblingDependencyLogic(
            operation = SharedOperationContext.create(world),
            oerOccurrence = OEROccurrence(root, emptyList(), root),
            includeQueryFragments = true,
        )
    }

    private fun testWorld(
        includeInapplicableSelection: Boolean = false,
        queryFragment: Boolean = false,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA_SDL,
            fieldResolvers = { schema ->
                val consumerFragment =
                    if (includeInapplicableSelection) {
                        val query = schema.requireQueryTypeDef()
                        Fragment.of(
                            nominalType = query,
                            subselections =
                                selectionForestOf(
                                    Selection.of(
                                        key = schema.key(query, "other"),
                                        possibleTypes = emptySet(),
                                        subselections = selectionForestOf(),
                                    ),
                                ),
                        )
                    } else {
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              sibling(input: 1) {
                                nested
                              }
                            }
                            """.trimIndent(),
                        )
                    }
                val emptyFragment = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.requireField("Query", "consumer") to
                        if (queryFragment) {
                            fieldResolverOf(
                                objectFragment = emptyFragment,
                                queryFragment = consumerFragment,
                                function = { _, _, _ -> "consumer" },
                            )
                        } else {
                            fieldResolverOf(
                                objectFragment = consumerFragment,
                                function = { _, _ -> "consumer" },
                            )
                        },
                    schema.requireField("Query", "sibling") to
                        fieldResolverOf(
                            objectFragment = emptyFragment,
                            function = { _, _ -> schema.objectOf("Payload") },
                        ),
                    schema.requireField("Query", "other") to
                        fieldResolverOf(
                            objectFragment = emptyFragment,
                            function = { _, _ -> "other" },
                        ),
                )
            },
        )

    private fun cycleTemplateWorld(
        firstObjectDependency: String? = null,
        templateQueryDependency: String,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  first: Int!
                  second: Int!
                  template: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val empty = schema.emptyFragmentOf("Query")
                val firstObjectFragment =
                    firstObjectDependency?.let { dependency ->
                        schema.fragmentFrom("fragment FirstObject on Query { $dependency }")
                    } ?: empty
                val templateQueryFragment =
                    schema.fragmentFrom(
                        "fragment TemplateQuery on Query { $templateQueryDependency }",
                    )
                mapOf(
                    schema.requireObjectField("Query", "first") to
                        fieldResolverOf(firstObjectFragment) { _, _ -> 1 },
                    schema.requireObjectField("Query", "second") to
                        fieldResolverOf(empty) { _, _ -> 2 },
                    schema.requireObjectField("Query", "template") to
                        fieldResolverOf(
                            objectFragment = empty,
                            queryFragment = templateQueryFragment,
                        ) { _, _, _ -> 3 },
                )
            },
        )

    /** Reuses a validated template to construct a runtime-only cycle after registry assembly. */
    private fun TestWorld.withQueryFragmentOverride(
        targetName: String,
        templateName: String = "template",
    ): Assumptions {
        val target = schema.requireObjectField("Query", targetName)
        val targetResolver = resolverRegistry.resolver(target)
        val templateResolver =
            resolverRegistry.resolver(schema.requireObjectField("Query", templateName))
        val replacement =
            FieldValueResolver.of(
                field = target,
                fragmentTemplates =
                    ResolverFragmentTemplates(
                        objectFragmentTemplate =
                            targetResolver.objectFragment.toCanonicalMaterializeSelectionForest(),
                        queryFragmentTemplate =
                            templateResolver.queryFragment.toCanonicalMaterializeSelectionForest(),
                    ),
                queryType = schema.requireQueryTypeDef(),
                function = { _, _, _, _ -> 2 },
            )
        val overriddenRegistry =
            object : ResolverRegistry by resolverRegistry {
                override fun resolver(field: ViaductSchema.ObjectField): FieldValueResolver = if (field == target) replacement else resolverRegistry.resolver(field)
            }
        return Assumptions.of(
            schema = schema,
            resolverRegistry = overriddenRegistry,
            selectiveResolvers = assumptions.selectiveResolvers,
        )
    }

    private fun ViaductSchema.key(
        type: ViaductSchema.Object,
        fieldName: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(
            field = requireObjectField(type.name, fieldName),
            arguments = arguments,
        )

    private companion object {
        val SCHEMA_SDL =
            """
            type Payload {
              nested: String
            }

            type Query {
              consumer: String
              sibling(input: Int): Payload
              other: String
            }
            """.trimIndent()
    }
}
