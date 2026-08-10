package com.example.remote

import com.example.starwars.common.SecurityAccessContext
import com.google.inject.AbstractModule
import com.google.inject.Scopes
import io.micronaut.context.annotation.Prototype
import io.micronaut.runtime.http.scope.RequestScope

/**
 * Guice module wiring StarWars-specific bindings for the standalone remote resolver process.
 * Replace this in your own integration with a module that satisfies your tenants'
 * dependencies; pass it to [Application] via [com.google.inject.Guice.createInjector].
 *
 * Maps Micronaut's DI scopes onto Guice's no-op scope so resolvers annotated for Micronaut work in
 * this process without a Micronaut context: `@RequestScope` because there is no HTTP request here,
 * and `@Prototype` because a new instance per lookup is Guice's default anyway. Without the
 * `@Prototype` mapping every tenant resolver fails to construct with
 * `[Guice/ScopeNotFound]: No scope is bound to Prototype`.
 */
class StarWarsRemoteModule : AbstractModule() {
    override fun configure() {
        bindScope(RequestScope::class.java, Scopes.NO_SCOPE)
        bindScope(Prototype::class.java, Scopes.NO_SCOPE)
        bind(SecurityAccessContext::class.java)
    }
}
