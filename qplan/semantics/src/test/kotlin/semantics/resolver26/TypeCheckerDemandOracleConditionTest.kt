package semantics.resolver26

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.fragmentFrom
import model.parsing.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import semantics.contract.CheckerApplicationRecorder
import semantics.contract.demandedTypeCheckerApplications
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.CheckerKind
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

class TypeCheckerDemandOracleConditionTest : Resolver26DispatcherResource {
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
                                model.materializeSelectionForestOf(),
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
                                            model.materializeSelectionForestOf(),
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
