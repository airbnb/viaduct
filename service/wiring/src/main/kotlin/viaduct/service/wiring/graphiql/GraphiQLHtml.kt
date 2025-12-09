package viaduct.service.wiring.graphiql

/**
 * Returns the HTML for the GraphiQL IDE.
 *
 * The GraphiQL HTML is loaded from src/main/resources/graphiql/index.html and packaged
 * into the JAR at build time. The HTML uses CDN-hosted GraphiQL libraries and loads
 * custom plugins from separate JavaScript files:
 * - global-id-plugin.jsx: Provides Global ID encode/decode utilities
 *
 * ## Integration Example
 *
 * To integrate GraphiQL into your application, you need to:
 * 1. Serve the GraphiQL HTML at `/graphiql`
 * 2. Serve the JavaScript files at `/js/jsx-loader.js`, `/js/global-id-plugin.jsx`
 * 3. Have a GraphQL endpoint at `/graphql`
 *
 * ### Ktor Example
 *
 * ```kotlin
 * fun Application.configureGraphiQL() {
 *     routing {
 *         get("/graphiql") {
 *             call.respondText(graphiQLHtml(), ContentType.Text.Html)
 *         }
 *         route("/js") {
 *             get("/{filename}") {
 *                 val filename = call.parameters["filename"]!!
 *                 val path = "graphiql/js/" + filename
 *                 val resource = this::class.java.classLoader.getResource(path)!!
 *                 call.respondText(resource.readText(), ContentType.Application.JavaScript)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ### Micronaut Example
 *
 * ```kotlin
 * @Controller
 * class GraphiQLController {
 *     @Get("/graphiql")
 *     @Produces(MediaType.TEXT_HTML)
 *     fun graphiql(): HttpResponse<String> = HttpResponse.ok(graphiQLHtml())
 * }
 *
 * @Controller("/js")
 * class GraphiQLJsController {
 *     @Get("/jsx-loader.js")
 *     @Produces("application/javascript")
 *     fun jsxLoader() = serveJs("jsx-loader.js")
 *
 *     @Get("/global-id-plugin.jsx")
 *     @Produces("application/javascript")
 *     fun globalIdPlugin() = serveJs("global-id-plugin.jsx")
 *
 *     private fun serveJs(filename: String): HttpResponse<String> {
 *         val path = "graphiql/js/" + filename
 *         val content = this::class.java.classLoader.getResource(path)?.readText()
 *         return if (content != null) HttpResponse.ok(content) else HttpResponse.notFound()
 *     }
 * }
 * ```
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
