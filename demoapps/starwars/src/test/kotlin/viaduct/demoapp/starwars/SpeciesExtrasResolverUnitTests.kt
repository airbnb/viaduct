@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.demoapp.starwars

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import viaduct.api.grts.Species
import viaduct.demoapp.starwars.data.StarWarsData
import viaduct.demoapp.starwars.resolvers.SpeciesCulturalNotesResolver
import viaduct.demoapp.starwars.resolvers.SpeciesRarityLevelResolver
import viaduct.demoapp.starwars.resolvers.SpeciesSpecialAbilitiesResolver
import viaduct.demoapp.starwars.resolvers.SpeciesTechnologicalLevelResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.service.runtime.SchemaRegistryConfiguration
import viaduct.service.runtime.ViaductSchemaRegistry
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

@OptIn(ExperimentalCoroutinesApi::class)
class SpeciesExtrasResolverUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        ViaductSchemaRegistry.Factory(DefaultCoroutineInterop)
            .createRegistry(
                SchemaRegistryConfiguration.fromResources("viaduct.demoapp.starwars", ".*\\.graphqls")
            )
            .getFullSchema()

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
            val ref = StarWarsData.species.first()
            val resolver = SpeciesCulturalNotesResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId(ref.id)
            )

            assertEquals(ref.extrasData.culturalNotes, result)
        }

    @Test
    fun `SpeciesRarityLevelResolver returns rarity level from extrasData`(): Unit =
        runBlocking {
            val ref = StarWarsData.species.first()
            val resolver = SpeciesRarityLevelResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId(ref.id)
            )

            assertEquals(ref.extrasData.rarityLevel, result)
        }

    @Test
    fun `SpeciesSpecialAbilitiesResolver returns abilities list from extrasData`(): Unit =
        runBlocking {
            val ref = StarWarsData.species.first()
            val resolver = SpeciesSpecialAbilitiesResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId(ref.id)
            )

            val expected = ref.extrasData.specialAbilities
            // Compare size and content to be robust to nulls
            assertNotNull(result)
            assertEquals(expected.size, result!!.size)
            assertIterableEquals(expected, result)
        }

    @Test
    fun `SpeciesTechnologicalLevelResolver returns tech level from extrasData`(): Unit =
        runBlocking {
            val ref = StarWarsData.species.first()
            val resolver = SpeciesTechnologicalLevelResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = speciesGrtForId(ref.id)
            )

            assertEquals(ref.extrasData.technologicalLevel, result)
        }

    @Test
    fun `resolvers return null when species id not found`(): Unit =
        runBlocking {
            val fakeId = "non-existent-id-123"
            val grt = speciesGrtForId(fakeId)

            val notes = runFieldResolver(SpeciesCulturalNotesResolver(), grt)
            val rarity = runFieldResolver(SpeciesRarityLevelResolver(), grt)
            val abilities = runFieldResolver(SpeciesSpecialAbilitiesResolver(), grt)
            val tech = runFieldResolver(SpeciesTechnologicalLevelResolver(), grt)

            assertNull(notes)
            assertNull(rarity)
            assertNull(abilities)
            assertNull(tech)
        }
}
