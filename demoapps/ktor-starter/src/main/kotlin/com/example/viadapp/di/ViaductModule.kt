package com.example.viadapp.di

import com.example.viadapp.SCHEMA_ID
import com.example.viadapp.injector.KoinTenantCodeInjector
import org.koin.dsl.module
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.Viaduct

private const val TENANT_PACKAGE_PREFIX = "com.example.viadapp"

/**
 * Koin module that provides the Viaduct instance.
 *
 * The Viaduct is registered as a singleton, configured to use:
 * - [KoinTenantCodeInjector] for resolver instantiation via Koin
 * - Default classpath scanning for resolver discovery
 *
 * This allows resolvers to use constructor injection for dependencies while
 * Viaduct handles discovery automatically via classpath scanning.
 */
val viaductModule = module {
    // Register the Koin instance itself so it can be injected
    single { getKoin() }

    // Register the tenant code injector - needs Koin instance for on-demand resolution
    single { KoinTenantCodeInjector(get()) }

    single<Viaduct> {
        val tenantCodeInjector: KoinTenantCodeInjector = get()

        BasicViaductFactory.create(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo(SCHEMA_ID)),
            ),
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = TENANT_PACKAGE_PREFIX,
                tenantCodeInjector = tenantCodeInjector
            )
        )
    }
}
