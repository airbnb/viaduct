@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class InclusionConditionTest : ResolutionDispatcherResource {
    @Test
    fun `a failed provider inclusion condition leaves unrelated fields running`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              controller: Int!
                @resolver(
                  of: "outer @include(if: ${'$'}enabled)"
                  vars: {names: ["enabled"], script: "error"}
                  script: "1"
                )
              outer: Int!
                @resolver(
                  of: "provider consume(value: ${'$'}value)"
                  pathVars: [{name: "value", path: ["provider"]}]
                  script: "2"
                )
              provider: Int! @resolver(script: "7")
              consume(value: Int!): Int! @resolver(script: "value")
              healthy: Int! @resolver(script: "42")
            }
            """.trimIndent(),
        )

        val applications = Collections.synchronizedList(mutableListOf<String>())

        val recordingObserver = object : ResolutionObserver {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                applications += observation.field.name
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, resolutionObserver = recordingObserver)
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
                  root: Root! @resolver(script: "{:}")
                }

                type Root {
                  child: Child! @resolver(script: "{:}")
                  expensive: Int! @resolver(script: "7")
                }

                type Child {
                  parent: Root @parent
                  result: Int!
                    @resolver(
                      of: "parent { expensive @skip(if: true) }"
                      script: "1"
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
                          script: "1"
                        )
                      dependency: Int! @resolver(script: "7")
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
                      script: "1"
                    )
                  bridge(flag: Boolean!): Int!
                    @resolver(
                      of: "dependency @include(if: ${'$'}flag)"
                      script: "2"
                    )
                  dependency: Int!
                    @resolver(
                      of: "prerequisite"
                      script: "3"
                    )
                  prerequisite: Int! @resolver(script: "4")
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
            TestWorld.fromDSL(
                """
                extend type Query {
                  outer(a: Boolean!, b: Boolean!): Int! @resolver(
                    of: "left: dependency @include(if: ${'$'}a) right: dependency @include(if: ${'$'}b)"
                    script: ${"\"\"\""}
                      if ('left' =~ objectValue && 'right' !~ objectValue) { return 10; }
                      if ('left' !~ objectValue && 'right' =~ objectValue) { return 1; }
                      throw 'Unexpected conditioned aliases';
                    ${"\"\"\""}
                  )
                  dependency: Int! @resolver(script: "7")
                }
                """.trimIndent(),
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
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      outer: Int! @resolver(
                        of: "dependency @include(if: ${'$'}enabled)"
                        vars: {names: ["enabled"], script: "providerApplications.incrementAndGet(); {'enabled': $enabled}"}
                        script: "1"
                      )
                      dependency: Int! @resolver(script: "7")
                    }
                    """.trimIndent(),
                    scriptBindings = mapOf("providerApplications" to providerApplications),
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
            TestWorld.fromDSL(
                """
                extend type Query {
                  controller(enabled: Boolean!): Int! @resolver(of: "outer @include(if: ${'$'}enabled)", script: "1")
                  outer: Int! @resolver(
                    of: "dependency @include(if: ${'$'}provided)"
                    vars: {names: ["provided"], script: "providerApplications.incrementAndGet(); {'provided': true}"}
                    script: "2"
                  )
                  dependency: Int! @resolver(script: "7")
                }
                """.trimIndent(),
                scriptBindings = mapOf("providerApplications" to providerApplications),
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
            TestWorld.fromDSL(
                """
                extend type Query {
                  controller(enabled: Boolean!): Int! @resolver(of: "outer @include(if: ${'$'}enabled)", script: "1")
                  outer: Int! @resolver(
                    of: "dependency(value: ${'$'}provided)"
                    vars: {names: ["provided"], script: "providerApplications.incrementAndGet(); {'provided': 7}"}
                    script: "2"
                  )
                  dependency(value: Int!): Int! @resolver(script: "3")
                }
                """.trimIndent(),
                scriptBindings = mapOf("providerApplications" to providerApplications),
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
                          script: "1"
                        )
                      dependency: Int! @resolver(script: "7")
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
                      script: "1"
                    )
                  dependency: Int! @resolver(script: "7")
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
                    TestWorld.fromDSL(
                        """
                        extend type Query {
                          outer(enabled: Boolean!): Int! @resolver(
                            of: "${if (conditionNestedNamespace) "namespace { nested ${use.directiveSource()} { value } }" else "namespace ${use.directiveSource()} { nested { value } }"}"
                            script: "1"
                          )
                          namespace: Namespace @resolver(script: "{:}")
                        }
                        type Namespace { nested: NestedNamespace @resolver(script: "{:}") }
                        type NestedNamespace { value: Int! @resolver(script: "7") }
                        """.trimIndent(),
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
                  script: "1"
                )
              dependency: Int! @resolver(script: "7")
            }
            """.trimIndent(),
        )

    private fun fromFieldConditionWorld(
        providerFragment: ProviderFragment,
        use: ConditionUse,
    ): TestWorld =
        TestWorld.fromDSL(
            """
            extend type Query {
              outer: Int! @resolver(
                ${if (providerFragment == ProviderFragment.OBJECT) "of" else "qf"}: "flag dependency ${use.directiveSource()}"
                variables: [{name: "enabled", ${if (providerFragment == ProviderFragment.OBJECT) "fromObjectField" else "fromQueryField"}: ["flag"]}]
                script: "1"
              )
              flag: Boolean! @resolver(script: "${use.value}")
              dependency: Int! @resolver(script: "7")
            }
            """.trimIndent(),
        )

    private fun parentConditionWorld(enabled: Boolean): TestWorld =
        TestWorld.fromDSL(
            """
            extend type Query { root: Root! @resolver(script: "{'enabled': $enabled}") }
            type Root {
              enabled: Boolean!
              child: Child! @resolver(script: "{:}")
            }
            type Child {
              parent: Root @parent
              result: Int! @resolver(
                of: "parent { enabled } dependency @include(if: ${'$'}enabled)"
                variables: [{name: "enabled", fromObjectField: ["parent", "enabled"]}]
                script: "1"
              )
              dependency: Int! @resolver(script: "7")
            }
            """.trimIndent(),
        )

    private fun TestWorld.resolve(query: String): Resolution {
        val fragment = schemas.fragmentFrom(query.replace("query", "fragment Query on Query"))

        val applications =
            Collections.synchronizedList(mutableListOf<ViaductSchema.ObjectField>())

        val recordingObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications += observation.field
            }
        }
        val operation =
            SharedOperationContext.create(
                world = assumptions,
                resolutionObserver = recordingObserver,
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
