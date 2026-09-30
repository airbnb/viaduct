@file:Suppress("ForbiddenImport")

package model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.ObjectEngineResult
import model.arg
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.merge
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import model.usedVariables
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

class TypeCheckerResolverTest {
    @Test
    fun `retains a type target and shares checker fragment mechanics`() =
        runBlocking {
            val schema = TestWorld.fromSDL(SCHEMA_SDL).schemas
            val itemType = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
            val queryType = schema.loweredSchema.requireQueryTypeDef()
            val checker =
                TypeCheckerResolver.of(
                    type = itemType,
                    queryType = queryType,
                    fragmentTemplates =
                        mapOf(
                            "access" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate =
                                        schema
                                            .fragmentFrom("fragment Input on Item { owner }")
                                            .materializeSelections,
                                    queryFragmentTemplate =
                                        schema
                                            .fragmentFrom("fragment Input on Query { viewer }")
                                            .materializeSelections,
                                ),
                        ),
                ) { inputs, executionContext ->
                    assertEquals(emptyMap(), inputs)
                    assertSame(ResolutionExecutionContext.Unsupported, executionContext)
                    CheckerResult.Success
                }

            assertEquals(ResolverTarget.TypeCheckerTarget(itemType), checker.target)
            assertEquals(
                setOf("owner"),
                checker.objectFragment
                    .merge(itemType)
                    .keys()
                    .mapTo(mutableSetOf()) { it.field.name },
            )
            assertEquals(
                setOf("viewer"),
                checker.queryFragment
                    .merge(queryType)
                    .keys()
                    .mapTo(mutableSetOf()) { it.field.name },
            )
            assertSame(
                CheckerResult.Success,
                checker(emptyMap(), ResolutionExecutionContext.Unsupported),
            )
        }

    @Test
    fun `type checker variables retain their type-checker target through lowering`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schemas
        val itemType = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
        val queryType = schema.loweredSchema.requireQueryTypeDef()
        val target = ResolverTarget.TypeCheckerTarget(itemType)
        val ownerVariable = Arguments.Variable.of(target, "ownerId")
        val viewerVariable = Arguments.Variable.of(target, "viewerId")
        val templates =
            ResolverFragmentTemplates(
                objectFragmentTemplate =
                    schema
                        .fragmentFrom(
                            "fragment Input on Item { id owner(seed: ${'$'}viewerId) }",
                            variableTarget = target,
                        ).materializeSelections,
                queryFragmentTemplate =
                    schema
                        .fragmentFrom(
                            "fragment Input on Query { viewer echo(value: ${'$'}ownerId) }",
                            variableTarget = target,
                        ).materializeSelections,
                variables =
                    mapOf(
                        ownerVariable to
                            VariableDefinition.FromField.of(
                                providerFragment = ProviderFragment.OBJECT,
                                path =
                                    listOf(
                                        ObjectEngineResult.Key.of(
                                            schema.loweredSchema.requireObjectField("Item", "id"),
                                            emptyMap(),
                                        ),
                                    ),
                                responsePath = listOf("id"),
                            ),
                        viewerVariable to
                            VariableDefinition.FromField.of(
                                providerFragment = ProviderFragment.QUERY,
                                path =
                                    listOf(
                                        ObjectEngineResult.Key.of(
                                            schema.loweredSchema.requireObjectField("Query", "viewer"),
                                            emptyMap(),
                                        ),
                                    ),
                                responsePath = listOf("viewer"),
                            ),
                    ),
            )
        val checker =
            TypeCheckerResolver.of(
                type = itemType,
                queryType = queryType,
                fragmentTemplates = mapOf("access" to templates),
            ) { _, _ -> CheckerResult.Success }

        val fragments =
            checker.instantiateFragmentsAt(
                ObjectEngineResult.of(queryType, emptyMap()),
                emptyList(),
            )

        assertEquals(
            setOf("access:ownerId"),
            fragments.objectFragment.pathVariableDefinitions.mapTo(mutableSetOf()) {
                it.variable.variableName
            },
        )
        assertEquals(
            setOf("access:viewerId"),
            fragments.queryFragment.pathVariableDefinitions.mapTo(mutableSetOf()) {
                it.variable.variableName
            },
        )
        assertEquals<Set<ResolverTarget>>(
            setOf(target),
            (
                fragments.objectFragment.constructionSelections.usedVariables() +
                    fragments.queryFragment.constructionSelections.usedVariables()
            ).mapTo(mutableSetOf()) { it.target },
        )
    }

    @Test
    fun `type checker providers receive an empty argument tuple`() =
        runBlocking {
            val schema = TestWorld.fromSDL(SCHEMA_SDL).schemas
            val itemType = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
            val target = ResolverTarget.TypeCheckerTarget(itemType)
            val variable = Arguments.Variable.of(target, "provided")
            val templates =
                ResolverFragmentTemplates(
                    objectFragmentTemplate = materializeSelectionForestOf(),
                    queryFragmentTemplate =
                        schema
                            .fragmentFrom(
                                "fragment Input on Query { echo(value: ${'$'}provided) }",
                                variableTarget = target,
                            ).materializeSelections,
                    variables = mapOf(variable to VariableDefinition.FromProvider),
                    variablesProvider = { arguments ->
                        assertEquals(emptyMap(), arguments.fieldValues)
                        mapOf("provided" to 7)
                    },
                )
            val checker =
                TypeCheckerResolver.of(
                    type = itemType,
                    queryType = schema.loweredSchema.requireQueryTypeDef(),
                    fragmentTemplates = mapOf("access" to templates),
                ) { _, _ -> CheckerResult.Success }

            assertEquals(mapOf("access:provided" to 7), checker.provideVariables())
        }

    @Test
    fun `type checker rejects field-owned and from-argument variables`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schemas
        val itemType = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
        val queryType = schema.loweredSchema.requireQueryTypeDef()
        val checkedField = schema.loweredSchema.requireObjectField("Item", "secured")
        val argument = requireNotNull(checkedField.arg("seed"))

        fun templates(target: ResolverTarget.FieldCheckerTarget): ResolverFragmentTemplates {
            val variable = Arguments.Variable.of(target, "seed")
            return ResolverFragmentTemplates(
                objectFragmentTemplate =
                    schema
                        .fragmentFrom(
                            "fragment Input on Item { owner(seed: ${'$'}seed) }",
                            variableTarget = target,
                        ).materializeSelections,
                queryFragmentTemplate = materializeSelectionForestOf(),
                variables = mapOf(variable to VariableDefinition.FromArgument.of(argument)),
            )
        }

        val fieldTarget = ResolverTarget.FieldCheckerTarget(checkedField)
        assertFailsWith<IllegalArgumentException> {
            TypeCheckerResolver.of(
                type = itemType,
                queryType = queryType,
                fragmentTemplates = mapOf("access" to templates(fieldTarget)),
            ) { _, _ -> CheckerResult.Success }
        }

        val typeTarget = ResolverTarget.TypeCheckerTarget(itemType)
        val variable = Arguments.Variable.of(typeTarget, "seed")
        val typeTemplates =
            ResolverFragmentTemplates(
                objectFragmentTemplate =
                    schema
                        .fragmentFrom(
                            "fragment Input on Item { owner(seed: ${'$'}seed) }",
                            variableTarget = typeTarget,
                        ).materializeSelections,
                queryFragmentTemplate = materializeSelectionForestOf(),
                variables = mapOf(variable to VariableDefinition.FromArgument.of(argument)),
            )
        assertFailsWith<IllegalArgumentException> {
            TypeCheckerResolver.of(
                type = itemType,
                queryType = queryType,
                fragmentTemplates = mapOf("access" to typeTemplates),
            ) { _, _ -> CheckerResult.Success }
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Query {
              item: Item
              viewer: Int
              echo(value: Int): Int
            }

            type Item {
              id: Int
              secured(seed: Int): Int
              owner(seed: Int): Int
            }
            """.trimIndent()
    }
}
