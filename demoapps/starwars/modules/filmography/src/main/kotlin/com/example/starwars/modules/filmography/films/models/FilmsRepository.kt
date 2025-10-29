package com.example.starwars.modules.filmography.films.models

import jakarta.inject.Singleton

@Singleton
class FilmsRepository {
    private val films = listOf(
        Film(
            id = "1",
            title = "A New Hope",
            episodeID = 4,
            openingCrawl = """
                It is a period of civil war.
                Rebel spaceships, striking
                from a hidden base, have won
                their first victory against
                the evil Galactic Empire.
            """.trimIndent(),
            director = "George Lucas",
            producers = listOf("Gary Kurtz", "Rick McCallum"),
            releaseDate = "1977-05-25"
        ),
        Film(
            id = "2",
            title = "The Empire Strikes Back",
            episodeID = 5,
            openingCrawl = """
                It is a dark time for the
                Rebellion. Although the Death
                Star has been destroyed,
                Imperial troops have driven the
                Rebel forces from their hidden
                base and pursued them across
                the galaxy.
            """.trimIndent(),
            director = "Irvin Kershner",
            producers = listOf("Gary Kurtz"),
            releaseDate = "1980-05-17"
        ),
        Film(
            id = "3",
            title = "Return of the Jedi",
            episodeID = 6,
            openingCrawl = """
                Luke Skywalker has returned to
                his home planet of Tatooine in
                an attempt to rescue his
                friend Han Solo from the
                clutches of the vile gangster
                Jabba the Hutt.
            """.trimIndent(),
            director = "Richard Marquand",
            producers = listOf("Howard G. Kazanjian", "George Lucas", "Rick McCallum"),
            releaseDate = "1983-05-25"
        )
    )

    fun getAllFilms(): List<Film> = films

    fun findFilmById(filmId: String): Film? = films.find { it.id == filmId }
}
