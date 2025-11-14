package viaduct.devserve

/**
 * Returns the HTML for the GraphiQL IDE.
 *
 * The GraphiQL HTML is downloaded at build time by the downloadGraphiQL Gradle task
 * and packaged into resources. This ensures we always use the latest version without
 * hardcoding version numbers.
 *
 * @return The GraphiQL HTML content
 * @throws IllegalStateException if the GraphiQL HTML cannot be found in resources
 */
fun graphiQLHtml(): String {
    val resourcePath = "/graphiql/index.html"

    return object {}.javaClass.getResourceAsStream(resourcePath)?.use { stream ->
        stream.bufferedReader().readText()
    } ?: throw IllegalStateException(
        "GraphiQL HTML not found at $resourcePath. " +
        "Ensure the downloadGraphiQL Gradle task has been run during build."
    )
}
