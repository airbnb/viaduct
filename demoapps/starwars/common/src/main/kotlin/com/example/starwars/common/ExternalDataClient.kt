package com.example.starwars.common

/**
 * Stands in for a real outbound dependency (a DB connection, an HTTP client, etc.).
 *
 * Each tenant module binds its own implementation via a per-tenant injector, so two tenants
 * that both request an [ExternalDataClient] receive different, tenant-specific instances.
 */
interface ExternalDataClient {
    /** Name of the backing system this client pretends to call, e.g. "film-archive-service". */
    val sourceName: String

    /** Simulates fetching [key] from the backing system. */
    fun fetchData(key: String): String
}
