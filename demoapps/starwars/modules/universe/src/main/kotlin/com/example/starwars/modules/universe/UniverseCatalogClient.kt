package com.example.starwars.modules.universe

import com.example.starwars.common.ExternalDataClient
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Simulates a client for the universe-catalog database: an out-of-process system that would, in
 * a real deployment, be reached over the network. Registered under the `@Named("universe")`
 * qualifier, so only injection points requesting that qualifier receive this instance.
 */
@Singleton
@Named("universe")
class UniverseCatalogClient : ExternalDataClient {
    override val sourceName: String = "universe-catalog-db"

    override fun fetchData(key: String): String {
        log.info("Calling {} for key={}", sourceName, key)
        val record = "$sourceName:$key"
        log.info("Received response from {}: {}", sourceName, record)
        return record
    }

    companion object {
        private val log = LoggerFactory.getLogger(UniverseCatalogClient::class.java)
    }
}
