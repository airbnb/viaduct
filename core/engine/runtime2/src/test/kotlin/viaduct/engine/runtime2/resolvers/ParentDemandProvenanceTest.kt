package viaduct.engine.runtime2.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
        val worldFixture = TestWorld.fromDSL(
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
        )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val queryType = schema.loweredSchema.requireQueryTypeDef()
        val organizationType = schema.loweredSchema.requireObjectField("Organization", "company").containingDef
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

        val closed = schema.loweredSchema.objectOf("Query").closeOrchestrationConstructionDemand(
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

    private fun ObjectSelectionForest.fieldNames(): Set<String> = groundKeys().mapTo(linkedSetOf()) { it.field.name }
}
