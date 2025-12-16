package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * Represents a feature flag with a name
 */
@StableApi
interface Flag {
    val flagName: String
}
