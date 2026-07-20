package com.example.starwars.modules.filmography

import com.example.starwars.common.ExternalDataClient
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Simulates a client for the film-archive service: an out-of-process system that would, in a
 * real deployment, be reached over the network. Registered under the `@Named("filmography")`
 * qualifier, so only injection points requesting that qualifier receive this instance.
 */
@Singleton
@Named("filmography")
class FilmArchiveClient : ExternalDataClient {
    override val sourceName: String = "film-archive-service"

    override fun fetchData(key: String): String {
        log.info("Calling {} for key={}", sourceName, key)
        val record = "$sourceName:$key"
        log.info("Received response from {}: {}", sourceName, record)
        return record
    }

    companion object {
        private val log = LoggerFactory.getLogger(FilmArchiveClient::class.java)
    }
}
