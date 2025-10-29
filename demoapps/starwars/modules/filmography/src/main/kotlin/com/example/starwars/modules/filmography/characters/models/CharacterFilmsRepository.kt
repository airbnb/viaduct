package com.example.starwars.modules.filmography.characters.models

import jakarta.inject.Singleton

/**
 * Repository object for managing relationships between characters and films
 * in the Star Wars demo application.
 *
 */
@Singleton
class CharacterFilmsRepository {
    /**
     * In-memory map to store character-film relationships.
     * Key: Character ID
     * Value: List of Film IDs the character appears in
     */
    private val characterFilmRelations = mutableMapOf(
        "1" to mutableListOf("1", "2", "3"), // Luke in all three films
        "2" to mutableListOf("1", "2", "3"), // Leia in all three films
        "3" to mutableListOf("1", "2", "3"), // Han in all three films
        "4" to mutableListOf("1", "2", "3"), // Vader in all three films
        "5" to mutableListOf("1", "2", "3") // Obi-Wan in all three films
    )

    /**
     * Finds all film IDs associated with a given character ID.
     *
     * @param characterId The ID of the character.
     * @return A list of film IDs the character appears in, or an empty list if none found.
     */
    fun findFilmsByCharacterId(characterId: String): List<String> {
        return characterFilmRelations[characterId] ?: emptyList()
    }

    /**
     * Adds a character to a film by updating the in-memory relationship map.
     *
     * @param characterId The ID of the character to add.
     * @param filmId The ID of the film to which the character is added.
     * @throws IllegalArgumentException if the character is already associated with the film.
     */
    fun addCharacterToFilm(
        characterId: String,
        filmId: String
    ) {
        val films = characterFilmRelations.getOrPut(characterId) { mutableListOf() }
        if (!films.contains(filmId)) {
            films.add(filmId)
        } else {
            throw IllegalArgumentException("Character with ID $characterId is already in film with ID $filmId")
        }
    }

    /**
     * Removes all film associations for a given character ID.
     *
     * @param internalID The ID of the character to remove.
     */
    fun removeCharacter(internalID: String) {
        characterFilmRelations.forEach { _, films ->
            films.remove(internalID)
        }
    }
}
