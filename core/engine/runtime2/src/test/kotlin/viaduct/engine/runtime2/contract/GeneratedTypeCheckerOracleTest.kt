package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
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
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                    type Query {
                      selected: Int!
                      surplus: Int!
                      dependency: Dependency!
                    }

                    type Dependency { value: Int! }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireObjectField("Query", "selected") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                            schema.loweredSchema.requireObjectField("Query", "surplus") to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment SurplusInput on Query { dependency { value } }",
                                    ),
                                ) { _, _ -> 2 },
                            schema.loweredSchema.requireObjectField("Query", "dependency") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                    schema.loweredSchema.objectOf("Dependency") { "value" setTo 3 }
                                },
                        )
                    },
                    typeCheckers = { schema ->
                        val dependency = schema.loweredSchema.requireType("Dependency") as ViaductSchema.Object
                        mapOf(
                            dependency to
                                TypeCheckerResolver.of(
                                    dependency,
                                    schema.loweredSchema.requireQueryTypeDef(),
                                ) { _, _ -> CheckerResult.Success },
                        )
                    },
                )
            val world = testWorld.newAssumptions(selectiveResolvers = true)
            val recorder = CheckerApplicationRecorder()
            val operation =
                SharedOperationContext.create(
                    world,
                    resolverObserver = CorrectnessResolverObserver(),
                    checkerObserver = CorrectnessCheckerObserver(recorder),
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
        val worldFixture = TestWorld.fromSDL(
            schemaSDL = "type Query { item: Item! } type Item { value: Int! raw: Raw! } type Raw { token: Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "item") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Item") {
                                "value" setTo 1
                                "raw" setTo schema.loweredSchema.objectOf("Raw") { "token" setTo 7 }
                            }
                        }
                )
            },
            typeCheckers = { schema ->
                val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                val raw = schema.loweredSchema.requireType("Raw") as ViaductSchema.Object
                mapOf(
                    item to TypeCheckerResolver.of(
                        item,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Item { raw { token } }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        ),
                    ) { _, _ -> CheckerResult.Success },
                    raw to TypeCheckerResolver.of(raw, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> CheckerResult.Success },
                )
            },
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
            resolverObserver = CorrectnessResolverObserver(),
            checkerObserver = CorrectnessCheckerObserver(recorder),
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
        ).forEach(recorder::onCheckerInvocation)

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
