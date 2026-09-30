package semantics.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.ObjectSelectionForest
import model.fragmentFrom
import model.merge
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld

class ParentSuccessorDemandTest {
    @Test
    fun `selections without parent demand contribute nothing`() {
        val selections = fixture.schemas.fragmentFrom(
            "fragment F on Query { organization { name company { title user { label } } } }",
        ).subselections

        assertTrue(selections.liftParentSuccessorDemand(world).isEmpty())
    }

    @Test
    fun `grandparent lifting returns only additions and their containing paths`() {
        val selections = fixture.schemas.fragmentFrom(
            "fragment F on Query { organization { company { title user { label parent { parent { name } } } } } }",
        ).subselections

        val additions = selections.liftParentSuccessorDemand(world)
        val queryDemand = additions.merge(schema.requireQueryTypeDef())
        assertEquals(setOf("organization"), queryDemand.fieldNames())
        val organizationDemand = queryDemand.field("organization").subselections.merge(organization)
        assertEquals(setOf("company", "name"), organizationDemand.fieldNames())
        val companyDemand = organizationDemand.field("company").subselections.merge(company)
        assertEquals(setOf("parent"), companyDemand.fieldNames())
        assertEquals(
            setOf("name"),
            companyDemand.field("parent").subselections.merge(organization).fieldNames(),
        )

        val combined = (selections + additions).merge(schema.requireQueryTypeDef())
            .field("organization").subselections.merge(organization)
            .field("company").subselections.merge(company)
        assertEquals(setOf("title", "user", "parent"), combined.fieldNames())
        assertEquals(
            setOf("label", "parent"),
            combined.field("user").subselections.merge(user).fieldNames(),
        )
    }

    @Test
    fun `lifted additions preserve conditions across ancestor boundaries`() {
        val selections = fixture.schemas.fragmentFrom(
            """
            fragment F on Query {
              organization @include(if: ${'$'}outer) {
                company @include(if: ${'$'}inner) {
                  user { parent { parent { name } } }
                }
              }
            }
            """.trimIndent(),
            variableField = schema.requireObjectField("Query", "organization"),
        ).subselections

        val organizationDemand = selections.liftParentSuccessorDemand(world)
            .merge(schema.requireQueryTypeDef())
            .field("organization").subselections.merge(organization)
        val ancestorName = organizationDemand.field("name")
        val intermediateName = organizationDemand.field("company").subselections.merge(company)
            .field("parent").subselections.merge(organization).field("name")

        for (outer in listOf(false, true)) {
            for (inner in listOf(false, true)) {
                val bindings = mapOf("outer" to outer, "inner" to inner)
                for (selection in listOf(ancestorName, intermediateName)) {
                    assertEquals(
                        outer && inner,
                        selection.inclusionCondition.includeWith { bindings.getValue(it.variableName) },
                        "outer=$outer, inner=$inner",
                    )
                }
            }
        }
    }

    private fun ObjectSelectionForest.field(name: String) = byKey().values.single { it.key.field.name == name }

    private fun ObjectSelectionForest.fieldNames(): Set<String> = keys().mapTo(mutableSetOf()) { it.field.name }

    private val fixture = TestWorld.fromSDL(
        schemaSDL =
            """
            directive @parent on FIELD_DEFINITION
            type Query { organization: Organization }
            type Organization { name: String, company: Company }
            type Company { parent: Organization @parent, title: String, user: User }
            type User { parent: Company @parent, label: String }
            """.trimIndent(),
    )
    private val world = fixture.assumptions
    private val schema = world.schema
    private val organization = schema.requireObjectField("Organization", "name").containingDef
    private val company = schema.requireObjectField("Company", "user").containingDef
    private val user = schema.requireObjectField("User", "label").containingDef
}
