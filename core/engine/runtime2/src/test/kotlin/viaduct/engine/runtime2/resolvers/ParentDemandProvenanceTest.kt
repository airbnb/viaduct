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
        assertNestedParentDemand(queryRooted = false, checked = true)
    }

    @Test
    fun `nested checked Query parent demand remains checked`() {
        assertNestedParentDemand(queryRooted = true, checked = true)
    }

    @Test
    fun `nested unchecked object parent demand remains unchecked`() {
        assertNestedParentDemand(queryRooted = false, checked = false)
    }

    @Test
    fun `nested unchecked Query parent demand remains unchecked`() {
        assertNestedParentDemand(queryRooted = true, checked = false)
    }

    private fun assertNestedParentDemand(
        queryRooted: Boolean,
        checked: Boolean,
    ) {
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
              displayName: String @resolver(of: "parent { name }", result: null)
            }
            """.trimIndent(),
        )
        val schema = worldFixture.schemas
        val queryType = schema.loweredSchema.requireQueryTypeDef()
        val organizationType = schema.loweredSchema.requireObjectField("Organization", "company").containingDef
        val original = schema.fragmentFrom(
            "fragment F on Query { organization { company { displayName } } }",
        ).subselections
        val demand = if (checked) Demand.checked(original) else Demand.unchecked(original)
        val initial: OrchestrationConstructionDemand<SelectionForest> =
            if (queryRooted) {
                OrchestrationConstructionDemand(Demand.EMPTY, demand)
            } else {
                OrchestrationConstructionDemand(demand, Demand.EMPTY)
            }

        for (precomputed in listOf(false, true)) {
            val world = worldFixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val objectRoot = ObjectEngineResult.of(queryType, emptyMap())
            val queryRoot = ObjectEngineResult.of(queryType, emptyMap())
            val closed = schema.loweredSchema.objectOf("Query").closeOrchestrationConstructionDemand(
                operation = SharedOperationContext.create(world),
                objectOccurrence = OEROccurrence(objectRoot, emptyList(), objectRoot),
                queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
                initialDemand = initial,
            )
            val selected = if (queryRooted) closed.queryRooted else closed.objectRooted
            val other = if (queryRooted) closed.objectRooted else closed.queryRooted

            val retained = if (checked) selected.checked else selected.unchecked
            val otherProvenance = if (checked) selected.unchecked else selected.checked
            val organization = retained.single().subselections.merge(organizationType)
            assertEquals(setOf("company", "name"), organization.fieldNames())
            assertTrue(otherProvenance.isEmpty(), "Parent lifting changed checking provenance")
            assertTrue(other.values.isEmpty(), "Parent lifting escaped its object/Query root component")
        }
    }

    private fun ObjectSelectionForest.fieldNames(): Set<String> = groundKeys().mapTo(linkedSetOf()) { it.field.name }
}
