@file:Suppress("ForbiddenImport")

package semantics.resolver26

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.registry.ProviderFragment
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.fromQueryField
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class InclusionConditionTest : Resolver26DispatcherResource {
    @Test
    fun `a failed provider inclusion condition leaves unrelated fields running`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              controller: Int!
                @resolver(
                  of: "outer @include(if: ${'$'}enabled)"
                  providerVars: {enabled: "ERROR"}
                  result: 1
                )
              outer: Int!
                @resolver(
                  of: "provider consume(value: ${'$'}value)"
                  pathVars: [{name: "value", path: ["provider"]}]
                  result: 2
                )
              provider: Int! @resolver(result: 7)
              consume(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
              healthy: Int! @resolver(result: 42)
            }
            """.trimIndent(),
        )

        val applications = Collections.synchronizedList(mutableListOf<String>())

        val recordingObserver = object : ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                applications += observation.field.name
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = recordingObserver)
        val result = operation.resolveWithTestDispatcher(
            world.schemas.fragmentFrom("fragment Test on Query { controller healthy }").subselections,
        )

        for (name in listOf("controller", "outer")) {
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
            assertIs<ErrorEngineResult>(result.getCell(key).value.get(), name)
            assertTrue(result.getCell(key).fieldCheckerResult.isCompleted, "$name absent checker must finish after activation failure")
        }
        val healthy = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "healthy"), emptyMap())
        assertEquals(42, result.getCell(healthy).value.get())
        assertFalse("consume" in applications)
    }

    @Test
    fun `statically excluded demand below parent does not invoke ancestor resolver`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  root: Root! @resolver(result: {})
                }

                type Root {
                  child: Child! @resolver(result: {})
                  expensive: Int! @resolver(result: 7)
                }

                type Child {
                  parent: Root @parent
                  result: Int!
                    @resolver(
                      of: "parent { expensive @skip(if: true) }"
                      result: 1
                    )
                }
                """.trimIndent(),
            )
        val resolution = world.resolve("query { root { child { result } } }")
        val expensive = world.schema.requireObjectField("Root", "expensive")

        assertTrue(resolution.correct)
        assertEquals(0, resolution.applications.count { it == expensive })
    }

    @Test
    fun `field and fragment conditions are conjoined`() {
        listOf(
            ConditionCase(fragment = false, field = false, skipped = false, included = false),
            ConditionCase(fragment = false, field = true, skipped = false, included = false),
            ConditionCase(fragment = true, field = false, skipped = false, included = false),
            ConditionCase(fragment = true, field = true, skipped = true, included = false),
            ConditionCase(fragment = true, field = true, skipped = false, included = true),
        ).forEach { case ->
            val world =
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      outer(fragment: Boolean!, field: Boolean!, skipped: Boolean!): Int!
                        @resolver(
                          of: "... @include(if: ${'$'}fragment) { dependency @include(if: ${'$'}field) @skip(if: ${'$'}skipped) }"
                          result: 1
                        )
                      dependency: Int! @resolver(result: 7)
                    }
                    """.trimIndent(),
                )
            val resolution =
                world.resolve(
                    "query { outer(fragment: ${case.fragment}, field: ${case.field}, skipped: ${case.skipped}) }",
                )
            val dependency = world.schema.requireObjectField("Query", "dependency")

            assertEquals(
                if (case.included) 1 else 0,
                resolution.applications.count { it == dependency },
                case.toString(),
            )
            assertTrue(resolution.correct, case.toString())
        }
    }

    @Test
    fun `equal-key alternatives invoke their tenant resolver exactly when any condition permits`() {
        val falseWorld = alternativeWorld()
        val falseResolution = falseWorld.resolve("query { outer(a: false, b: false) }")
        val falseDependency = falseWorld.schema.requireObjectField("Query", "dependency")
        val falseCell =
            falseResolution.result.getCell(
                ObjectEngineResult.GroundKey.of(falseDependency, emptyMap()),
            )

        assertEquals(0, falseResolution.applications.count { it == falseDependency })
        assertFalse(runBlocking { falseCell.fetchActivated() })
        assertTrue(falseResolution.correct)

        val trueWorld = alternativeWorld()
        val trueResolution = trueWorld.resolve("query { outer(a: false, b: true) }")
        val trueDependency = trueWorld.schema.requireObjectField("Query", "dependency")
        val trueCell =
            trueResolution.result.getCell(
                ObjectEngineResult.GroundKey.of(trueDependency, emptyMap()),
            )

        assertEquals(1, trueResolution.applications.count { it == trueDependency })
        assertTrue(runBlocking { trueCell.fetchActivated() })
        assertTrue(trueResolution.correct)
    }

    @Test
    fun `late equal-key alternative broadens resolver prerequisites`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  outer(a: Boolean!, b: Boolean!): Int!
                    @resolver(
                      of: "dependency @include(if: ${'$'}a) bridge(flag: ${'$'}b)"
                      result: 1
                    )
                  bridge(flag: Boolean!): Int!
                    @resolver(
                      of: "dependency @include(if: ${'$'}flag)"
                      result: 2
                    )
                  dependency: Int!
                    @resolver(
                      of: "prerequisite"
                      result: 3
                    )
                  prerequisite: Int! @resolver(result: 4)
                }
                """.trimIndent(),
            )

        val resolution = world.resolve("query { outer(a: false, b: true) }")

        listOf("outer", "bridge", "dependency", "prerequisite").forEach { fieldName ->
            val field = world.schema.requireObjectField("Query", fieldName)
            assertEquals(1, resolution.applications.count { it == field }, fieldName)
        }
        assertTrue(resolution.correct)
    }

    @Test
    fun `aliases sharing one construction cell are materialized by source occurrence`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      outer(a: Boolean!, b: Boolean!): Int!
                      dependency: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        outer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Outer on Query {
                                      left: dependency @include(if: ${'$'}a)
                                      right: dependency @include(if: ${'$'}b)
                                    }
                                    """.trimIndent(),
                                    variableField = outer,
                                ),
                            ) { input, _ ->
                                when {
                                    input.isPresent("left") && !input.isPresent("right") -> 10
                                    !input.isPresent("left") && input.isPresent("right") -> 1
                                    else -> error("Unexpected conditioned aliases")
                                }
                            },
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
                variableProviders = { schema ->
                    val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                    mapOf(
                        Arguments.Variable.of(outer, "a") to schema.loweredSchema.fromArgument(outer, "a"),
                        Arguments.Variable.of(outer, "b") to schema.loweredSchema.fromArgument(outer, "b"),
                    )
                },
            )
        val resolution =
            world.resolve(
                """
                query {
                  first: outer(a: true, b: false)
                  second: outer(a: false, b: true)
                }
                """.trimIndent(),
            )
        val outer = world.schema.requireObjectField("Query", "outer")
        val dependency = world.schema.requireObjectField("Query", "dependency")

        assertEquals(
            10,
            resolution.result
                .getCell(ObjectEngineResult.GroundKey.of(outer, mapOf("a" to true, "b" to false)))
                .value
                .get(),
        )
        assertEquals(
            1,
            resolution.result
                .getCell(ObjectEngineResult.GroundKey.of(outer, mapOf("a" to false, "b" to true)))
                .value
                .get(),
        )
        assertEquals(1, resolution.applications.count { it == dependency })
        assertTrue(resolution.correct)
    }

    @Test
    fun `from-field variables apply include and skip semantics`() {
        ProviderFragment.entries.forEach { providerFragment ->
            conditionUses.forEach { use ->
                val world = fromFieldConditionWorld(providerFragment, use)
                val resolution = world.resolve("query { outer }")
                val flag = world.schema.requireObjectField("Query", "flag")
                val dependency = world.schema.requireObjectField("Query", "dependency")
                val message = "$providerFragment $use"

                assertEquals(1, resolution.applications.count { it == flag }, message)
                assertEquals(
                    if (use.included) 1 else 0,
                    resolution.applications.count { it == dependency },
                    message,
                )
                assertTrue(resolution.correct, message)
            }
        }
    }

    @Test
    fun `a from-provider condition gates a dependency after its owning resolver activates`() {
        listOf(false, true).forEach { enabled ->
            val providerApplications = AtomicInteger()
            val world =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          outer: Int!
                          dependency: Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                        val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                        mapOf(
                            outer to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Outer on Query { dependency @include(if: ${'$'}enabled) }",
                                        variableField = outer,
                                    ),
                                ) { _, _ -> 1 }
                                    .withVariablesProvider(setOf("enabled")) {
                                        providerApplications.incrementAndGet()
                                        mapOf("enabled" to enabled)
                                    },
                            dependency to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        )
                    },
                )
            val resolution = world.resolve("query { outer }")
            val dependency = world.schema.requireObjectField("Query", "dependency")

            assertEquals(1, providerApplications.get())
            assertEquals(
                if (enabled) 1 else 0,
                resolution.applications.count { it == dependency },
            )
            assertTrue(resolution.correct)
        }
    }

    @Test
    fun `an excluded resolver does not invoke its own variables provider`() {
        val providerApplications = AtomicInteger()
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      controller(enabled: Boolean!): Int!
                      outer: Int!
                      dependency: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val controller = schema.loweredSchema.requireObjectField("Query", "controller")
                    val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        controller to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Controller on Query { outer @include(if: ${'$'}enabled) }",
                                    variableField = controller,
                                ),
                            ) { _, _ -> 1 },
                        outer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Outer on Query { dependency @include(if: ${'$'}provided) }",
                                    variableField = outer,
                                ),
                            ) { _, _ -> 2 }
                                .withVariablesProvider(setOf("provided")) {
                                    providerApplications.incrementAndGet()
                                    mapOf("provided" to true)
                                },
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
                variableProviders = { schema ->
                    val controller = schema.loweredSchema.requireObjectField("Query", "controller")
                    mapOf(
                        Arguments.Variable.of(controller, "enabled") to
                            schema.loweredSchema.fromArgument(controller, "enabled"),
                    )
                },
            )
        val resolution = world.resolve("query { controller(enabled: false) }")
        val outer = world.schema.requireObjectField("Query", "outer")
        val dependency = world.schema.requireObjectField("Query", "dependency")

        assertEquals(0, providerApplications.get())
        assertEquals(0, resolution.applications.count { it == outer })
        assertEquals(0, resolution.applications.count { it == dependency })
        assertTrue(resolution.correct)
    }

    @Test
    fun `an excluded resolver does not ground a descendant key from its provider`() {
        val providerApplications = AtomicInteger()
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      controller(enabled: Boolean!): Int!
                      outer: Int!
                      dependency(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val controller = schema.loweredSchema.requireObjectField("Query", "controller")
                    val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        controller to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Controller on Query { outer @include(if: ${'$'}enabled) }",
                                    variableField = controller,
                                ),
                            ) { _, _ -> 1 },
                        outer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Outer on Query { dependency(value: ${'$'}provided) }",
                                    variableField = outer,
                                ),
                            ) { _, _ -> 2 }
                                .withVariablesProvider(setOf("provided")) {
                                    providerApplications.incrementAndGet()
                                    mapOf("provided" to 7)
                                },
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 3 },
                    )
                },
                variableProviders = { schema ->
                    val controller = schema.loweredSchema.requireObjectField("Query", "controller")
                    mapOf(
                        Arguments.Variable.of(controller, "enabled") to
                            schema.loweredSchema.fromArgument(controller, "enabled"),
                    )
                },
            )
        val resolution = world.resolve("query { controller(enabled: false) }")
        val outer = world.schema.requireObjectField("Query", "outer")
        val dependency = world.schema.requireObjectField("Query", "dependency")

        assertEquals(0, providerApplications.get())
        assertEquals(0, resolution.applications.count { it == outer })
        assertEquals(0, resolution.applications.count { it == dependency })
        assertTrue(resolution.correct)
    }

    @Test
    fun `a variable defined beneath parent may condition a sibling dependency`() {
        listOf(false, true).forEach { enabled ->
            val world = parentConditionWorld(enabled)
            val resolution = world.resolve("query { root { child { result } } }")
            val dependency = world.schema.requireObjectField("Child", "dependency")

            assertEquals(
                if (enabled) 1 else 0,
                resolution.applications.count { it == dependency },
            )
            assertTrue(resolution.correct)
        }
    }

    @Test
    fun `conflicting requirements for one variable never invoke the dependency`() {
        listOf(false, true).forEach { enabled ->
            val world =
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      outer(enabled: Boolean!): Int!
                        @resolver(
                          of: "dependency @include(if: ${'$'}enabled) @skip(if: ${'$'}enabled)"
                          result: 1
                        )
                      dependency: Int! @resolver(result: 7)
                    }
                    """.trimIndent(),
                )
            val resolution = world.resolve("query { outer(enabled: $enabled) }")
            val dependency = world.schema.requireObjectField("Query", "dependency")

            assertEquals(0, resolution.applications.count { it == dependency })
            assertTrue(resolution.correct)
        }
    }

    @Test
    fun `include and skip must both permit inclusion`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  outer: Int!
                    @resolver(
                      of: "dependency @include(if: true) @skip(if: true)"
                      result: 1
                    )
                  dependency: Int! @resolver(result: 7)
                }
                """.trimIndent(),
            )
        val resolution = world.resolve("query { outer }")
        val dependency = world.schema.requireObjectField("Query", "dependency")

        assertFalse(dependency in resolution.applications)
        assertTrue(resolution.correct)
    }

    @Test
    fun `include and skip gate synthetic namespace resolvers at root and nested positions`() {
        listOf(false, true).forEach { conditionNestedNamespace ->
            conditionUses.forEach { use ->
                val world =
                    TestWorld.fromSDL(
                        schemaSDL =
                            """
                            type Query {
                              outer(enabled: Boolean!): Int!
                              namespace: Namespace
                            }

                            type Namespace {
                              nested: NestedNamespace
                            }

                            type NestedNamespace {
                              value: Int!
                            }
                            """.trimIndent(),
                        fieldResolvers = { schema ->
                            val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                            val namespace = schema.loweredSchema.requireObjectField("Query", "namespace")
                            val nested = schema.loweredSchema.requireObjectField("Namespace", "nested")
                            val value = schema.loweredSchema.requireObjectField("NestedNamespace", "value")
                            val namespaceSelection =
                                if (conditionNestedNamespace) {
                                    "namespace { nested ${use.directiveSource()} { value } }"
                                } else {
                                    "namespace ${use.directiveSource()} { nested { value } }"
                                }
                            mapOf(
                                outer to
                                    fieldResolverOf(
                                        schema.fragmentFrom(
                                            "fragment Outer on Query { $namespaceSelection }",
                                            variableField = outer,
                                        ),
                                    ) { _, _ -> 1 },
                                namespace to
                                    fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                        schema.loweredSchema.objectOf("Namespace")
                                    },
                                nested to
                                    fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Namespace")) { _, _ ->
                                        schema.loweredSchema.objectOf("NestedNamespace")
                                    },
                                value to
                                    fieldResolverOf(schema.loweredSchema.emptyFragmentOf("NestedNamespace")) { _, _ ->
                                        7
                                    },
                            )
                        },
                        variableProviders = { schema ->
                            val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                            mapOf(
                                Arguments.Variable.of(outer, "enabled") to
                                    schema.loweredSchema.fromArgument(outer, "enabled"),
                            )
                        },
                    )
                val resolution = world.resolve("query { outer(enabled: ${use.value}) }")
                val namespace = world.schema.requireObjectField("Query", "namespace")
                val nested = world.schema.requireObjectField("Namespace", "nested")
                val value = world.schema.requireObjectField("NestedNamespace", "value")
                val message =
                    "${use.directive}=${use.value}, nested=$conditionNestedNamespace"

                assertEquals(
                    if (conditionNestedNamespace || use.included) 1 else 0,
                    resolution.applications.count { it == namespace },
                    message,
                )
                assertEquals(
                    if (use.included) 1 else 0,
                    resolution.applications.count { it == nested },
                    message,
                )
                assertEquals(
                    if (use.included) 1 else 0,
                    resolution.applications.count { it == value },
                    message,
                )
                assertTrue(resolution.correct, message)
            }
        }
    }

    private fun alternativeWorld(): TestWorld =
        TestWorld.fromDSL(
            """
            extend type Query {
              outer(a: Boolean!, b: Boolean!): Int!
                @resolver(
                  of: "dependency @include(if: ${'$'}a) dependency @include(if: ${'$'}b)"
                  result: 1
                )
              dependency: Int! @resolver(result: 7)
            }
            """.trimIndent(),
        )

    private fun fromFieldConditionWorld(
        providerFragment: ProviderFragment,
        use: ConditionUse,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  outer: Int!
                  flag: Boolean!
                  dependency: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                val flag = schema.loweredSchema.requireObjectField("Query", "flag")
                val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                val fragmentSource =
                    "fragment Outer on Query { flag dependency ${use.directiveSource()} }"
                val fragment = schema.fragmentFrom(fragmentSource, variableField = outer)
                val outerResolver =
                    when (providerFragment) {
                        ProviderFragment.OBJECT ->
                            fieldResolverOf(fragment) { _, _ -> 1 }
                        ProviderFragment.QUERY ->
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment = fragment,
                            ) { _, _, _ -> 1 }
                    }
                mapOf(
                    outer to outerResolver,
                    flag to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> use.value },
                    dependency to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
            variableProviders = { schema ->
                val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                val fragmentSource = "fragment Outer on Query { flag }"
                val provider =
                    when (providerFragment) {
                        ProviderFragment.OBJECT ->
                            schema.fromObjectField(
                                objectFragmentSource = fragmentSource,
                                responsePath = listOf("flag"),
                                variableField = outer,
                            )
                        ProviderFragment.QUERY ->
                            schema.fromQueryField(
                                queryFragmentSource = fragmentSource,
                                responsePath = listOf("flag"),
                                variableField = outer,
                            )
                    }
                mapOf(Arguments.Variable.of(outer, "enabled") to provider)
            },
        )

    private fun parentConditionWorld(enabled: Boolean): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                directive @parent on FIELD_DEFINITION
                type Query { root: Root! }
                type Root { enabled: Boolean!, child: Child! }
                type Child {
                  parent: Root @parent
                  result: Int!
                  dependency: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val root = schema.loweredSchema.requireObjectField("Query", "root")
                val child = schema.loweredSchema.requireObjectField("Root", "child")
                val result = schema.loweredSchema.requireObjectField("Child", "result")
                val dependency = schema.loweredSchema.requireObjectField("Child", "dependency")
                mapOf(
                    root to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Root") { "enabled" setTo enabled }
                        },
                    child to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Root")) { _, _ ->
                            schema.loweredSchema.objectOf("Child")
                        },
                    result to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                """
                                fragment ResultInput on Child {
                                  parent { enabled }
                                  dependency @include(if: ${'$'}enabled)
                                }
                                """.trimIndent(),
                                variableField = result,
                            ),
                        ) { _, _ -> 1 },
                    dependency to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Child")) { _, _ -> 7 },
                )
            },
            variableProviders = { schema ->
                val result = schema.loweredSchema.requireObjectField("Child", "result")
                mapOf(
                    Arguments.Variable.of(result, "enabled") to
                        schema.fromObjectField(
                            objectFragmentSource =
                                "fragment ResultInput on Child { parent { enabled } }",
                            responsePath = listOf("parent", "enabled"),
                            variableField = result,
                        ),
                )
            },
        )

    private fun TestWorld.resolve(query: String): Resolution {
        val fragment = schemas.fragmentFrom(query.replace("query", "fragment Query on Query"))

        val applications =
            Collections.synchronizedList(mutableListOf<ViaductSchema.ObjectField>())

        val recordingObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                applications += observation.field
            }
        }
        val operation =
            SharedOperationContext.create(
                world = assumptions,
                resolverObserver = recordingObserver,
            )
        val result =
            operation.resolveWithTestDispatcher(fragment.subselections)
        val correct =
            result.correctResolution(
                operation,
                fragment.subselections.merge(schema.requireQueryTypeDef()),
            )
        return Resolution(result, correct, applications.toList())
    }

    private data class Resolution(
        val result: ObjectEngineResult,
        val correct: Boolean,
        val applications: List<ViaductSchema.ObjectField>,
    )

    private data class ConditionCase(
        val fragment: Boolean,
        val field: Boolean,
        val skipped: Boolean,
        val included: Boolean,
    )

    private data class ConditionUse(
        val directive: String,
        val value: Boolean,
        val included: Boolean,
    ) {
        fun directiveSource(): String = "@$directive(if: ${'$'}enabled)"
    }

    private companion object {
        val conditionUses =
            listOf(
                ConditionUse(directive = "include", value = false, included = false),
                ConditionUse(directive = "include", value = true, included = true),
                ConditionUse(directive = "skip", value = false, included = true),
                ConditionUse(directive = "skip", value = true, included = false),
            )
    }
}
