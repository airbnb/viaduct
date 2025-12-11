package com.example.viadapp.serve

import com.example.viadapp.di.viaductModule
import com.example.viadapp.resolvers.di.resolversModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import viaduct.serve.ViaductConfiguration
import viaduct.serve.ViaductProvider
import viaduct.service.api.Viaduct

/**
 * Viaduct provider for the Ktor Starter application.
 *
 * This provider initializes Koin (if not already started) and retrieves
 * the Viaduct instance from Koin. This is analogous to how Spring would
 * manage Viaduct in the starwars demo app.
 *
 * **Key design:** The Viaduct is a Koin-managed singleton, configured to use
 * Koin for both resolver discovery and instantiation. No classpath scanning.
 *
 * When running via:
 * - **Serve mode** (`./gradlew serve`): Koin is initialized here
 * - **Ktor mode** (`./gradlew run`): Koin is already initialized by Plugins.kt
 */
@ViaductConfiguration
class KtorStarterViaductProvider : ViaductProvider, KoinComponent {

    // Lazy inject Viaduct from Koin
    private val viaduct: Viaduct by inject()

    init {
        // Initialize Koin if not already started (for serve mode)
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(resolversModule, viaductModule)
            }
        }
    }

    override fun getViaduct(): Viaduct = viaduct
}
