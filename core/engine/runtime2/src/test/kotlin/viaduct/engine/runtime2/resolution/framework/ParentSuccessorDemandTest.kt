package viaduct.engine.runtime2.resolution.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom

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

    @Test
    fun `abstract interface key becomes ParentKey after merge specialization`() {
        val interfaceFixture = TestWorld.fromSDL(
            schemaSDL = """
                directive @parent on FIELD_DEFINITION
                type Query { root: Root }
                type Root { seed: Int child: Child }
                type Child implements HasParent { parent: Root @parent }
                interface HasParent { parent: Root }
            """.trimIndent(),
        )
        val root = interfaceFixture.schema.requireObjectField("Root", "seed").containingDef
        val child = interfaceFixture.schema.requireObjectField("Child", "parent").containingDef
        val selections = interfaceFixture.schemas.fragmentFrom(
            "fragment F on Root { child { ... on HasParent { parent { seed } } } }",
        ).subselections

        // Verify the precondition: the outer forest should see a ParentKey after merge
        val childDemand = selections.merge(root).byKey().values.single { it.key.field.name == "child" }
            .subselections.merge(child)
        val parentKey = childDemand.byKey().values.singleOrNull { it.key.field.name == "parent" }?.key
        assertTrue(
            parentKey is ObjectEngineResult.ParentKey,
            "Expected ParentKey after merge but got ${parentKey?.javaClass?.simpleName}",
        )

        val additions = selections.liftParentSuccessorDemand(interfaceFixture.assumptions)
        assertFalse(additions.isEmpty(), "Interface parent field should produce lifted additions after specialization")
        val rootDemand = (selections + additions).merge(root)
        assertTrue(
            rootDemand.fieldNames().contains("seed"),
            "Expected seed in lifted demand but got ${rootDemand.fieldNames()}",
        )
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
