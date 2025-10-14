package viaduct.demoapp.universe.species.viaduct.fieldresolvers

import viaduct.api.Resolver
import viaduct.api.grts.Planet
import viaduct.demoapp.universe.resolverbases.SpeciesResolvers
import viaduct.demoapp.universe.species.models.repository.SpeciesRepository

/**
 * Resolver for `homeworld` field in Species.
 *
 * Returns the planet that is the homeworld of this species, or null if none exists.
 */
// tag::resolver_example[15] Example of a computed field resolver
@Resolver("id")
class HomeworldResolver : SpeciesResolvers.Homeworld() {
    override suspend fun resolve(ctx: Context): Planet? {
        val species = ctx.objectValue
        val homeWorldId = SpeciesRepository.findById(species.getId().internalID)?.homeworldId ?: return null

        val planetId = ctx.globalIDFor(Planet.Reflection, homeWorldId)

        return ctx.nodeFor(planetId)
    }
}
