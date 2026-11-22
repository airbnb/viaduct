package viaduct.engine.runtime2.resolution

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import viaduct.engine.runtime2.contract.validateFromFieldBindings
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
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class FromFieldExclusionTest : ResolutionDispatcherResource {
    @Test
    fun `excluded intermediate and terminal paths bind null without invoking excluded resolvers`() {
        for (provider in ProviderFragment.entries) {
            for (segment in listOf("b", "c")) {
                for (directive in listOf("include", "skip")) {
                    val guard = "@$directive(if: ${'$'}enabled)"
                    val source = if (segment == "b") "a { b $guard { c } }" else "a { b { c $guard } }"
                    val world = world(provider, source)
                    val enabled = directive == "skip"
                    val excluded = resolve(world, enabled)
                    assertEquals(-1, excluded.value, "$provider $segment $directive")
                    assertEquals(0, excluded.applications.count { it == "B/c" })
                    if (segment == "b") assertEquals(0, excluded.applications.count { it == "A/b" })
                    assertEquals(7, resolve(world, !enabled).value)
                }
            }
        }
    }

    @Test
    fun `another fragment or alias cannot include the variable's excluded path`() {
        for (provider in ProviderFragment.entries) {
            for (segment in listOf("b", "c")) {
                val source = if (segment == "b") {
                    "a { b @include(if: ${'$'}enabled) { c } }"
                } else {
                    "a { b { c @include(if: ${'$'}enabled) } }"
                }
                // The second alias requests the same physical cells inside the same Query OER too.
                val world = world(provider, "$source visible: a { b { c } }")
                val resolution = resolve(world, enabled = false, extra = "a { b { c } }")
                assertEquals(-1, resolution.value, "$provider $segment")
                assertTrue("B/c" in resolution.applications)
            }
        }
    }

    @Test
    fun `repeated path occurrences preserve ancestor condition correlations`() {
        val source = """
            a @include(if: ${'$'}enabled) { b @include(if: ${'$'}other) { c } }
            a @skip(if: ${'$'}enabled) { b @skip(if: ${'$'}other) { c } }
            visible: a { b { c } }
        """.trimIndent()
        for (provider in ProviderFragment.entries) {
            val world = world(provider, source)
            for (enabled in listOf(false, true)) for (other in listOf(false, true)) {
                assertEquals(if (enabled == other) 7 else -1, resolve(world, enabled, other).value)
            }
        }
    }

    @Test
    fun `terminal response alias selects its own guard`() {
        for (provider in ProviderFragment.entries) {
            val world = world(
                provider,
                "a { b { hidden: c @include(if: ${'$'}enabled) visible: c } }",
                responsePath = listOf("a", "b", "hidden"),
            )
            assertEquals(-1, resolve(world, enabled = false).value)
            assertEquals(7, resolve(world, enabled = true).value)
        }
    }

    @Test
    fun `excluded errors do not poison a binding but included errors do`() {
        for (provider in ProviderFragment.entries) {
            val world = world(
                provider,
                "a { b { c @include(if: ${'$'}enabled) } } visible: a { b { c } }",
                errorAt = "c",
            )
            assertEquals(-1, resolve(world, enabled = false).value)
            assertIs<ErrorEngineResult>(resolve(world, enabled = true).value)
        }
    }

    @Test
    fun `a path condition can depend on another from-field variable`() {
        for (provider in ProviderFragment.entries) {
            val world = world(provider, "flag a { b { c @include(if: ${'$'}enabled) } }", conditionFromField = true)
            assertEquals(-1, resolve(world, enabled = true).value)
        }
    }

    @Test
    fun `selected null intermediate and terminal still bind null`() {
        for (provider in ProviderFragment.entries) for (nullAt in listOf("b", "c")) {
            val world = world(provider, "a { b { c } }", nullAt = nullAt)
            assertEquals(-1, resolve(world, enabled = true).value)
        }
    }

    private fun world(
        provider: ProviderFragment,
        source: String,
        conditionFromField: Boolean = false,
        nullAt: String? = null,
        errorAt: String? = null,
        responsePath: List<String> = listOf("a", "b", "c"),
    ): TestWorld {
        val fragmentSource = "$source consume(value: ${'$'}value)".replace("\n", " ")
        val variableSource = if (provider == ProviderFragment.OBJECT) "fromObjectField" else "fromQueryField"
        val variables = buildList {
            add("""{name: "value", $variableSource: [${responsePath.joinToString { "\"$it\"" }}]}""")
            if ("${'$'}enabled" in source) {
                val definition = if (conditionFromField) """$variableSource: ["flag"]""" else """fromArgument: ["enabled"]"""
                add("""{name: "enabled", $definition}""")
            }
            if ("${'$'}other" in source) add("""{name: "other", fromArgument: ["other"]}""")
        }.joinToString("\n")
        val cScript = when {
            errorAt == "c" -> "error"
            nullAt == "c" -> "null"
            else -> "7"
        }
        return TestWorld.fromDSL(
            schemaSDL = """
                extend type Query {
                  outer(enabled: Boolean!, other: Boolean!): Int! @resolver(
                    ${if (provider == ProviderFragment.OBJECT) "of" else "qf"}: "$fragmentSource"
                    variables: [$variables]
                    script: "${if (provider == ProviderFragment.OBJECT) "objectValue" else "queryValue"}.consume"
                  )
                  consume(value: Int): Int! @resolver(script: "value == null ? -1 : value")
                  a: A! @resolver(script: "{:}")
                  flag: Boolean! @resolver(script: "false")
                }
                type A { b: B @resolver(script: "${if (nullAt == "b") "null" else "{:}"}") }
                type B { c: Int @resolver(script: "$cScript") }
            """.trimIndent(),
        )
    }

    private fun resolve(
        world: TestWorld,
        enabled: Boolean,
        other: Boolean = false,
        extra: String = ""
    ): Resolution {
        val observer = object : CorrectnessResolverObserver() {
            val applications = Collections.synchronizedList(mutableListOf<String>())

            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications += "${observation.field.containingDef.name}/${observation.field.name}"
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, resolutionObserver = observer)
        val fragment = world.schemas.fragmentFrom("fragment Query on Query { outer(enabled: $enabled, other: $other) $extra }")
        val result = operation.resolveWithTestDispatcher(fragment.subselections)
        result.validateFromFieldBindings(operation, observer.invokedResolverOccurrences())
        assertTrue(result.correctResolution(operation, fragment.subselections.merge(world.schema.requireQueryTypeDef())))
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "outer"), mapOf("enabled" to enabled, "other" to other))
        return Resolution(result.getCell(key).value.get(), observer.applications.toList())
    }

    private class Resolution(val value: Any?, val applications: List<String>)
}
