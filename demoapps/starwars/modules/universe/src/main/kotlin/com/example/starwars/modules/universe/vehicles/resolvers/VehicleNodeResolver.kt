package com.example.starwars.modules.universe.vehicles.resolvers

import com.example.starwars.modules.universe.vehicles.models.VehicleBuilder
import com.example.starwars.modules.universe.vehicles.models.VehiclesRepository
import com.example.starwars.universe.NodeResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Vehicle

/**
 * Resolver for fetching a single vehicle node by its ID.
 *
 * This class extends the `NodeResolvers.Vehicle` base class and provides
 * the implementation for resolving a specific vehicle node.
 */
@Resolver
class VehicleNodeResolver
    @Inject
    constructor(
        private val vehiclesRepository: VehiclesRepository
    ) : NodeResolvers.Vehicle() {
        /**
         * Resolves a single vehicle node based on the ID provided int the context.
         *
         * @param ctx The context autofills the Node ID.
         * @return A `Vehicle` object representing the resolved vehicle node.
         * @throws IllegalArgumentException if no vehicle is found with the given ID.
         */
        override suspend fun resolve(ctx: Context): Vehicle {
            // Extract the internal ID from the context, it is automatically filled on node requests.
            val stringId = ctx.id.internalID

            // Fetch the vehicle from the repository.
            val vehicle = vehiclesRepository.findById(stringId)
                ?: throw IllegalArgumentException("Vehicle with ID $stringId not found")

            // Build and return the viaduct generated `Vehicle`.
            return VehicleBuilder(ctx).build(vehicle)
        }
    }
