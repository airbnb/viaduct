@file:Suppress("ForbiddenImport", "DEPRECATION")

package com.example.starwars.service.test

import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.modules.universe.species.resolvers.SpeciesCulturalNotesResolver
import com.example.starwars.modules.universe.species.resolvers.SpeciesRarityLevelResolver
import com.example.starwars.modules.universe.species.resolvers.SpeciesSpecialAbilitiesResolver
import com.example.starwars.modules.universe.species.resolvers.SpeciesTechnologicalLevelResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.grts.Species
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

/**
 * Integration tests for custom field resolvers on the Species type that
 * access data in the extrasData field.
 *
 * These tests focus on the logic within each resolver, ensuring they return
 * the expected results given specific Species inputs.
 *
 * Note: Integration tests that cover full query execution and authorization
 * are located in QueryResolverUnitTests.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeciesExtrasResolverUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        SchemaFactory(DefaultCoroutineInterop)
            .fromResources("com.example.starwars", Regex(".*\\.graphqls"))

    private lateinit var speciesRepository: SpeciesRepository

    @BeforeEach
    fun setUp() {
        speciesRepository = SpeciesRepository()
    }

    /**
     * Build Species GRT containing only the GlobalID
     */
    private fun speciesGrtForId(internalId: String): Species =
        Species.Builder(context)
            .id(context.globalIDFor(Species.Reflection, internalId))
            .build()

    @Test
    fun `SpeciesCulturalNotesResolver returns cultural notes from extrasData`(): Unit =
        runBlocking {
            val resolver = SpeciesCulturalNotesResolver(speciesRepository)

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId("1")
            )

            assertEquals("Diverse species with strong adaptability and technological advancement", result)
        }

    @Test
    fun `SpeciesRarityLevelResolver returns rarity level from extrasData`(): Unit =
        runBlocking {
            val resolver = SpeciesRarityLevelResolver(speciesRepository)

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId("1")
            )

            assertEquals("Common", result)
        }

    @Test
    fun `SpeciesSpecialAbilitiesResolver returns abilities list from extrasData`(): Unit =
        runBlocking {
            val resolver = SpeciesSpecialAbilitiesResolver(speciesRepository)

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId("1")
            )

            // Compare size and content to be robust to nulls
            assertNotNull(result)
            assertEquals(3, result!!.size)
            assertIterableEquals(listOf("Force sensitivity (rare)", "Adaptability", "Innovation"), result)
        }

    @Test
    fun `SpeciesTechnologicalLevelResolver returns tech level from extrasData`(): Unit =
        runBlocking {
            val resolver = SpeciesTechnologicalLevelResolver(speciesRepository)

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId("1")
            )

            assertEquals("Advanced", result)
        }

    @Test
    fun `resolvers return null when species id not found`(): Unit =
        runBlocking {
            val fakeId = "non-existent-id-123"
            val grt = speciesGrtForId(fakeId)

            val notes = runFieldResolver(SpeciesCulturalNotesResolver(speciesRepository), grt)
            val rarity = runFieldResolver(SpeciesRarityLevelResolver(speciesRepository), grt)
            val abilities = runFieldResolver(SpeciesSpecialAbilitiesResolver(speciesRepository), grt)
            val tech = runFieldResolver(SpeciesTechnologicalLevelResolver(speciesRepository), grt)

            assertNull(notes)
            assertNull(rarity)
            assertNull(abilities)
            assertNull(tech)
        }
}
