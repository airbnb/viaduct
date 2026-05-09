@file:Suppress("ForbiddenImport")

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
import viaduct.api.testing.ResolverTestBase
import viaduct.apiannotations.ExperimentalApi

// tag::character_resolver_unit_tests[10] Example of unit tests for field resolvers
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalApi::class)
class CharacterResolverUnitTests : ResolverTestBase() {
    lateinit var characterRepository: CharacterRepository

    @BeforeEach
    fun setUp() {
        characterRepository = CharacterRepository()
    }

    // tag::field_resolver_example[11] Simple field resolver test
    @Test
    fun `DisplayNameResolver returns name correctly`(): Unit =
        runBlocking {
            val resolver = CharacterDisplayNameResolver()

            val result = runFieldResolver(resolver) {
                objectValue = Character.of(context) { name("Leia Organa") }
            }

            assertEquals("Leia Organa", result)
        }

    @Test
    fun `DisplaySummaryResolver returns formatted name and birth year`(): Unit =
        runBlocking {
            val resolver = CharacterDisplaySummaryResolver()

            val result = runFieldResolver(resolver) {
                objectValue = Character.of(context) {
                    name("Darth Vader")
                    birthYear("41.9BBY")
                }
            }

            assertEquals("Darth Vader (41.9BBY)", result)
        }

    @Test
    fun `AppearanceDescriptionResolver returns appearance string`(): Unit =
        runBlocking {
            val resolver = CharacterAppearanceDescriptionResolver()

            val result = runFieldResolver(resolver) {
                objectValue = Character.of(context) {
                    name("Obi-Wan Kenobi")
                    eyeColor("blue")
                    hairColor("gray")
                }
            }

            assertEquals("Obi-Wan Kenobi has blue eyes and gray hair", result)
        }

    @Test
    fun `CharacterProfileResolver returns basic profile when details not included`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(resolver) {
                objectValue = Character.of(context) { name("C-3PO") }
            }

            assertEquals("Character Profile: C-3PO (basic info only)", result)
        }

    @Test
    fun `CharacterProfileResolver returns full profile when details are available`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(resolver) {
                objectValue = Character.of(context) {
                    name("Luke Skywalker")
                    birthYear("19BBY")
                    height(172)
                    mass(77.0)
                }
            }

            assertEquals("Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg", result)
        }

    @Test
    fun `CharacterStatsResolver returns full stats when in valid age range`(): Unit =
        runBlocking {
            val resolver = CharacterStatsResolver()

            val result = runFieldResolver(resolver) {
                arguments = Character_CharacterStats_Arguments.of(context) { minAge(10).maxAge(100) }
                objectValue = Character.of(context) {
                    name("Ahsoka Tano")
                    birthYear("36BBY")
                    height(170)
                    species(Species.of(context) { name("Togruta") })
                }
            }

            assertEquals("Stats for Ahsoka Tano (Age range: 10-100), Born: 36BBY, Height: 170cm, Species: Togruta", result)
        }

    @Test
    fun `CharacterStatsResolver still shows minimal info for invalid age range`(): Unit =
        runBlocking {
            val resolver = CharacterStatsResolver()

            val result = runFieldResolver(resolver) {
                arguments = Character_CharacterStats_Arguments.of(context) {
                    minAge(500)
                    maxAge(1000)
                }
                objectValue = Character.of(context) {
                    name("Yoda")
                    birthYear("896BBY")
                    height(66)
                    species(Species.of(context) { name("Yoda's species") })
                }
            }

            assertEquals("Stats for Yoda (Age range: 500-1000), Born: 896BBY, Height: 66cm, Species: Yoda's species", result)
        }

    // tag::field_resolver_with_arguments_example[17] Field resolver test with arguments
    @Test
    fun `FormattedDescriptionResolver returns full description for detailed format`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(resolver) {
                arguments = Character_FormattedDescription_Arguments.of(context) { format("detailed") }
                objectValue = Character.of(context) {
                    name("Padmé Amidala")
                    birthYear("46BBY")
                    eyeColor("brown")
                    hairColor("brown")
                }
            }

            assertEquals("Padmé Amidala (born 46BBY) - brown eyes, brown hair", result)
        }

    @Test
    fun `FormattedDescriptionResolver returns year only`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(resolver) {
                arguments = Character_FormattedDescription_Arguments.of(context) { format("year-only") }
                objectValue = Character.of(context) {
                    name("Qui-Gon Jinn")
                    birthYear("92BBY")
                }
            }

            assertEquals("Qui-Gon Jinn (born 92BBY)", result)
        }

    @Test
    fun `FormattedDescriptionResolver returns appearance only`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(resolver) {
                arguments = Character_FormattedDescription_Arguments.of(context) { format("appearance-only") }
                objectValue = Character.of(context) {
                    name("Rey")
                    eyeColor("hazel")
                    hairColor("brown")
                }
            }

            assertEquals("Rey - hazel eyes, brown hair", result)
        }

    @Test
    fun `FormattedDescriptionResolver returns name by default`(): Unit =
        runBlocking {
            val resolver = CharacterFormattedDescriptionResolver()

            val result = runFieldResolver(resolver) {
                arguments = Character_FormattedDescription_Arguments.of(context) { }
                objectValue = Character.of(context) { name("BB-8") }
            }

            assertEquals("BB-8", result)
        }

    @Test
    fun `CharacterProfileResolver returns basic profile when includeDetails=false`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(resolver) {
                // Explicitly disable details via argument bound to the @Variable
                arguments = Character_CharacterProfile_Arguments.of(context) { includeDetails(false) }
                // Even if the object has data, it shouldn't be selected/available
                objectValue = Character.of(context) {
                    name("Luke Skywalker")
                    birthYear("19BBY")
                    height(172)
                    mass(77.0)
                }
            }

            assertEquals("Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg", result)
        }

    @Test
    fun `CharacterProfileResolver returns full profile when includeDetails=true`(): Unit =
        runBlocking {
            val resolver = ProfileFieldResolver()

            val result = runFieldResolver(resolver) {
                // Enable details so the fragment includes conditional fields
                arguments = Character_CharacterProfile_Arguments.of(context) { includeDetails(true) }
                objectValue = Character.of(context) {
                    name("Luke Skywalker")
                    birthYear("19BBY")
                    height(172)
                    mass(77.0)
                }
            }

            assertEquals(
                "Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg",
                result
            )
        }

    // tag::character_node_resolver_multiple_ids[9] Example of runNodeBatchResolver
    @Test
    fun `CharacterBatchNodeResolver resolves multiple ids`() =
        runBlocking {
            val results = runNodeBatchResolver(CharacterNodeResolver(characterRepository)) {
                ids = listOf("1", "2").map { globalIDFor(Character.Reflection, it) }
            }

            assertEquals(2, results.size)
        }
}
