package com.example.starwars.modules.filmography.characters.models

import java.time.Instant

/**
 * Represents a character entity in the Star Wars demo application.
 *
 * This data class defines the properties of a character, including their
 * unique identifier, name, physical attributes, and associated metadata.
 */
data class Character(
    val id: String,
    val name: String,
    val birthYear: String?,
    val eyeColor: String?,
    val gender: String?,
    val hairColor: String?,
    val height: Int?,
    val mass: Float?,
    val homeworldId: String?,
    val speciesId: String?,
    val created: Instant = Instant.now(),
    val edited: Instant = Instant.now()
)
