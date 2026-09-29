package semantics.resolver26

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import model.Arguments
import model.EngineErrorData
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.outputValue
import model.registry.ProviderFragment
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.fromQueryField
import semantics.contract.validateFromFieldBindings
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.ResolverInvocationObservation
import semantics.shared.SharedOperationContext

class FromFieldExclusionTest : Resolver26DispatcherResource {
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
        val fragmentSource = "fragment Input on Query { $source consume(value: ${'$'}value) }"
        return TestWorld.fromSDL(
            schemaSDL = """
                type Query {
                  outer(enabled: Boolean!, other: Boolean!): Int!
                  consume(value: Int): Int!
                  a: A!
                  flag: Boolean!
                }
                type A { b: B }
                type B { c: Int }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val outer = schema.requireObjectField("Query", "outer")
                val fragment = schema.fragmentFrom(fragmentSource, variableField = outer)
                mapOf(
                    outer to fieldResolverOf(
                        objectFragment = if (provider == ProviderFragment.OBJECT) fragment else schema.emptyFragmentOf("Query"),
                        queryFragment = if (provider == ProviderFragment.QUERY) fragment else schema.emptyFragmentOf("Query"),
                    ) { obj, query, _ ->
                        (if (provider == ProviderFragment.OBJECT) obj else query).outputValue("consume")
                    },
                    schema.requireObjectField("Query", "consume") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, args ->
                        args.fieldValues.getValue("value") ?: -1
                    },
                    schema.requireObjectField("Query", "a") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> schema.objectOf("A") },
                    schema.requireObjectField("Query", "flag") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> false },
                    schema.requireObjectField("A", "b") to fieldResolverOf(schema.emptyFragmentOf("A")) { _, _ ->
                        if (nullAt == "b") null else schema.objectOf("B")
                    },
                    schema.requireObjectField("B", "c") to fieldResolverOf(schema.emptyFragmentOf("B")) { _, _ ->
                        when {
                            errorAt == "c" -> EngineErrorData.of()
                            nullAt == "c" -> null
                            else -> 7
                        }
                    },
                )
            },
            variableProviders = { schema ->
                val outer = schema.requireObjectField("Query", "outer")

                fun fromPath(path: List<String>) =
                    when (provider) {
                        ProviderFragment.OBJECT -> schema.fromObjectField(fragmentSource, path, outer)
                        ProviderFragment.QUERY -> schema.fromQueryField(fragmentSource, path, outer)
                    }
                buildMap {
                    put(Arguments.Variable.of(outer, "value"), fromPath(responsePath))
                    if ("${'$'}enabled" in source) {
                        put(Arguments.Variable.of(outer, "enabled"), if (conditionFromField) fromPath(listOf("flag")) else schema.fromArgument(outer, "enabled"))
                    }
                    if ("${'$'}other" in source) put(Arguments.Variable.of(outer, "other"), schema.fromArgument(outer, "other"))
                }
            },
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

            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                applications += "${observation.field.containingDef.name}/${observation.field.name}"
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val fragment = world.assumptions.fragmentFrom("fragment Query on Query { outer(enabled: $enabled, other: $other) $extra }")
        val result = operation.resolveWithTestDispatcher(fragment.subselections)
        result.validateFromFieldBindings(operation, observer.invokedResolverOccurrences())
        assertTrue(result.correctResolution(operation, fragment.subselections.merge(world.schema.requireQueryTypeDef())))
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "outer"), mapOf("enabled" to enabled, "other" to other))
        return Resolution(result.getCell(key).value.get(), observer.applications.toList())
    }

    private class Resolution(val value: Any?, val applications: List<String>)
}
