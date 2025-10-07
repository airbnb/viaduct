package viaduct.engine.api

/**
 * Factory creator pattern for creating schema-aware CheckerExecutorFactory instances.
 *
 * This interface enables the child injector pattern by allowing CheckerExecutorFactory creation
 * to be deferred until the ViaductSchema is available. The parent injector holds the creator,
 * and child injectors call create() with their specific schema to get a factory instance.
 *
 * This pattern solves the dependency inversion problem where configuration (in parent injector)
 * needs to create schema-dependent components (in child injector).
 */
fun interface CheckerExecutorFactoryCreator {
    fun create(schema: ViaductSchema): CheckerExecutorFactory
}
