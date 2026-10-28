package viaduct.engine.runtime2.execution.testing

import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource

/**
 * Creates execution fixtures that borrow the dispatcher owned by the concrete JUnit test class.
 *
 * The inherited [ResolutionDispatcherResource] closes that dispatcher after the class. Fixtures
 * created here retain no owned execution resource and therefore require no per-test cleanup list.
 */
interface ExecutionTestFixtureResource : ResolutionDispatcherResource {
    fun fixtureFromResolverDSL(
        schemaSDL: String,
        resolverSchemaSDL: String,
    ): ExecutionTestFixture =
        ExecutionTestFixture.fromResolverDSL(
            schemaSDL = schemaSDL,
            resolverSchemaSDL = resolverSchemaSDL,
            resolverCoroutineContext = resolverDispatcher,
        )

    fun fixtureFromResolverDSL(resolverSchemaSDL: String): ExecutionTestFixture =
        ExecutionTestFixture.fromResolverDSL(
            resolverSchemaSDL = resolverSchemaSDL,
            resolverCoroutineContext = resolverDispatcher,
        )

    fun fixtureFromWorld(
        schemaSDL: String,
        world: TestWorld,
    ): ExecutionTestFixture =
        ExecutionTestFixture.fromWorld(
            schemaSDL = schemaSDL,
            world = world,
            resolverCoroutineContext = resolverDispatcher,
        )
}
