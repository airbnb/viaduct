@file:Suppress("ForbiddenImport", "DEPRECATION")

package com.example.starwars.service.test

import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.characters.resolvers.CharacterAppearanceDescriptionResolver
import com.example.starwars.modules.filmography.characters.resolvers.CharacterDisplayNameResolver
import com.example.starwars.modules.filmography.characters.resolvers.CharacterDisplaySummaryResolver
import com.example.starwars.modules.filmography.characters.resolvers.CharacterFormattedDescriptionResolver
import com.example.starwars.modules.filmography.characters.resolvers.CharacterNodeResolver
import com.example.starwars.modules.filmography.characters.resolvers.CharacterStatsResolver
import com.example.starwars.modules.filmography.characters.resolvers.ProfileFieldResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.grts.Character
import viaduct.api.grts.Character_CharacterProfile_Arguments
import viaduct.api.grts.Character_CharacterStats_Arguments
import viaduct.api.grts.Character_FormattedDescription_Arguments
import viaduct.api.grts.Species
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

// tag::character_resolver_unit_tests[10] Example of unit tests for field resolvers
@OptIn(ExperimentalCoroutinesApi::class)
class CharacterResolverUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        SchemaFactory(DefaultCoroutineInterop)
            .fromResources("com.example.starwars", Regex(".*\\.graphqls"))

    lateinit var characterRepository: CharacterRepository

    @BeforeEach
    fun setUp() {
        characterRepository = CharacterRepository()
    }

    @Test
    fun `DisplayNameResolver returns name correctly`(): Unit =
        runBlocking {
            val resolver = CharacterDisplayNameResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Character.Builder(context).name("Leia Organa").build(),
            )

            assertEquals("Leia Organa", result)
        }

    @Test
    fun `DisplaySummaryResolver returns formatted name and birth year`(): Unit =
        runBlocking {
            val resolver = CharacterDisplaySummaryResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Character.Builder(context).name("Darth Vader").birthYear("41.9BBY").build(),
            )

            assertEquals("Darth Vader (41.9BBY)", result)
        }

    @Test
    fun `AppearanceDescriptionResolver returns appearance string`(): Unit =
        runBlocking {
            val resolver = CharacterAppearanceDescriptionResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Character.Builder(context).name("Obi-Wan Kenobi").eyeColor("blue").hairColor("gray").build(),
            )

            assertEquals("Obi-Wan Kenobi has blue eyes and gray hair", result)
        }

    @Test
    fun `CharacterProfileResolver returns basic profile when details not included`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Character.Builder(context).name("C-3PO").build(),
            )

            assertEquals("Character Profile: C-3PO (basic info only)", result)
        }

    @Test
    fun `CharacterProfileResolver returns full profile when details are available`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Character.Builder(context)
                    .name("Luke Skywalker")
                    .birthYear("19BBY")
                    .height(172)
                    .mass(77.0)
                    .build(),
            )

            assertEquals("Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg", result)
        }

    @Test
    fun `CharacterStatsResolver returns full stats when in valid age range`(): Unit =
        runBlocking {
            val resolver = CharacterStatsResolver()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = Character_CharacterStats_Arguments.Builder(context).minAge(10).maxAge(100).build(),
                objectValue = Character.Builder(context)
                    .name("Ahsoka Tano")
                    .birthYear("36BBY")
                    .height(170)
                    .species(Species.Builder(context).name("Togruta").build())
                    .build(),
            )

            assertEquals("Stats for Ahsoka Tano (Age range: 10-100), Born: 36BBY, Height: 170cm, Species: Togruta", result)
        }

    @Test
    fun `CharacterStatsResolver still shows minimal info for invalid age range`(): Unit =
        runBlocking {
            val resolver = CharacterStatsResolver()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = Character_CharacterStats_Arguments.Builder(context).minAge(500).maxAge(1000).build(),
                objectValue = Character.Builder(context)
                    .name("Yoda")
                    .birthYear("896BBY")
                    .height(66)
                    .species(Species.Builder(context).name("Yoda's species").build())
                    .build(),
            )

            assertEquals("Stats for Yoda (Age range: 500-1000), Born: 896BBY, Height: 66cm, Species: Yoda's species", result)
        }

    @Test
    fun `FormattedDescriptionResolver returns full description for detailed format`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = Character_FormattedDescription_Arguments.Builder(context).format("detailed").build(),
                objectValue = Character.Builder(context)
                    .name("Padmé Amidala")
                    .birthYear("46BBY")
                    .eyeColor("brown")
                    .hairColor("brown")
                    .build(),
            )

            assertEquals("Padmé Amidala (born 46BBY) - brown eyes, brown hair", result)
        }

    @Test
    fun `FormattedDescriptionResolver returns year only`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = Character_FormattedDescription_Arguments.Builder(context).format("year-only").build(),
                objectValue = Character.Builder(context)
                    .name("Qui-Gon Jinn")
                    .birthYear("92BBY")
                    .build(),
            )

            assertEquals("Qui-Gon Jinn (born 92BBY)", result)
        }

    @Test
    fun `FormattedDescriptionResolver returns appearance only`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = Character_FormattedDescription_Arguments.Builder(context).format("appearance-only").build(),
                objectValue = Character.Builder(context)
                    .name("Rey")
                    .eyeColor("hazel")
                    .hairColor("brown")
                    .build(),
            )

            assertEquals("Rey - hazel eyes, brown hair", result)
        }

    @Test
    fun `FormattedDescriptionResolver returns name by default`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = Character_FormattedDescription_Arguments.Builder(context).build(),
                objectValue = Character.Builder(context)
                    .name("BB-8")
                    .build(),
            )

            assertEquals("BB-8", result)
        }

    @Test
    fun `CharacterProfileResolver returns basic profile when includeDetails=false`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(
                resolver = resolver,
                // Explicitly disable details via argument bound to the @Variable
                arguments = Character_CharacterProfile_Arguments
                    .Builder(context)
                    .includeDetails(false)
                    .build(),
                // Even if the object has data, it shouldn't be selected/available
                objectValue = Character.Builder(context)
                    .name("Luke Skywalker")
                    .birthYear("19BBY")
                    .height(172)
                    .mass(77.0)
                    .build(),
            )

            assertEquals("Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg", result)
        }

    @Test
    fun `CharacterProfileResolver returns full profile when includeDetails=true`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(
                resolver = resolver,
                // Enable details so the fragment includes conditional fields
                arguments = Character_CharacterProfile_Arguments
                    .Builder(context)
                    .includeDetails(true)
                    .build(),
                objectValue = Character.Builder(context)
                    .name("Luke Skywalker")
                    .birthYear("19BBY")
                    .height(172)
                    .mass(77.0)
                    .build(),
            )

            assertEquals(
                "Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg",
                result
            )
        }

    // tag::character_node_resolver_multiple_ids[14] Example of runNodeBatchResolver
    @Test
    fun `CharacterBatchNodeResolver resolves multiple ids`() =
        runBlocking {
            val resolver = CharacterNodeResolver(characterRepository)

            val ids = listOf("1", "2").map {
                context.globalIDFor(Character.Reflection, it)
            }

            val results = runNodeBatchResolver(
                resolver = resolver,
                ids = ids
            )

            assertEquals(2, results.size)
        }
}
