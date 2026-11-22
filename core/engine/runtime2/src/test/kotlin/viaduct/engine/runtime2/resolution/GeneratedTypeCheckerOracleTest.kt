package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.contract.CheckerApplicationRecorder
import viaduct.engine.runtime2.contract.demandedTypeCheckerApplications
import viaduct.engine.runtime2.contract.registeredCheckerApplications
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver23.resolve
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/** Review regression: generated assertions must reject type checks outside checked demand. */
class GeneratedTypeCheckerOracleTest : ResolutionDispatcherResource {
    @Test
    fun `generated oracle rejects type checks justified only by a surplus resolver invocation`() {
        listOf(false, true).forEach { symbolic ->
            val testWorld =
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      selected: Int! @resolver(script: "1")
                      surplus: Int! @resolver(of: "dependency { value }", script: "2")
                      dependency: Dependency! @resolver(script: "{'value': 3}")
                    }
                    type Dependency @checker(script: "allow") { value: Int! }
                    """.trimIndent(),
                )
            val world = testWorld.newAssumptions()
            val recorder = CheckerApplicationRecorder()
            val operation =
                SharedOperationContext.create(
                    world,
                    resolutionObserver = CorrectnessResolverObserver(recorder),
                )

            // This is the injected implementation defect: execute `surplus` even though the intended
            // client demand selects only `selected`. The surplus resolver's checked input then causes a
            // real type check for Dependency.
            val result =
                if (symbolic) {
                    operation.resolveWithTestDispatcher(testWorld.schemas.operationSelectionsFrom("{ selected surplus }"))
                } else {
                    operation.resolve(testWorld.schemas.operationSelectionsFrom("{ selected surplus }"))
                }
            val intended = testWorld.schemas.fragmentFrom("fragment Intended on Query { selected }")

            val correct = result.correctResolution(operation, intended)
            val applicationsMatch =
                recorder.hasExactlyCheckerApplications(
                    result.registeredCheckerApplications(operation),
                )
            assertTrue(correct, "correctResolution rejected the surplus resolver execution")
            assertTrue(
                applicationsMatch,
                "registered-application reconstruction rejected the surplus resolver execution",
            )
            val acceptedByGeneratedAssertions =
                correct &&
                    applicationsMatch &&
                    recorder.hasExactlyCheckerApplications(
                        result.demandedTypeCheckerApplications(
                            operation,
                            intended.subselections,
                        ),
                    )

            assertFalse(
                acceptedByGeneratedAssertions,
                "The generated oracle accepted a type check introduced only by an unnecessary resolver",
            )
        }
    }

    @Test
    fun `generated oracle rejects an unnecessary type check on a raw-only nested object`() {
        val worldFixture = TestWorld.fromDSL(
            """
            extend type Query { item: Item! @resolver(script: "{'value': 1, 'raw': {'token': 7}}") }
            type Item @checker(inputs: [{name: "input", of: "raw { token }"}], script: "allow") {
              value: Int!
              raw: Raw!
            }
            type Raw @checker(script: "allow") { token: Int! }
            """.trimIndent(),
        )
        val world = worldFixture.assumptions
        val itemType = worldFixture.schema.requireType("Item") as ViaductSchema.Object
        val rawType = worldFixture.schema.requireType("Raw") as ViaductSchema.Object
        val itemKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "item"), emptyMap())
        val valueKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "value"), emptyMap())
        val rawKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "raw"), emptyMap())
        val tokenKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Raw", "token"), emptyMap())
        val raw = ObjectEngineResult.of(
            rawType,
            values = mapOf(tokenKey to 7),
            // This is the defect injected into the completed result: Raw is reached only through
            // Item's raw checker input and must not itself be type-checked.
            typeCheckerResult = Promise.of(CheckerResult.Success),
        )
        val item = ObjectEngineResult.of(
            itemType,
            values = mapOf(valueKey to 1, rawKey to raw),
            typeCheckerResult = Promise.of(CheckerResult.Success),
        )
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), values = mapOf(itemKey to item))
        val recorder = CheckerApplicationRecorder()
        val operation = SharedOperationContext.create(
            world,
            resolutionObserver = CorrectnessResolverObserver(recorder),
        )
        listOf(
            CheckerInvocationObservation(
                CheckerKind.TYPE,
                root,
                listOf(itemKey),
                null,
                ResolverTarget.TypeCheckerTarget(itemType),
            ),
            CheckerInvocationObservation(
                CheckerKind.TYPE,
                root,
                listOf(itemKey, rawKey),
                null,
                ResolverTarget.TypeCheckerTarget(rawType),
            ),
        ).forEach { recorder.onCheckerInvocation(it, emptyMap()) }

        val selections = worldFixture.schemas.fragmentFrom("fragment Query on Query { item { value } }")
        val correct = root.correctResolution(operation, selections)
        val applicationsMatch = recorder.hasExactlyCheckerApplications(root.registeredCheckerApplications(operation))
        assertTrue(correct, "correctResolution rejected the injected result")
        assertTrue(applicationsMatch, "registered-application reconstruction rejected the injected result")
        val acceptedByGeneratedAssertions = correct && applicationsMatch &&
            recorder.hasExactlyCheckerApplications(root.demandedTypeCheckerApplications(operation, selections.subselections))

        assertFalse(
            acceptedByGeneratedAssertions,
            "The generated oracle accepted an unnecessary type check on a raw-only nested OER",
        )
    }
}
