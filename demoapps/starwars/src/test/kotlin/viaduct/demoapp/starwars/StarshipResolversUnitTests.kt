@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.demoapp.starwars

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.api.grts.Query_AllStarships_Arguments
import viaduct.demoapp.starwars.resolvers.AllStarshipsResolver
import viaduct.demoapp.starwars.resolvers.StarshipNodeResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.service.runtime.SchemaRegistryConfiguration
import viaduct.service.runtime.ViaductSchemaRegistry
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

@OptIn(ExperimentalCoroutinesApi::class)
class StarshipResolversUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        ViaductSchemaRegistry.Factory(DefaultCoroutineInterop)
            .createRegistry(
                SchemaRegistryConfiguration.fromResources("viaduct.demoapp.starwars", ".*\\.graphqls")
            )
            .getFullSchema()

    private fun queryObj() = viaduct.api.grts.Query.Builder(context).build()

    @Test
    fun `AllStarshipsResolver returns default page size when limit is not provided`(): Unit =
        runBlocking {
            val resolver = AllStarshipsResolver()
            val args = Query_AllStarships_Arguments.Builder(context).build()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = queryObj(),
                queryValue = queryObj(),
                arguments = args
            )

            assertNotNull(result)
            assertEquals(2, result!!.size)
        }

    @Test
    fun `AllStarshipsResolver respects custom limit and maps fields`(): Unit =
        runBlocking {
            val resolver = AllStarshipsResolver()
            val limit = 2
            val args = Query_AllStarships_Arguments.Builder(context).limit(limit).build()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = queryObj(),
                queryValue = queryObj(),
                arguments = args
            )

            assertNotNull(result)
            assertEquals(2, result!!.size)

            val grt = result.first()!!
            assertEquals("Millennium Falcon", grt.getName())
        }

    @Test
    fun `starship by id returns the correct Starship using node resolver`(): Unit =
        runBlocking {
            val resolver = StarshipNodeResolver()
            val starshipId = "1" // Millennium Falcon ID

            val starshipGlobalId = context.globalIDFor(viaduct.api.grts.Starship.Reflection, starshipId)
            val result = runNodeResolver(resolver, starshipGlobalId)

            assertNotNull(result)
            assertEquals("Millennium Falcon", result.getName())
            assertEquals("YT-1300 light freighter", result.getModel())
        }

    @Test
    fun `starship by id returns the correct X-wing using node resolver`(): Unit =
        runBlocking {
            val resolver = StarshipNodeResolver()
            val starshipId = "2" // X-wing ID

            val starshipGlobalId = context.globalIDFor(viaduct.api.grts.Starship.Reflection, starshipId)
            val result = runNodeResolver(resolver, starshipGlobalId)

            assertNotNull(result)
            assertEquals("X-wing", result.getName())
            assertEquals("T-65 X-wing", result.getModel())
        }
}
