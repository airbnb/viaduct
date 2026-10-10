package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.contract.CheckerApplicationRecorder
import viaduct.engine.runtime2.contract.demandedTypeCheckerApplications
import viaduct.engine.runtime2.contract.hasIncludedAlternative
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class TypeCheckerDemandOracleConditionTest : ResolutionDispatcherResource {
    @Test
    fun `oracle retains a successful alternative beside a failed binding`() {
        val world = TestWorld.fromSDL("type Query { value: Boolean! }")
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val owner = ResolverOccurrenceId.at(root, emptyList())
        val field = world.schema.requireObjectField("Query", "value")
        val failed = Arguments.Variable.of(field, "failed").instantiate(owner)
        val enabled = Arguments.Variable.of(field, "enabled").instantiate(owner)
        val operation = SharedOperationContext.create(world.assumptions)
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
                result: 1
              )
              source: Int! @resolver(result: "ERROR")
              checked(value: Int!): Int! @resolver(result: 2)
              raw: Raw! @resolver(result: {dependency: {token: 7}})
            }
            type Raw {
              active: Int! @resolver(of: "dependency { token }", result: 3)
              dependency: Dependency!
            }
            type Dependency { token: Int! }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val field = schema.loweredSchema.requireObjectField("Query", "checked")
                mapOf(
                    field to FieldCheckerResolver.of(
                        field,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                viaduct.engine.runtime2.model.materializeSelectionForestOf(),
                                schema.fragmentFrom("fragment Input on Query { raw { active } }").materializeSelections,
                            )
                        ),
                    ) { _, _, _ -> error("Error-valued arguments must prevent invocation") }
                )
            },
            typeCheckers = { schema ->
                val type = schema.loweredSchema.requireType("Dependency") as ViaductSchema.Object
                mapOf(type to TypeCheckerResolver.of(type, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> CheckerResult.Success })
            },
        )
        val applications = CheckerApplicationRecorder()
        val checkerObserver = CorrectnessCheckerObserver(applications)
        val operation = SharedOperationContext.create(
            world.assumptions,
            resolverObserver = CorrectnessResolverObserver(),
            checkerObserver = checkerObserver,
        )
        val selections = world.schemas.operationSelectionsFrom("{ trigger }")
        val result = operation.resolveWithTestDispatcher(selections)
        assertTrue(checkerObserver.allQueryFragmentResults().isEmpty(), "No checker reached Query input materialization")
        assertEquals(1, applications.checkerApplications().size)
        assertEquals(CheckerKind.TYPE, applications.checkerApplications().single().checkerKind)
        assertEquals(applications.checkerApplications().toSet(), result.demandedTypeCheckerApplications(operation, selections))
    }

    private fun exercise(enabled: Boolean?) {
        val typeChecks = AtomicInteger()
        val variables = if (enabled == null) {
            "providerVars: {bad: \"ERROR\", enabled: false}"
        } else {
            "pathVars: [{name: \"bad\", path: [\"source\"]}] providerVars: {enabled: $enabled}"
        }
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  trigger: Int! @resolver(
                    of: "${if (enabled == null) "" else "source "}item(value: ${'$'}bad) @include(if: ${'$'}enabled) { id }"
                    $variables
                    result: 1
                  )
                  source: Int! @resolver(result: "ERROR")
                  other: Int! @resolver(result: 2)
                  item(value: Int!): Item! @resolver(of: "raw { token }", result: {id: 1})
                  raw: Raw! @resolver(result: {token: 7})
                }
                type Item { id: Int! }
                type Raw { token: Int! }
                """.trimIndent(),
                fieldCheckers = { schema ->
                    val other = schema.loweredSchema.requireObjectField("Query", "other")
                    mapOf(
                        other to
                            FieldCheckerResolver.of(
                                other,
                                schema.loweredSchema.requireQueryTypeDef(),
                                mapOf(
                                    "raw" to
                                        ResolverFragmentTemplates(
                                            schema.fragmentFrom("fragment Raw on Query { raw { token } }").materializeSelections,
                                            viaduct.engine.runtime2.model.materializeSelectionForestOf(),
                                        ),
                                ),
                            ) { _, _, _ -> CheckerResult.Success },
                    )
                },
                typeCheckers = { schema ->
                    val raw = schema.loweredSchema.requireType("Raw") as ViaductSchema.Object
                    mapOf(
                        raw to TypeCheckerResolver.of(raw, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            typeChecks.incrementAndGet()
                            CheckerResult.Success
                        },
                    )
                },
            )
        val applications = CheckerApplicationRecorder()
        val operation =
            SharedOperationContext.create(
                world.assumptions,
                resolverObserver = CorrectnessResolverObserver(),
                checkerObserver = CorrectnessCheckerObserver(applications),
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
