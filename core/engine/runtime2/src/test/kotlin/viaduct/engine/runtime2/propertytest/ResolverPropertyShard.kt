package viaduct.engine.runtime2.propertytest

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_PROFILE_PROPERTY

const val RESOLVER_PROPERTY_IMPLEMENTATION_PROPERTY = "resolver.property.implementation"
const val RESOLVER_PROPERTY_PROFILES_PROPERTY = "resolver.property.profiles"

abstract class ResolverPropertyShard(private val shard: Int) {
    @TestFactory
    fun suites(): List<DynamicTest> =
        ResolverPropertyTestSelection.configured().select()
            .filter { descriptor -> descriptor.executionShard == shard }
            .map { descriptor -> dynamicTest(descriptor.id) { descriptor.execute() } }
}

internal data class ResolverPropertyTestSelection(
    val implementation: String? = null,
    val profiles: Set<String> = emptySet(),
    val testCase: ResolverTestCaseId? = null,
) {
    fun select(): List<ResolverPropertyTestDescriptor> {
        testCase?.let { selected -> ResolverTestSuiteDefinition.byId(selected.suite) }
        require(implementation == null || ResolverImplementation.entries.any { candidate -> candidate.id == implementation }) {
            "Unknown resolver implementation $implementation; implementations=${ResolverImplementation.entries.map(ResolverImplementation::id)}"
        }
        val implemented = resolverPropertyTestDescriptors.filter { descriptor -> implementation == null || descriptor.implementation.id == implementation }
        val missing = profiles - implemented.map { descriptor -> descriptor.suite.profile }.toSet()
        require(missing.isEmpty()) { "No property suites for implementation $implementation and profiles $missing" }
        val selected = implemented.filter { descriptor -> profiles.isEmpty() || descriptor.suite.profile in profiles }
        return if (testCase == null) {
            selected
        } else {
            selected.filter { descriptor ->
                val suite = descriptor.suite
                ResolverTestSuiteDefinition.forProfile(suite.profile, suite.config, suite.fieldCheckerMode, suite.typeCheckerMode).id == testCase.suite
            }
        }
    }

    companion object {
        fun configured(): ResolverPropertyTestSelection {
            val profiles = System.getProperty(RESOLVER_PROPERTY_PROFILES_PROPERTY)?.split(',')?.toSet().orEmpty()
            val profile = System.getProperty(RESOLVER_TEST_PROFILE_PROPERTY)
            require(profile == null || profiles.isEmpty() || profiles == setOf(profile)) { "Conflicting resolver profile selections: $profiles and $profile" }
            return ResolverPropertyTestSelection(
                implementation = System.getProperty(RESOLVER_PROPERTY_IMPLEMENTATION_PROPERTY),
                profiles = profile?.let(::setOf) ?: profiles,
                testCase = configuredResolverTestCaseId(),
            )
        }
    }
}
