package com.example.viadapp.resolvers.service

/**
 * Service for loading and providing ASCII art from resources.
 *
 * This service demonstrates dependency injection with Koin by loading
 * ASCII art from a resource file and making it available to resolvers.
 */
class AsciiArtService {
    private val asciiArt: String by lazy {
        loadAsciiArt()
    }

    /**
     * Returns the Viaduct ASCII art banner.
     */
    fun getViaductBanner(): String = asciiArt

    /**
     * Returns a list of available ASCII art names.
     */
    fun getAvailableArt(): List<String> = listOf("viaduct")

    private fun loadAsciiArt(): String {
        val resourcePath = "/ascii/viaduct.txt"
        return this::class.java.getResourceAsStream(resourcePath)?.use { stream ->
            stream.bufferedReader().readText()
        } ?: throw IllegalStateException("ASCII art not found at $resourcePath")
    }
}
