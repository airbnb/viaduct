package com.example.viadapp.resolvers.di

import com.example.viadapp.resolvers.AsciiArtResolver
import com.example.viadapp.resolvers.service.AsciiArtService
import org.koin.dsl.module

/**
 * Koin module providing resolver dependencies.
 *
 * This module registers services and resolvers. The Viaduct instance
 * is registered separately in [viaductModule] so it can access
 * the Koin instance for dependency injection.
 *
 * Resolvers are discovered automatically via Viaduct's classpath scanning.
 * Koin is only used for instantiation, enabling constructor injection.
 *
 * To add a new resolver:
 * 1. Annotate it with @Resolver
 * 2. Register it as a factory in this module
 */
val resolversModule = module {
    // Services - singleton instances shared across resolvers
    single { AsciiArtService() }

    // Resolvers - factory creates new instance per invocation (per GraphQL field resolution)
    factory { AsciiArtResolver(get()) }
}
