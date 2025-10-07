@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.demoapp.starwars

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.grts.Film
import viaduct.demoapp.starwars.resolvers.FilmCharacterCountSummaryResolver
import viaduct.demoapp.starwars.resolvers.FilmDisplayTitleResolver
import viaduct.demoapp.starwars.resolvers.FilmProductionDetailsResolver
import viaduct.demoapp.starwars.resolvers.FilmSummaryResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.service.runtime.SchemaRegistryConfiguration
import viaduct.service.runtime.ViaductSchemaRegistry
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

@OptIn(ExperimentalCoroutinesApi::class)
class FilmResolverUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        ViaductSchemaRegistry.Factory(DefaultCoroutineInterop)
            .createRegistry(
                SchemaRegistryConfiguration.fromResources("viaduct.demoapp.starwars", ".*\\.graphqls")
            )
            .getFullSchema()

    @Test
    fun `FilmDisplayTitleResolver returns title`(): Unit =
        runBlocking {
            val resolver = FilmDisplayTitleResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("Star Wars: A New Hope")
                    .build(),
            )

            assertEquals("Star Wars: A New Hope", result)
        }

    @Test
    fun `FilmSummaryResolver formats episode title and director`(): Unit =
        runBlocking {
            val resolver = FilmSummaryResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("The Empire Strikes Back")
                    .episodeID(5)
                    .director("Irvin Kershner")
                    .build(),
            )

            assertEquals("Episode 5: The Empire Strikes Back (Directed by Irvin Kershner)", result)
        }

    @Test
    fun `FilmProductionDetailsResolver formats release director and producers`(): Unit =
        runBlocking {
            val resolver = FilmProductionDetailsResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("Return of the Jedi")
                    .director("Richard Marquand")
                    .producers(listOf("Howard Kazanjian", "George Lucas", "Rick McCallum"))
                    .releaseDate("1983-05-25")
                    .build(),
            )

            assertEquals(
                "Return of the Jedi was released on 1983-05-25, directed by Richard Marquand and produced by Howard Kazanjian, George Lucas, Rick McCallum",
                result
            )
        }

    @Test
    fun `FilmProductionDetailsResolver handles missing producers gracefully`(): Unit =
        runBlocking {
            val resolver = FilmProductionDetailsResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("Rogue One")
                    .director("Gareth Edwards")
                    .producers(null) // triggers "Unknown producers"
                    .releaseDate("2016-12-16")
                    .build(),
            )

            assertEquals(
                "Rogue One was released on 2016-12-16, directed by Gareth Edwards and produced by Unknown producers",
                result
            )
        }

    @Test
    fun `FilmCharacterCountSummaryResolver counts characters`(): Unit =
        runBlocking {
            val resolver = FilmCharacterCountSummaryResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .id(GlobalIDImpl(Film.Reflection, "1"))
                    .title("A New Hope")
                    .build(),
            )

            assertEquals("A New Hope features 5 main characters", result)
        }

    // Note: Node-based tests using runNodeResolver are now in QueryResolverUnitTests.kt
    // These tests demonstrate node fetches for Film and Character entities using proper NodeResolvers
}
