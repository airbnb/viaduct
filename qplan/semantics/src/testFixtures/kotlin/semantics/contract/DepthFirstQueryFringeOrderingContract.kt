package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation
import viaduct.engine.api.EngineObjectData

/** Paired Query-OER work must preserve the containing result's depth-first fringe ordering. */
interface DepthFirstQueryFringeOrderingContract : ResolverContract {
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
                    val container = schema.requireObjectField("Query", "container")
                    val dependency = schema.requireObjectField("Query", "dependency")
                    val owner = schema.requireObjectField("Container", "owner")
                    val value = schema.requireObjectField("Dependency", "value")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container")
                            },
                        dependency to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Dependency")
                            },
                        value to
                            fieldResolverOf(schema.emptyFragmentOf("Dependency")) { _, _ -> 7 },
                        owner to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Container"),
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
            fixture.assumptions,
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
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "target")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "child" setTo objectOf("Child")
                                }
                            },
                        schema.requireObjectField("Query", "dependency") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        target to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment TargetQuery on Query { dependency }"),
                            ) { _, query, _ -> query.selectionValues().getValue("dependency") },
                        schema.requireObjectField("Query", "after") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment After on Query { container { child { value } } target }",
                                ),
                            ) { _, _ -> 9 },
                        schema.requireObjectField("Child", "value") to
                            fieldResolverOf(schema.emptyFragmentOf("Child")) { _, _ -> 8 },
                    )
                },
            )

        resolveAndValidate(
            fixture.assumptions,
            "{ container { child { value } } target after }",
            resolverObserver = invocationObserver,
        )

        assertEquals(listOf("dependency", "container", "value", "target", "after"), applications)
    }
}
