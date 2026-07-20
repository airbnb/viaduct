@file:Suppress("ForbiddenImport")

package com.example.starwars.service.viaduct

import com.example.starwars.common.ExternalDataClient
import com.example.starwars.modules.filmography.FilmArchiveClient
import com.example.starwars.modules.universe.UniverseCatalogClient
import io.micronaut.context.BeanContext
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import jakarta.inject.Named
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A stand-in resolver-shaped bean that requires the filmography-qualified [ExternalDataClient]. */
@Prototype
private class FilmographyClientHolder
    @Inject
    constructor(
        @Named("filmography") val client: ExternalDataClient
    )

/** A stand-in resolver-shaped bean that requires the universe-qualified [ExternalDataClient]. */
@Prototype
private class UniverseClientHolder
    @Inject
    constructor(
        @Named("universe") val client: ExternalDataClient
    )

class MicronautTenantModuleInjectorFactoryTest {
    private lateinit var beanContext: BeanContext
    private lateinit var factory: MicronautTenantModuleInjectorFactory

    @BeforeEach
    fun setUp() {
        beanContext = BeanContext.run()
        factory = MicronautTenantModuleInjectorFactory(beanContext)
    }

    @AfterEach
    fun tearDown() {
        beanContext.close()
    }

    @Test
    fun `bootstrap resolves a bean whose qualified dependency is bound by a named implementation`(): Unit =
        runBlocking {
            val injector = factory.bootstrap("filmography", null)

            val client = injector.getProvider(FilmographyClientHolder::class.java).get().client

            assertInstanceOf(FilmArchiveClient::class.java, client)
            assertEquals("film-archive-service:42", client.fetchData("42"))
        }

    @Test
    fun `each tenant's qualified dependency resolves to a distinct instance for the same requested type`(): Unit =
        runBlocking {
            val injector = factory.bootstrap("filmography", null)

            val filmographyClient = injector.getProvider(FilmographyClientHolder::class.java).get().client
            val universeClient = injector.getProvider(UniverseClientHolder::class.java).get().client

            assertInstanceOf(FilmArchiveClient::class.java, filmographyClient)
            assertInstanceOf(UniverseCatalogClient::class.java, universeClient)
            assertNotSame(filmographyClient, universeClient)
        }

    @Test
    fun `bootstrap returns the same injector for every tenant`(): Unit =
        runBlocking {
            val filmographyInjector = factory.bootstrap("filmography", null)
            val universeInjector = factory.bootstrap("universe", null)

            assertSame(filmographyInjector, universeInjector)
        }
}
