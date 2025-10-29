package com.example.starwars.modules.universe.vehicles.queries

import com.example.starwars.modules.universe.vehicles.models.VehicleBuilder
import com.example.starwars.modules.universe.vehicles.models.VehiclesRepository
import com.example.starwars.universe.resolverbases.QueryResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Vehicle

const val DEFAULT_PAGE_SIZE = 10

/**
 * Resolver for fetching a list of vehicles.
 *
 * This class extends the `QueryResolvers.AllVehicles` base class and provides
 * the implementation for resolving all vehicles with an optional limit.
 */
@Resolver
class AllVehiclesQueryResolver
    @Inject
    constructor(
        private val vehiclesRepository: VehiclesRepository
    ) : QueryResolvers.AllVehicles() {
        /**
         * Resolves a list of vehicles based on the provided context.
         *
         * @param ctx The context containing arguments and other request-related data.
         * @return A list of `Vehicle` objects, or null if no vehicles are found.
         */
        override suspend fun resolve(ctx: Context): List<Vehicle?>? {
            // The limit comes from arguments, or we take the default.
            val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE

            // Fetch vehicles from the repository.
            val vehicles = vehiclesRepository.findAll().take(limit)

            // Map the fetched vehicles to the Viaduct generated class.
            return vehicles.map(VehicleBuilder(ctx)::build)
        }
    }
