@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_ID_PROPERTY
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_PROFILE_PROPERTY
import viaduct.engine.runtime2.arbitrary.withResolverProperties

class ResolverPropertyTestSelectionTest {
    @Test
    fun `profile selection retains the implementation's own input configuration`() {
        val selected = ResolverPropertyTestSelection(implementation = "resolution", profiles = setOf("query-fragment")).select().single()

        assertEquals(ResolverTestSuiteDefinition.RESOLUTION_QUERY_FRAGMENT, selected.suite)
        assertEquals(true, selected.exactApplications)
        assertEquals(true, selected.fromFieldBindings)
    }

    @Test
    fun `multiple profiles select only the requested checker suites`() {
        val selected = ResolverPropertyTestSelection(
            implementation = "resolver23-precomputed",
            profiles = setOf("resolver23-field-checker-denial", "resolver23-type-checker-mixed"),
        ).select()

        assertEquals(
            setOf("resolver23-precomputed/resolver23-field-checker-denial", "resolver23-precomputed/resolver23-type-checker-mixed"),
            selected.map(ResolverPropertyTestDescriptor::id).toSet(),
        )
        assertEquals(listOf(false, false), selected.map(ResolverPropertyTestDescriptor::exactApplications))
    }

    @Test
    fun `unknown implementations and unsupported profiles fail selection`() {
        assertFailsWith<IllegalArgumentException> { ResolverPropertyTestSelection(implementation = "resoluton").select() }
        assertFailsWith<IllegalArgumentException> {
            ResolverPropertyTestSelection(implementation = "resolver06", profiles = setOf("object-fragment")).select()
        }
        assertFailsWith<IllegalArgumentException> {
            ResolverPropertyTestSelection(testCase = ResolverTestCaseId("unknown", "b001", "query")).select()
        }
    }

    @Test
    fun `a stored stress case selects its standard descriptor regardless of configured variant`(): Unit =
        runBlocking {
            withResolverProperties(
                RESOLVER_PROPERTY_IMPLEMENTATION_PROPERTY to "resolution",
                RESOLVER_TEST_CASE_ID_PROPERTY to "resolution-field-checker-mixed-stress/b001/query",
                RESOLVER_TEST_SUITE_VARIANT_PROPERTY to "standard",
            ) {
                val selected = ResolverPropertyTestSelection.configured().select().single()

                assertEquals("resolution/resolution-field-checker-mixed", selected.id)
            }
        }

    @Test
    fun `a case belonging to a separate stress harness leaves the factory empty`() {
        val selected = ResolverPropertyTestSelection(testCase = ResolverTestCaseId("resolution-stress", "b001", "query")).select()

        assertEquals(emptyList(), selected)
    }

    @Test
    fun `the factories expose a selected logical test in exactly one shard`(): Unit =
        runBlocking {
            withResolverProperties(
                RESOLVER_PROPERTY_IMPLEMENTATION_PROPERTY to "resolver08",
                RESOLVER_TEST_PROFILE_PROPERTY to "construction-witness",
            ) {
                val tests = (0 until RESOLVER_PROPERTY_SHARD_COUNT).flatMap { shard ->
                    object : ResolverPropertyShard(shard) {}.suites()
                }

                assertEquals(listOf("resolver08/construction-witness"), tests.map { test -> test.displayName })
            }
        }

    @Test
    fun `single and multiple profile selectors cannot silently disagree`(): Unit =
        runBlocking {
            withResolverProperties(
                RESOLVER_TEST_PROFILE_PROPERTY to "node",
                RESOLVER_PROPERTY_PROFILES_PROPERTY to "node,object-fragment",
            ) {
                assertFailsWith<IllegalArgumentException> { ResolverPropertyTestSelection.configured() }
            }
        }
}
