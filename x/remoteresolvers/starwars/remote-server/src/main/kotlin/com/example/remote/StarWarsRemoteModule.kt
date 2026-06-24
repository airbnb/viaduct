package com.example.remote

import com.example.starwars.common.SecurityAccessContext
import com.google.inject.AbstractModule
import com.google.inject.Scopes
import io.micronaut.runtime.http.scope.RequestScope

/**
 * Guice module wiring StarWars-specific bindings for the standalone remote resolver process.
 * Replace this in your own integration with a module that satisfies your tenants'
 * dependencies; pass it to [Application] via [com.google.inject.Guice.createInjector].
 *
 * Maps Micronaut's `@RequestScope` to Guice's no-op scope so resolvers that rely on
 * per-request DI work without an HTTP request context.
 */
class StarWarsRemoteModule : AbstractModule() {
    override fun configure() {
        bindScope(RequestScope::class.java, Scopes.NO_SCOPE)
        bind(SecurityAccessContext::class.java)
    }
}
