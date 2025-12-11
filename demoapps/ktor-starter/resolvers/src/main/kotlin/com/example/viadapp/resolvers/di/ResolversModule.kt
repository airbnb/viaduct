package com.example.viadapp.resolvers.di

import com.example.viadapp.resolvers.AsciiArtResolver
import com.example.viadapp.resolvers.service.AsciiArtService
import org.koin.dsl.module

/**
 * The set of resolver classes registered in this module.
 *
 * This is the source of truth for resolver discovery - Viaduct will use
 * this set instead of classpath scanning.
 */
val resolverClasses: Set<Class<*>> = setOf(
    AsciiArtResolver::class.java,
)

/**
 * Koin module providing resolver dependencies.
 *
 * This module registers services and resolvers. The Viaduct instance
 * is registered separately in [viaductModule] so it can access
 * the Koin instance for dependency injection.
 *
 * To add a new resolver:
 * 1. Add it to [resolverClasses]
 * 2. Register it as a factory in this module
 */
val resolversModule = module {
    // Services - singleton instances shared across resolvers
    single { AsciiArtService() }

    // Resolvers - factory creates new instance per invocation (per GraphQL field resolution)
    factory { AsciiArtResolver(get()) }
}
