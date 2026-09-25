package model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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

class FieldCheckerTest {
    @Test
    fun `retains named fragment pairs and combines resolution demand per root`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val itemType = schema.requireType("Item") as ViaductSchema.Object
        val queryType = schema.requireQueryTypeDef()
        val ownershipInput =
            fragments(
                schema = schema,
                objectFragment = "fragment Owner on Item { owner }",
                queryFragment = "fragment Viewer on Query { viewer }",
            )
        val policyInput =
            fragments(
                schema = schema,
                objectFragment = "fragment Region on Item { region }",
                queryFragment = "fragment Policy on Query { policy }",
            )
        val emptyInput =
            ResolverFragmentTemplates(
                objectFragmentTemplate = materializeSelectionForestOf(),
                queryFragmentTemplate = materializeSelectionForestOf(),
            )
        val checker =
            FieldChecker.of(
                field = schema.requireObjectField("Item", "secured"),
                queryType = queryType,
                fragmentTemplates =
                    mapOf(
                        "ownershipInput" to ownershipInput,
                        "policyInput" to policyInput,
                        "emptyInput" to emptyInput,
                    ),
            ) { _, _, _ -> CheckerResult.Success }

        assertSame(ownershipInput, checker.fragmentTemplates.getValue("ownershipInput"))
        assertSame(policyInput, checker.fragmentTemplates.getValue("policyInput"))
        assertSame(emptyInput, checker.fragmentTemplates.getValue("emptyInput"))
        assertEquals(
            setOf("owner", "region"),
            checker.objectFragment
                .merge(itemType)
                .keys()
                .mapTo(mutableSetOf()) { key -> key.field.name },
        )
        assertEquals(
            setOf("viewer", "policy"),
            checker.queryFragment
                .merge(queryType)
                .keys()
                .mapTo(mutableSetOf()) { key -> key.field.name },
        )
    }

    @Test
    fun `variables are shared across one named object and Query fragment pair`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val field = schema.requireObjectField("Item", "secured")
        val queryType = schema.requireQueryTypeDef()
        val objectVariable = Arguments.Variable.of(field, "objectId")
        val queryVariable = Arguments.Variable.of(field, "queryId")
        val objectFragment =
            "fragment AccessInput on Item { testId owner(seed: ${'$'}queryId) }"
        val queryFragment =
            "fragment AccessInput on Query { policy b(y: ${'$'}objectId) }"
        val objectDefinition: VariableDefinition =
            VariableDefinition.FromField.of(
                providerFragment = ProviderFragment.OBJECT,
                path =
                    listOf(
                        ObjectEngineResult.Key.of(
                            schema.requireObjectField("Item", "testId"),
                            emptyMap(),
                        ),
                    ),
                responsePath = listOf("testId"),
            )
        val queryDefinition: VariableDefinition =
            VariableDefinition.FromField.of(
                providerFragment = ProviderFragment.QUERY,
                path =
                    listOf(
                        ObjectEngineResult.Key.of(
                            schema.requireObjectField("Query", "policy"),
                            emptyMap(),
                        ),
                    ),
                responsePath = listOf("policy"),
            )
        val accessInput =
            ResolverFragmentTemplates(
                objectFragmentTemplate = schema.fragmentFrom(objectFragment).materializeSelections,
                queryFragmentTemplate =
                    schema
                        .fragmentFrom(queryFragment, variableField = field)
                        .materializeSelections,
                variables =
                    mapOf(
                        objectVariable to objectDefinition,
                        queryVariable to queryDefinition,
                    ),
            )
        val checker =
            FieldChecker.of(
                field = field,
                queryType = queryType,
                fragmentTemplates = mapOf("accessInput" to accessInput),
            ) { _, _, _ -> CheckerResult.Success }

        val root = ObjectEngineResult.of(queryType, emptyMap())
        val fragments = checker.instantiateFragmentsAt(root, emptyList())

        assertEquals(
            setOf("accessInput:objectId"),
            fragments.objectFragment.pathVariableDefinitions.mapTo(mutableSetOf()) {
                it.variable.variableName
            },
        )
        assertEquals(
            setOf("accessInput:queryId"),
            fragments.queryFragment.pathVariableDefinitions.mapTo(mutableSetOf()) {
                it.variable.variableName
            },
        )
        assertEquals(
            setOf("accessInput:queryId"),
            fragments.objectFragment.constructionSelections
                .usedVariables()
                .mapTo(mutableSetOf()) { it.variableName },
        )
        assertEquals(
            setOf("accessInput:objectId"),
            fragments.queryFragment.constructionSelections
                .usedVariables()
                .mapTo(mutableSetOf()) { it.variableName },
        )
        assertEquals(
            mapOf(
                objectVariable to objectDefinition,
                queryVariable to queryDefinition,
            ),
            accessInput.variables,
        )
    }

    @Test
    fun `same named variables are lowered independently in different fragment pairs`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val field = schema.requireObjectField("Item", "secured")
        val queryType = schema.requireQueryTypeDef()
        val variable = Arguments.Variable.of(field, "seed")
        val definition = VariableDefinition.FromArgument.of(requireNotNull(field.arg("seed")))
        val ownerInput =
            ResolverFragmentTemplates(
                objectFragmentTemplate =
                    schema
                        .fragmentFrom(
                            "fragment Owner on Item { owner(seed: ${'$'}seed) }",
                            variableField = field,
                        ).materializeSelections,
                queryFragmentTemplate = materializeSelectionForestOf(),
                variables = mapOf(variable to definition),
            )
        val viewerInput =
            ResolverFragmentTemplates(
                objectFragmentTemplate = materializeSelectionForestOf(),
                queryFragmentTemplate =
                    schema
                        .fragmentFrom(
                            "fragment Viewer on Query { viewer(seed: ${'$'}seed) }",
                            variableField = field,
                        ).materializeSelections,
                variables = mapOf(variable to definition),
            )
        val checker =
            FieldChecker.of(
                field = field,
                queryType = queryType,
                fragmentTemplates =
                    mapOf(
                        "ownerInput" to ownerInput,
                        "viewerInput" to viewerInput,
                    ),
            ) { _, _, _ -> CheckerResult.Success }

        val root = ObjectEngineResult.of(queryType, emptyMap())
        val fragments = checker.instantiateFragmentsAt(root, emptyList())

        assertEquals(
            setOf("ownerInput:seed"),
            fragments.objectFragment.variableDefinitions.mapTo(mutableSetOf()) {
                it.variable.variableName
            },
        )
        assertEquals(
            setOf("viewerInput:seed"),
            fragments.queryFragment.variableDefinitions.mapTo(mutableSetOf()) {
                it.variable.variableName
            },
        )
        assertEquals(setOf(variable), ownerInput.variables.keys)
        assertEquals(setOf(variable), viewerInput.variables.keys)
    }

    private fun fragments(
        schema: ViaductSchema,
        objectFragment: String,
        queryFragment: String,
    ): ResolverFragmentTemplates =
        ResolverFragmentTemplates(
            objectFragmentTemplate = schema.fragmentFrom(objectFragment).materializeSelections,
            queryFragmentTemplate = schema.fragmentFrom(queryFragment).materializeSelections,
        )

    private companion object {
        val SCHEMA_SDL =
            """
            type Query {
              item: Item
              viewer(seed: Int): Int
              policy: Int
              b(y: Int): Int
            }

            type Item {
              secured(seed: Int): Int
              owner(seed: Int): Int
              region: Int
              testId: Int
            }
            """.trimIndent()
    }
}
