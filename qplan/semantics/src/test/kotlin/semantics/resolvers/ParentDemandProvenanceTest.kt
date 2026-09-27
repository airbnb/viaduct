package semantics.resolvers

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.SelectionForest
import model.fragmentFrom
import model.merge
import model.objectOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.shared.OrchestrationConstructionDemand
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parent lifting must preserve checking provenance. */
class ParentDemandProvenanceTest {
    @Test
    fun `nested checked object parent demand remains checked`() {
        assertNestedCheckedParentDemand(queryRooted = false)
    }

    @Test
    fun `nested checked Query parent demand remains checked`() {
        assertNestedCheckedParentDemand(queryRooted = true)
    }

    private fun assertNestedCheckedParentDemand(queryRooted: Boolean) {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              organization: Organization @resolver(result: {})
            }
            type Organization {
              name: String
              company: Company
            }
            type Company {
              parent: Organization @parent
            }
            """.trimIndent(),
        ).assumptions
        val schema = world.schema
        val queryType = schema.requireQueryTypeDef()
        val organizationType = schema.requireObjectField("Organization", "company").containingDef
        val objectRoot = ObjectEngineResult.of(queryType, emptyMap())
        val queryRoot = ObjectEngineResult.of(queryType, emptyMap())
        val original = schema.fragmentFrom(
            "fragment F on Query { organization { company { parent { name } } } }",
        ).subselections
        val initial: OrchestrationConstructionDemand<SelectionForest> =
            if (queryRooted) {
                OrchestrationConstructionDemand(Demand.EMPTY, Demand.checked(original))
            } else {
                OrchestrationConstructionDemand.checkedObject(original)
            }

        val closed = schema.objectOf("Query").closeOrchestrationConstructionDemand(
            operation = SharedOperationContext.create(world),
            objectOccurrence = OEROccurrence(objectRoot, emptyList(), objectRoot),
            queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
            initialDemand = initial,
        )
        val selected = if (queryRooted) closed.queryRooted else closed.objectRooted
        val other = if (queryRooted) closed.objectRooted else closed.queryRooted

        // This is checked client demand throughout. Lifting the parent name read back across
        // Organization.company must produce checked organization { name }, not unchecked work.
        val checkedOrganization = selected.checked.single().subselections.merge(organizationType)
        assertEquals(setOf("company", "name"), checkedOrganization.fieldNames())
        assertTrue(selected.unchecked.isEmpty(), "Checked parent demand acquired unchecked provenance")
        assertTrue(other.values.isEmpty(), "Parent lifting escaped its object/Query root component")
    }

    private fun ObjectSelectionForest.fieldNames(): Set<String> =
        groundKeys().mapTo(linkedSetOf()) { it.field.name }
}
