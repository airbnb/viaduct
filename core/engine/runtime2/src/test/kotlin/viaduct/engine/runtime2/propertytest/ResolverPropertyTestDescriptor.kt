package viaduct.engine.runtime2.propertytest

import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition as Suite

const val RESOLVER_PROPERTY_SHARD_COUNT = 8

data class ResolverPropertyTestDescriptor(
    val implementation: ResolverImplementation,
    val suite: Suite,
    val executionShard: Int,
    val exactApplications: Boolean = false,
    val fromFieldBindings: Boolean = false,
) {
    init {
        require(executionShard in 0 until RESOLVER_PROPERTY_SHARD_COUNT) { "Invalid execution shard $executionShard" }
    }

    val id: String
        get() = "${implementation.id}/${suite.id}"
}

val resolverPropertyTestDescriptors: List<ResolverPropertyTestDescriptor> =
    listOf(
        tests(
            ResolverImplementation.RESOLVER06,
            Suite.EMPTY_OBJECT_FRAGMENT to 0,
            Suite.NODE to 0,
            Suite.ROOT_FIELD_REFERENCE to 0,
            Suite.SOMETIMES_PASSIVE to 0,
        ),
        tests(
            ResolverImplementation.RESOLVER07,
            Suite.EMPTY_OBJECT_FRAGMENT to 1,
            Suite.NODE to 1,
            Suite.ROOT_FIELD_REFERENCE to 1,
            Suite.OBJECT_FRAGMENT to 1,
            Suite.OBJECT_FRAGMENT_FROM_ARGUMENT to 1,
            Suite.QUERY_FRAGMENT to 1,
            Suite.SOMETIMES_PASSIVE to 1,
            Suite.FEATURE_INTERACTION to 1,
        ),
        tests(
            ResolverImplementation.RESOLVER08,
            Suite.EMPTY_OBJECT_FRAGMENT to 2,
            Suite.NODE to 2,
            Suite.ROOT_FIELD_REFERENCE to 2,
            Suite.LIST_PASSIVE_DEEPENING to 2,
            Suite.OBJECT_FRAGMENT to 2,
            Suite.OBJECT_FRAGMENT_FROM_ARGUMENT to 2,
            Suite.QUERY_FRAGMENT to 2,
            Suite.SOMETIMES_PASSIVE to 2,
            Suite.FEATURE_INTERACTION to 2,
            Suite.CONSTRUCTION_WITNESS to 3,
        ),
        tests(
            ResolverImplementation.RESOLVER21,
            Suite.EMPTY_OBJECT_FRAGMENT to 3,
            Suite.NODE to 3,
            Suite.ROOT_FIELD_REFERENCE to 3,
            Suite.SOMETIMES_PASSIVE to 3,
        ),
        tests(
            ResolverImplementation.RESOLVER22,
            Suite.EMPTY_OBJECT_FRAGMENT to 4,
            Suite.NODE to 4,
            Suite.ROOT_FIELD_REFERENCE to 4,
            Suite.OBJECT_FRAGMENT to 4,
            Suite.OBJECT_FRAGMENT_FROM_ARGUMENT to 4,
            Suite.QUERY_FRAGMENT to 4,
            Suite.SOMETIMES_PASSIVE to 4,
            Suite.FEATURE_INTERACTION to 4,
        ),
        tests(
            ResolverImplementation.RESOLVER23,
            Suite.EMPTY_OBJECT_FRAGMENT to 5,
            Suite.NODE to 5,
            Suite.ROOT_FIELD_REFERENCE to 5,
            Suite.LIST_PASSIVE_DEEPENING to 5,
            Suite.OBJECT_FRAGMENT to 5,
            Suite.OBJECT_FRAGMENT_FROM_ARGUMENT to 5,
            Suite.QUERY_FRAGMENT to 5,
            Suite.SOMETIMES_PASSIVE to 5,
            Suite.FEATURE_INTERACTION to 5,
            Suite.RESOLVER23_FIELD_CHECKER_SUCCESS to 0,
            Suite.RESOLVER23_FIELD_CHECKER_DENIAL to 1,
            Suite.RESOLVER23_FIELD_CHECKER_MIXED to 2,
            Suite.RESOLVER23_FIELD_CHECKER_PASSIVE to 3,
            Suite.RESOLVER23_FIELD_CHECKER_ROOT_REFERENCE to 4,
            Suite.RESOLVER23_TYPE_CHECKER_SUCCESS to 0,
            Suite.RESOLVER23_TYPE_CHECKER_DENIAL to 1,
            Suite.RESOLVER23_TYPE_CHECKER_MIXED to 2,
        ),
        tests(
            ResolverImplementation.RESOLVER23_PRECOMPUTED,
            Suite.EMPTY_OBJECT_FRAGMENT to 6,
            Suite.NODE to 6,
            Suite.ROOT_FIELD_REFERENCE to 6,
            Suite.LIST_PASSIVE_DEEPENING to 6,
            Suite.OBJECT_FRAGMENT to 6,
            Suite.OBJECT_FRAGMENT_FROM_ARGUMENT to 6,
            Suite.QUERY_FRAGMENT to 6,
            Suite.SOMETIMES_PASSIVE to 6,
            Suite.FEATURE_INTERACTION to 6,
            Suite.RESOLVER23_FIELD_CHECKER_SUCCESS to 3,
            Suite.RESOLVER23_FIELD_CHECKER_DENIAL to 4,
            Suite.RESOLVER23_FIELD_CHECKER_MIXED to 0,
            Suite.RESOLVER23_FIELD_CHECKER_PASSIVE to 1,
            Suite.RESOLVER23_FIELD_CHECKER_ROOT_REFERENCE to 2,
            Suite.RESOLVER23_TYPE_CHECKER_SUCCESS to 3,
            Suite.RESOLVER23_TYPE_CHECKER_DENIAL to 4,
            Suite.RESOLVER23_TYPE_CHECKER_MIXED to 0,
        ),
        tests(
            ResolverImplementation.RESOLUTION,
            Suite.EMPTY_OBJECT_FRAGMENT to 7,
            Suite.NODE to 7,
            Suite.ROOT_FIELD_REFERENCE to 7,
            Suite.SELECTIVE_NODE to 7,
            Suite.LIST_PASSIVE_DEEPENING to 7,
            Suite.OBJECT_FRAGMENT to 7,
            Suite.RESOLUTION_OBJECT_FRAGMENT_FROM_ARGUMENT to 7,
            Suite.RESOLUTION_OBJECT_FRAGMENT_FROM_OBJECT_FIELD to 7,
            Suite.RESOLUTION_FROM_PROVIDER to 7,
            Suite.RESOLUTION_MIXED_VARIABLES to 7,
            Suite.RESOLUTION_QUERY_FRAGMENT to 7,
            Suite.SOMETIMES_PASSIVE to 7,
            Suite.RESOLUTION_FEATURE_INTERACTION to 7,
            exactApplications = true,
            fromFieldBindings = true,
        ),
        tests(
            ResolverImplementation.RESOLUTION,
            Suite.RESOLUTION_FIELD_CHECKER_SUCCESS to 5,
            Suite.RESOLUTION_FIELD_CHECKER_DENIAL to 6,
            Suite.RESOLUTION_FIELD_CHECKER_MIXED to 1,
            Suite.RESOLUTION_FIELD_CHECKER_PASSIVE to 2,
            Suite.RESOLUTION_FIELD_CHECKER_ROOT_REFERENCE to 3,
            Suite.RESOLUTION_TYPE_CHECKER_SUCCESS to 4,
            Suite.RESOLUTION_TYPE_CHECKER_DENIAL to 5,
            Suite.RESOLUTION_TYPE_CHECKER_MIXED to 6,
            Suite.CONSTRUCTION_WITNESS to 4,
        ),
    ).flatten().also { descriptors ->
        require(descriptors.map(ResolverPropertyTestDescriptor::id).distinct().size == descriptors.size) { "Duplicate implementation/suite pairs" }
    }

private fun tests(
    implementation: ResolverImplementation,
    vararg suites: Pair<Suite, Int>,
    exactApplications: Boolean = false,
    fromFieldBindings: Boolean = false,
): List<ResolverPropertyTestDescriptor> =
    suites.map { (suite, shard) ->
        ResolverPropertyTestDescriptor(implementation, suite, shard, exactApplications, fromFieldBindings)
    }
