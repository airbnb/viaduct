package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Singleton

/**
 * Module containing truly global, stateless factories.
 * This module is bound in the parent injector and provides factories
 * that can create schema-specific instances.
 */
class StatelessFactoryModule : AbstractModule() {
    override fun configure() {
        // Factory for creating StandardViaduct instances with child injectors
        bind(StandardViaduct.Factory::class.java)
            .`in`(Singleton::class.java)
    }
}
