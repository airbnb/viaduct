package viaduct.serve

/**
 * Entry point for the Viaduct serve server.
 *
 * Usage (recommended):
 *   ./gradlew --continuous :yourapp:serve -Pserve.packagePrefix=com.example.app
 *
 * This runs the server with automatic reload - when you change source files,
 * Gradle will rebuild and restart the server automatically.
 *
 * This is the main class referenced by the viaduct-application-plugin's serve task.
 * It starts a ViaductServer that:
 * 1. Discovers @ViaductServerConfiguration providers, or falls back to DefaultViaductFactory
 * 2. Serves GraphQL at /graphql
 * 3. Provides GraphiQL IDE at /graphiql
 *
 * System properties:
 * - serve.port: Port to bind to (default: 8080). Use 0 for any available port.
 * - serve.host: Host to bind to (default: 0.0.0.0)
 * - serve.packagePrefix: Package prefix for tenant modules (required when using DefaultViaductFactory)
 */
fun main() {
    val port = System.getProperty("serve.port", "8080").toIntOrNull() ?: 8080
    val host = System.getProperty("serve.host", "0.0.0.0")
    val packagePrefix = System.getProperty("serve.packagePrefix")

    ViaductServer(port = port, host = host, packagePrefix = packagePrefix).start()
}
