package viaduct.serve.fixtures.resolvers

import viaduct.api.Resolver

/**
 * Test fixture: A valid resolver with @Resolver annotation and no-arg constructor
 */
@Resolver
class TestQueryResolver {
    // This would extend a generated base class in real code
    // For testing, we just need the annotation
}

/**
 * Test fixture: Another resolver in a different package
 */
@Resolver
class TestMutationResolver {
    // Another test resolver
}

/**
 * Test fixture: Resolver without no-arg constructor
 */
@Resolver
class ResolverWithoutNoArgConstructor(
    private val dependency: String
) {
    // Should be skipped by DefaultViaductFactory
}

/**
 * Test fixture: Class with resolver-like name but no annotation
 */
class UnannotatedResolver {
    // Should be ignored
}
