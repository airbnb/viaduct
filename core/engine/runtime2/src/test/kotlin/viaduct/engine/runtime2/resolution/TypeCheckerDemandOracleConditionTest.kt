package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.contract.CheckerApplicationRecorder
import viaduct.engine.runtime2.contract.demandedTypeCheckerApplications
import viaduct.engine.runtime2.contract.hasIncludedAlternative
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

open class TypeCheckerDemandOracleConditionTest : ResolutionDispatcherResource {
    protected open val precomputedConstructionDemand = false

    @Test
    fun `oracle retains a successful alternative beside a failed binding`() {
        val world = TestWorld.fromSDL("type Query { value: Boolean! }")
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val owner = ResolverOccurrenceId.at(root, emptyList())
        val field = world.schema.requireObjectField("Query", "value")
        val failed = Arguments.Variable.of(field, "failed").instantiate(owner)
        val enabled = Arguments.Variable.of(field, "enabled").instantiate(owner)
        val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand))
        operation.variableBindings.bindVariable(requireNotNull(failed.instanceId), VariableBinding.Error)
        operation.variableBindings.bindVariable(requireNotNull(enabled.instanceId), true)
        val condition =
            InclusionCondition.requires(mapOf(failed to true))
                .or(InclusionCondition.requires(mapOf(enabled to true)))

        assertTrue(condition.hasIncludedAlternative(operation.variableBindings))
    }

    @Test
    fun `excluded symbolic error owner does not turn overlapping raw demand into checked demand`() {
        exercise(enabled = null)
    }

    @Test
    fun `from-path argument error preserves both excluded and included owner demand`() {
        listOf(false, true).forEach { exercise(it) }
    }

    @Test
    fun `checker argument failure still contributes its declared Query input demand`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              trigger: Int! @resolver(
                of: "source checked(value: ${'$'}bad)"
                pathVars: [{name: "bad", path: ["source"]}]
                script: "1"
              )
              source: Int! @resolver(script: "error")
              checked(value: Int!): Int! @resolver(script: "2") @checker(
                inputs: [{name: "input", qf: "raw { active }"}]
                script: "throw 'Error-valued arguments must prevent invocation'"
              )
              raw: Raw! @resolver(script: "{'dependency': {'token': 7}}")
            }
            type Raw {
              active: Int! @resolver(of: "dependency { token }", script: "3")
              dependency: Dependency!
            }
            type Dependency @checker(script: "allow") { token: Int! }
            """.trimIndent(),
        )
        val applications = CheckerApplicationRecorder()
        val checkerObserver = CorrectnessResolverObserver(applications)
        val operation = SharedOperationContext.create(
            world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand),
            resolutionObserver = checkerObserver,
        )
        val selections = world.schemas.operationSelectionsFrom("{ trigger }")
        val result = operation.resolveWithTestDispatcher(selections)
        assertTrue(checkerObserver.allCheckerQueryFragmentResults().isEmpty(), "No checker reached Query input materialization")
        assertEquals(1, applications.checkerApplications().size)
        assertEquals(CheckerKind.TYPE, applications.checkerApplications().single().checkerKind)
        assertEquals(applications.checkerApplications().toSet(), result.demandedTypeCheckerApplications(operation, selections))
    }

    private fun exercise(enabled: Boolean?) {
        val typeChecks = AtomicInteger()
        val variables = if (enabled == null) {
            "vars: {names: [\"bad\", \"enabled\"], script: \"error\"}"
        } else {
            "pathVars: [{name: \"bad\", path: [\"source\"]}] vars: {names: [\"enabled\"], script: \"{'enabled': $enabled}\"}"
        }
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  trigger: Int! @resolver(
                    of: "${if (enabled == null) "" else "source "}item(value: ${'$'}bad) @include(if: ${'$'}enabled) { id }"
                    $variables
                    script: "1"
                  )
                  source: Int! @resolver(script: "error")
                  other: Int! @resolver(script: "2") @checker(inputs: [{name: "raw", of: "raw { token }"}], script: "allow")
                  item(value: Int!): Item! @resolver(of: "raw { token }", script: "{'id': 1}")
                  raw: Raw! @resolver(script: "{'token': 7}")
                }
                type Item { id: Int! }
                type Raw @checker(script: "typeChecks.incrementAndGet(); allow") { token: Int! }
                """.trimIndent(),
                scriptBindings = mapOf("typeChecks" to typeChecks),
            )
        val applications = CheckerApplicationRecorder()
        val operation =
            SharedOperationContext.create(
                world.newAssumptions(usePrecomputedConstructionDemand = precomputedConstructionDemand),
                resolutionObserver = CorrectnessResolverObserver(applications),
            )
        val selections = world.schemas.operationSelectionsFrom("{ trigger other }")
        val result = operation.resolveWithTestDispatcher(selections)

        assertEquals(if (enabled == true) 1 else 0, typeChecks.get(), "Only an included error owner contributes checked Raw demand")
        assertEquals(
            applications.checkerApplications().filter { it.checkerKind == CheckerKind.TYPE }.toSet(),
            result.demandedTypeCheckerApplications(operation, selections),
            "The generated exactness oracle must retain the excluded owner's condition",
        )
    }
}

class PrecomputedTypeCheckerDemandOracleConditionTest : TypeCheckerDemandOracleConditionTest() {
    override val precomputedConstructionDemand = true
}
