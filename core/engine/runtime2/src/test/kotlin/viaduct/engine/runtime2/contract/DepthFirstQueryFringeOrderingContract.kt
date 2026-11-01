package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

/** Paired Query-OER work must preserve the containing result's depth-first fringe ordering. */
interface DepthFirstQueryFringeOrderingContract : ResolverContract {
    @Test
    fun `reference Query fragment finishes before the enclosing passive fringe runs`() {
        val applications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications += observation.field.name
            }
        }
        val fixture =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      target: Int!
                      dependency: Int!
                      after: Int!
                    }
                    type Container {
                      left: Child!
                      values: [Int!]!
                    }
                    type Child { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val target = schema.loweredSchema.requireObjectField("Query", "target")
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "container") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Container") {
                                    "left" setTo objectOf("Child")
                                    "values" setTo
                                        listOf(
                                            RootFieldReferenceData.of(
                                                listOf(target),
                                                emptyMap(),
                                            ),
                                        )
                                }
                            },
                        schema.loweredSchema.requireObjectField("Query", "after") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment After on Query { container { left { value } values } }",
                                ),
                            ) { _, _ -> 9 },
                        target to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment TargetQuery on Query { dependency }",
                                    ),
                            ) { _, query, _ -> query.selectionValues().getValue("dependency") },
                        schema.loweredSchema.requireObjectField("Query", "dependency") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        schema.loweredSchema.requireObjectField("Child", "value") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Child")) { _, _ -> 8 },
                    )
                },
            )

        resolveAndValidate(
            fixture,
            "{ container { left { value } values } after }",
            resolutionObserver = invocationObserver,
        )

        assertEquals(listOf("container", "dependency", "target", "value", "after"), applications)
    }

    @Test
    fun `nested owner Query OER retains owner depth and drains Query descendants first`() {
        val applications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications += observation.field.name
            }
        }
        val fixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      container: Container! @resolver(result: {})
                      dependency: Dependency! @resolver(result: {})
                    }
                    type Container {
                      owner: Int! @resolver(qf: "dependency { value }", result: "value(query:dependency.value)")
                    }
                    type Dependency { value: Int! @resolver(result: 7) }
                    """.trimIndent(),
            )

        resolveAndValidate(
            fixture,
            "{ container { owner } }",
            resolutionObserver = invocationObserver,
        )

        assertEquals(listOf("container", "dependency", "value", "owner"), applications)
    }

    @Test
    fun `shared Query OER drains before its owner without stealing the owner fringe`() {
        val applications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications += observation.field.name
            }
        }
        val fixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      container: Container! @resolver(result: {child: {}})
                      dependency: Int! @resolver(result: 7)
                      target: Int! @resolver(qf: "dependency", result: "value(query:dependency)")
                      after: Int! @resolver(of: "container { child { value } } target", result: 9)
                    }
                    type Container { child: Child! }
                    type Child { value: Int! @resolver(result: 8) }
                    """.trimIndent(),
            )

        resolveAndValidate(
            fixture,
            "{ container { child { value } } target after }",
            resolutionObserver = invocationObserver,
        )

        assertEquals(listOf("dependency", "container", "value", "target", "after"), applications)
    }
}
