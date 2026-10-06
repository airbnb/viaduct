@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.arg
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom

class FieldCheckerRuntimeVariablesTest {
    private val schema = TestWorld.fromSDL("type Query { checked: Int flag: Boolean echo(value: Int): Int }").schemas
    private val field = schema.loweredSchema.requireObjectField("Query", "checked")

    @Test
    fun `parent variable validation specializes abstract branches on both input roots`() {
        val mixed = TestWorld.fromSDL(
            """
            directive @parent on FIELD_DEFINITION
            type Query { nodes: [ChildIface] checked(locale: String!): String }
            interface ChildIface { edge: ParentIface }
            type ChildA implements ChildIface { edge: ParentA @parent }
            type ChildB implements ChildIface { edge: ParentB }
            interface ParentIface { id: ID }
            type ParentA implements ParentIface { id: ID child: ChildA localized(locale: String!): String }
            type ParentB implements ParentIface { id: ID localized(locale: String!): String }
            """.trimIndent(),
        ).schemas
        val checked = mixed.loweredSchema.requireObjectField("Query", "checked")
        for (root in ProviderFragment.entries) {
            fun checker(
                type: String,
                directive: String = ""
            ): FieldCheckerResolver {
                val input = mixed.fragmentFrom(
                    "fragment Input on Query { nodes { edge { ... on $type { localized(locale: ${'$'}locale) $directive } } } }",
                    variableTarget = ResolverTarget.FieldCheckerTarget(checked),
                ).materializeSelections
                val empty = materializeSelectionForestOf()
                val pair = ResolverFragmentTemplates(
                    if (root == ProviderFragment.OBJECT) input else empty,
                    if (root == ProviderFragment.QUERY) input else empty,
                    mapOf(Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(checked), "locale") to VariableDefinition.FromArgument.of(requireNotNull(checked.arg("locale")))),
                )
                return FieldCheckerResolver.of(checked, mixed.loweredSchema.requireQueryTypeDef(), mapOf("input" to pair)) { _, _, _ -> CheckerResult.Success }
            }
            assertFailsWith<IllegalArgumentException> { checker("ParentA") }
            checker("ParentB")
            checker("ParentA", "@skip(if: true)")
        }
    }

    @Test
    fun `provider names remain independent between named pairs`() =
        runBlocking {
            fun pair(value: Int) =
                ResolverFragmentTemplates(
                    schema.fragmentFrom("fragment Input on Query { echo(value: ${'$'}v) }", variableTarget = ResolverTarget.FieldCheckerTarget(field)).materializeSelections,
                    materializeSelectionForestOf(),
                    mapOf(Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "v") to VariableDefinition.FromProvider),
                    variablesProvider = { mapOf("v" to value) },
                )
            val checker = FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("left" to pair(1), "right" to pair(2))) { _, _, _ -> CheckerResult.Success }
            assertEquals(mapOf("left:v" to 1, "right:v" to 2), checker.provideVariables(Arguments.Resolved.of(field, emptyMap())))
        }

    @Test
    fun `checker rejects cycles through provider inclusion conditions`() {
        val variable = Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "flag")
        val pair = ResolverFragmentTemplates(
            schema.fragmentFrom("fragment Input on Query { flag @include(if: ${'$'}flag) }", variableTarget = ResolverTarget.FieldCheckerTarget(field)).materializeSelections,
            materializeSelectionForestOf(),
            mapOf(
                variable to VariableDefinition.FromField.of(
                    ProviderFragment.OBJECT,
                    listOf(ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Query", "flag"), emptyMap())),
                    listOf("flag"),
                )
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to pair)) { _, _, _ -> CheckerResult.Success }
        }
    }

    @Test
    fun `provider must return exactly its declared names`(): Unit =
        runBlocking {
            val pair = ResolverFragmentTemplates(
                schema.fragmentFrom("fragment Input on Query { echo(value: ${'$'}v) }", variableTarget = ResolverTarget.FieldCheckerTarget(field)).materializeSelections,
                materializeSelectionForestOf(),
                mapOf(Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "v") to VariableDefinition.FromProvider),
                variablesProvider = { mapOf("wrong" to 1) },
            )
            val checker = FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to pair)) { _, _, _ -> CheckerResult.Success }
            assertFailsWith<IllegalArgumentException> { checker.provideVariables(Arguments.Resolved.of(field, emptyMap())) }
        }
}
