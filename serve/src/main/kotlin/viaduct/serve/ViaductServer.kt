package viaduct.serve

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.slf4j.LoggerFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct

/**
 * Development server for Viaduct applications.
 *
 * Provides:
 * - GraphQL endpoint at POST /graphql
 * - GraphiQL IDE at GET /graphiql
 * - Health check at GET /health
 *
 * For hot-reload, use `./gradlew --continuous :yourapp:serve` which will
 * restart the server when source files change.
 *
 * @param port The port to bind to (default: 8080). Use 0 for any available port.
 * @param host The host to bind to (default: 0.0.0.0)
 * @param packagePrefix The package prefix for tenant modules. Required when no
 *        @ViaductServerConfiguration provider is found.
 */
class ViaductServer(
    private val port: Int = 8080,
    private val host: String = "0.0.0.0",
    private val packagePrefix: String? = null
) {
    private val logger = LoggerFactory.getLogger(ViaductServer::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private var viaduct: Viaduct? = null
    private var server: ApplicationEngine? = null
    var actualPort: Int = 0
        private set

    /**
     * Starts the development server.
     *
     * Discovers and instantiates a ViaductFactory, creates a Viaduct instance,
     * then starts the Ktor server.
     *
     * If port is set to 0, the server will bind to any available port.
     */
    fun start() {
        logger.info("Starting Viaduct Development Server...")

        try {
            // Discover and instantiate ViaductProvider
            logger.info("Discovering ViaductProvider...")
            val provider = FactoryDiscovery.discoverProvider(packagePrefix)
            logger.info("Found provider: {}", provider::class.qualifiedName)

            // Get Viaduct instance from provider
            logger.info("Getting Viaduct instance from provider...")
            viaduct = provider.getViaduct()
            logger.info("Viaduct instance obtained successfully")

            // Capture references for use in server configuration
            val loggerRef = logger
            val mapperRef = objectMapper

            // Start the server - embeddedServer returns different types in Ktor 2.x vs 3.x
            // In Ktor 2.x: returns ApplicationEngine directly
            // In Ktor 3.x: returns EmbeddedServer<TEngine, TConfig> with .engine property
            val embeddedServer = embeddedServer(Netty, port = port, host = host) {
                configureApplication(loggerRef, mapperRef)
            }

            // Start server without blocking initially
            embeddedServer.start(wait = false)

            // Get the engine - handle both Ktor 2.x and 3.x
            val engine: NettyApplicationEngine = try {
                // Ktor 3.x: EmbeddedServer has .engine property
                val engineProperty = embeddedServer::class.members.find { it.name == "engine" }
                engineProperty?.call(embeddedServer) as? NettyApplicationEngine
                    ?: embeddedServer as NettyApplicationEngine
            } catch (e: Exception) {
                // Ktor 2.x: embeddedServer IS the engine
                embeddedServer as NettyApplicationEngine
            }
            server = engine
            val portFuture: CompletableFuture<Int> = CoroutineScope(Dispatchers.IO).future {
                engine.resolvedConnectors().first().port
            }
            actualPort = portFuture.get(10, TimeUnit.SECONDS)

            if (port == 0) {
                loggerRef.info("Viaduct Development Server running on dynamically assigned port: $actualPort")
            } else {
                loggerRef.info("Viaduct Development Server running on port: $actualPort")
            }
            loggerRef.info("Server address: http://$host:$actualPort")
            loggerRef.info("GraphiQL IDE: http://$host:$actualPort/graphiql")
            loggerRef.info("")
            loggerRef.info("TIP: For automatic reload on code changes, use: ./gradlew --continuous :yourapp:serve")

            // Add shutdown hook for graceful shutdown on SIGINT/SIGTERM
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    server?.let {
                        loggerRef.info("Shutting down Viaduct Development Server...")
                        it.stop(1000, 2000)
                    }
                }
            )

            // Block the main thread until interrupted
            // The shutdown hook will stop the server on SIGINT/SIGTERM
            try {
                while (true) {
                    Thread.sleep(Long.MAX_VALUE)
                }
            } catch (e: InterruptedException) {
                // Expected when shutdown is triggered
                Thread.currentThread().interrupt()
            }
        } catch (e: Exception) {
            handleStartupError(e)
            throw e
        }
    }

    /**
     * Stops the development server.
     * Useful for testing and programmatic shutdown.
     */
    fun stop() {
        server?.let {
            logger.info("Stopping Viaduct Development Server...")
            it.stop(1000, 2000)
            server = null
        }
    }

    /**
     * Handles startup errors with helpful diagnostic messages.
     */
    private fun handleStartupError(e: Exception) {
        val rootCause = findRootCause(e)

        when {
            // NoSuchMethodException: Missing zero-argument constructor
            rootCause is NoSuchMethodException -> {
                val className = extractClassNameFromNoSuchMethod(rootCause)
                logResolverInstantiationError(className, "missing zero-argument constructor")
            }

            // InstantiationException: Abstract class or interface
            rootCause is InstantiationException -> {
                val className = rootCause.message ?: "unknown"
                logResolverInstantiationError(className, "cannot instantiate abstract class or interface")
            }

            // IllegalAccessException: Constructor not accessible
            rootCause is IllegalAccessException -> {
                val className = rootCause.message ?: "unknown"
                logResolverInstantiationError(className, "constructor is not accessible (make it public)")
            }

            // InvocationTargetException: Constructor threw an exception
            rootCause is InvocationTargetException -> {
                val constructorError = rootCause.targetException
                logger.error("")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
                logger.error("  RESOLVER CONSTRUCTOR ERROR")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
                logger.error("")
                logger.error("  A resolver's constructor threw an exception:")
                logger.error("    {}: {}", constructorError?.javaClass?.name, constructorError?.message)
                logger.error("")
                logger.error("  This typically happens when the constructor tries to access")
                logger.error("  dependencies that aren't available (database connections, services, etc.)")
                logger.error("")
                logger.error("  SOLUTIONS:")
                logger.error("  1. Fix the error in the resolver's constructor")
                logger.error("  2. Create a @ViaductServerConfiguration provider that properly")
                logger.error("     initializes dependencies before creating resolver instances")
                logger.error("")
                logger.error("  See: https://viaduct.dev/docs/developers/serve")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
            }

            // IllegalStateException from FactoryDiscovery (multiple providers, missing packagePrefix)
            rootCause is IllegalStateException && rootCause.message?.contains("ViaductServerConfiguration") == true -> {
                logger.error("")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
                logger.error("  CONFIGURATION ERROR")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
                logger.error("")
                logger.error("  {}", rootCause.message)
                logger.error("")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
            }

            // Generic error
            else -> {
                logger.error("")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
                logger.error("  VIADUCT DEVELOPMENT SERVER FAILED TO START")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
                logger.error("")
                logger.error("  Error: {}: {}", e.javaClass.simpleName, e.message)
                if (rootCause !== e) {
                    logger.error("  Caused by: {}: {}", rootCause.javaClass.simpleName, rootCause.message)
                }
                logger.error("")
                logger.error("  If this is a resolver instantiation issue, ensure your resolvers either:")
                logger.error("  1. Have public zero-argument constructors, OR")
                logger.error("  2. Create a @ViaductServerConfiguration provider with proper DI setup")
                logger.error("")
                logger.error("  See: https://viaduct.dev/docs/developers/serve")
                logger.error("═══════════════════════════════════════════════════════════════════════════════")
            }
        }
    }

    /**
     * Logs a detailed error message for resolver instantiation failures.
     */
    private fun logResolverInstantiationError(
        className: String,
        reason: String
    ) {
        logger.error("")
        logger.error("═══════════════════════════════════════════════════════════════════════════════")
        logger.error("  RESOLVER INSTANTIATION ERROR")
        logger.error("═══════════════════════════════════════════════════════════════════════════════")
        logger.error("")
        logger.error("  Failed to create resolver instance: {}", className)
        logger.error("  Reason: {}", reason)
        logger.error("")
        logger.error("  The default Viaduct factory requires all @Resolver classes to have")
        logger.error("  public zero-argument constructors because it cannot inject dependencies.")
        logger.error("")
        logger.error("  SOLUTIONS:")
        logger.error("")
        logger.error("  Option 1: Add a zero-argument constructor to your resolver")
        logger.error("  ─────────────────────────────────────────────────────────────────────────────")
        logger.error("    @Resolver")
        logger.error("    class MyResolver {")
        logger.error("        // No constructor parameters")
        logger.error("    }")
        logger.error("")
        logger.error("  Option 2: Create a @ViaductServerConfiguration provider with DI")
        logger.error("  ─────────────────────────────────────────────────────────────────────────────")
        logger.error("    @ViaductServerConfiguration")
        logger.error("    class MyViaductProvider : ViaductProvider {")
        logger.error("        override fun getViaduct(): Viaduct {")
        logger.error("            // Use your DI framework (Micronaut, Guice, etc.) to create Viaduct")
        logger.error("            return myDiContainer.getBean(Viaduct::class.java)")
        logger.error("        }")
        logger.error("    }")
        logger.error("")
        logger.error("  See: https://viaduct.dev/docs/developers/serve")
        logger.error("═══════════════════════════════════════════════════════════════════════════════")
    }

    /**
     * Finds the root cause of an exception chain.
     */
    private fun findRootCause(e: Throwable): Throwable {
        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }

    /**
     * Extracts the class name from a NoSuchMethodException message.
     */
    private fun extractClassNameFromNoSuchMethod(e: NoSuchMethodException): String {
        // NoSuchMethodException message format is typically "com.example.ClassName.<init>()"
        val message = e.message ?: return "unknown"
        return message.substringBefore(".<init>").ifEmpty { message }
    }

    /**
     * Formats error messages for query execution failures.
     * Returns a pair of (userMessage, logMessage) where userMessage is suitable for
     * returning in the GraphQL response and logMessage provides details for the logs.
     */
    private fun formatQueryExecutionError(e: Exception): Pair<String, String> {
        val rootCause = findRootCause(e)

        return when {
            // NoSuchMethodException: Missing zero-argument constructor
            rootCause is NoSuchMethodException -> {
                val className = extractClassNameFromNoSuchMethod(rootCause)
                val userMessage = "Resolver instantiation failed: $className is missing a zero-argument constructor. " +
                    "Either add a no-arg constructor or create a @ViaductServerConfiguration provider with DI support."
                val logMessage = "Resolver instantiation failed for $className (missing zero-argument constructor)"
                Pair(userMessage, logMessage)
            }

            // InstantiationException: Abstract class or interface
            rootCause is InstantiationException -> {
                val className = rootCause.message ?: "unknown"
                val userMessage = "Resolver instantiation failed: Cannot instantiate $className (abstract class or interface). " +
                    "Ensure your resolver is a concrete class with a zero-argument constructor."
                val logMessage = "Resolver instantiation failed for $className (abstract class or interface)"
                Pair(userMessage, logMessage)
            }

            // IllegalAccessException: Constructor not accessible
            rootCause is IllegalAccessException -> {
                val className = rootCause.message ?: "unknown"
                val userMessage = "Resolver instantiation failed: Constructor not accessible for $className. " +
                    "Make sure the constructor is public."
                val logMessage = "Resolver instantiation failed for $className (constructor not accessible)"
                Pair(userMessage, logMessage)
            }

            // InvocationTargetException: Constructor threw an exception
            rootCause is InvocationTargetException -> {
                val constructorError = rootCause.targetException
                val userMessage = "Resolver constructor threw an exception: ${constructorError?.javaClass?.simpleName}: ${constructorError?.message}. " +
                    "This typically means the resolver is trying to access dependencies that aren't available. " +
                    "Create a @ViaductServerConfiguration provider with proper DI setup."
                val logMessage = "Resolver constructor threw an exception during query execution"
                Pair(userMessage, logMessage)
            }

            // Generic error
            else -> {
                val userMessage = e.message ?: "Internal server error"
                val logMessage = "Error executing GraphQL query"
                Pair(userMessage, logMessage)
            }
        }
    }

    /**
     * Configures the Ktor application.
     */
    private fun Application.configureApplication(
        loggerRef: org.slf4j.Logger,
        mapperRef: ObjectMapper
    ) {
        install(ContentNegotiation) {
            jackson()
        }

        install(CORS) {
            anyHost()
            allowHeader("Content-Type")
        }

        routing {
            // Health check endpoint
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
            }

            // GraphQL endpoint
            post("/graphql") {
                val currentViaduct = viaduct
                if (currentViaduct == null) {
                    call.respondText(
                        """{"errors":[{"message":"Viaduct not initialized"}]}""",
                        ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                    return@post
                }

                try {
                    val body = call.receiveText()
                    val request = mapperRef.readValue<GraphQLRequest>(body)

                    // Log introspection queries for debugging
                    if (request.operationName == "IntrospectionQuery") {
                        loggerRef.info("Received schema introspection query from GraphiQL")
                    } else {
                        loggerRef.debug("Executing GraphQL query: ${request.query}")
                    }

                    val executionInput = ExecutionInput.create(
                        operationText = request.query,
                        operationName = request.operationName,
                        variables = request.variables ?: emptyMap()
                    )

                    val result = currentViaduct.executeAsync(executionInput).await()

                    val response = mapOf(
                        "data" to result.getData<Any>(),
                        "errors" to result.errors?.map { error ->
                            mapOf(
                                "message" to error.message,
                                "locations" to error.locations,
                                "path" to error.path,
                                "extensions" to error.extensions
                            )
                        }
                    )

                    val json = mapperRef.writeValueAsString(response)
                    call.respondText(json, ContentType.Application.Json)
                } catch (e: Exception) {
                    val (userMessage, logMessage) = formatQueryExecutionError(e)
                    loggerRef.error(logMessage, e)
                    val errorResponse = mapOf(
                        "errors" to listOf(
                            mapOf(
                                "message" to userMessage,
                                "extensions" to mapOf("exception" to e::class.simpleName)
                            )
                        )
                    )
                    val json = mapperRef.writeValueAsString(errorResponse)
                    call.respondText(json, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }

            // GraphiQL IDE
            get("/graphiql") {
                call.respondText(graphiQLHtml(), ContentType.Text.Html)
            }

            // Serve GraphiQL static resources (JS files for plugins)
            // Resources are packaged in service-wiring module at /graphiql/js/
            get("/js/{file}") {
                val file = call.parameters["file"]
                if (file != null) {
                    val resourcePath = "/graphiql/js/$file"
                    // Use multiple classloader strategies to find resources from service-wiring
                    val resourceStream = Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath.removePrefix("/"))
                        ?: ViaductServer::class.java.getResourceAsStream(resourcePath)

                    if (resourceStream != null) {
                        val content = resourceStream.bufferedReader().use { it.readText() }
                        val contentType = when {
                            file.endsWith(".js") -> ContentType.Text.JavaScript
                            file.endsWith(".jsx") -> ContentType.Text.JavaScript
                            else -> ContentType.Application.OctetStream
                        }
                        call.respondText(content, contentType)
                    } else {
                        loggerRef.warn("Static resource not found: $resourcePath")
                        call.respond(HttpStatusCode.NotFound, "File not found: $file")
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest, "File parameter missing")
                }
            }

            // Root redirects to GraphiQL
            get("/") {
                call.respondText(
                    """<html><head><meta http-equiv="refresh" content="0; url=/graphiql"></head></html>""",
                    ContentType.Text.Html,
                    HttpStatusCode.OK
                )
            }
        }
    }

    /**
     * Data class for GraphQL requests.
     */
    private data class GraphQLRequest(
        val query: String,
        val variables: Map<String, Any?>? = null,
        val operationName: String? = null
    )
}
