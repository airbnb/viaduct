package com.example.starwars.modules.filmography.films.models

import jakarta.inject.Singleton

@Singleton
class FilmCharactersRepository {
    private val filmCharacterRelations = mutableMapOf(
        "1" to mutableListOf("1", "2", "3", "4", "5"), // A New Hope characters
        "2" to mutableListOf("1", "2", "3", "4", "5"), // Empire characters
        "3" to mutableListOf("1", "2", "3", "4", "5") // Return of the Jedi characters
    )

    fun findCharactersByFilmId(filmId: String): List<String> {
        return filmCharacterRelations[filmId] ?: emptyList()
    }

    fun addCharacterToFilm(
        filmId: String,
        characterId: String
    ) {
        val characters = filmCharacterRelations.getOrPut(filmId) { mutableListOf() }
        if (!characters.contains(characterId)) {
            characters.add(characterId)
        } else {
            throw IllegalArgumentException("Film with ID $filmId already has character with ID $characterId")
        }
    }

    fun removeCharacter(internalID: String) {
        filmCharacterRelations.forEach { _, characters ->
            characters.remove(internalID)
        }
    }
}
