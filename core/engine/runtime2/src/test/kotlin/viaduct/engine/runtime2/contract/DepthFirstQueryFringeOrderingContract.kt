package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import viaduct.engine.api.EngineObjectData
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
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
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
            resolverObserver = invocationObserver,
        )

        assertEquals(listOf("container", "dependency", "target", "value", "after"), applications)
    }

    @Test
    fun `nested owner Query OER retains owner depth and drains Query descendants first`() {
        val applications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
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
                      dependency: Dependency!
                    }
                    type Container { owner: Int! }
                    type Dependency { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.loweredSchema.requireObjectField("Query", "container")
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    val owner = schema.loweredSchema.requireObjectField("Container", "owner")
                    val value = schema.loweredSchema.requireObjectField("Dependency", "value")
                    mapOf(
                        container to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Container")
                            },
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Dependency")
                            },
                        value to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Dependency")) { _, _ -> 7 },
                        owner to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Container"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment OwnerQuery on Query { dependency { value } }",
                                    ),
                            ) { _, query, _ ->
                                val dependencyValue =
                                    query.selectionValues().getValue("dependency") as EngineObjectData.Sync
                                dependencyValue.selectionValues().getValue("value")
                            },
                    )
                },
            )

        resolveAndValidate(
            fixture,
            "{ container { owner } }",
            resolverObserver = invocationObserver,
        )

        assertEquals(listOf("container", "dependency", "value", "owner"), applications)
    }

    @Test
    fun `shared Query OER drains before its owner without stealing the owner fringe`() {
        val applications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
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
                      dependency: Int!
                      target: Int!
                      after: Int!
                    }
                    type Container { child: Child! }
                    type Child { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.loweredSchema.requireObjectField("Query", "container")
                    val target = schema.loweredSchema.requireObjectField("Query", "target")
                    mapOf(
                        container to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Container") {
                                    "child" setTo objectOf("Child")
                                }
                            },
                        schema.loweredSchema.requireObjectField("Query", "dependency") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        target to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment TargetQuery on Query { dependency }"),
                            ) { _, query, _ -> query.selectionValues().getValue("dependency") },
                        schema.loweredSchema.requireObjectField("Query", "after") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment After on Query { container { child { value } } target }",
                                ),
                            ) { _, _ -> 9 },
                        schema.loweredSchema.requireObjectField("Child", "value") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Child")) { _, _ -> 8 },
                    )
                },
            )

        resolveAndValidate(
            fixture,
            "{ container { child { value } } target after }",
            resolverObserver = invocationObserver,
        )

        assertEquals(listOf("dependency", "container", "value", "target", "after"), applications)
    }
}
