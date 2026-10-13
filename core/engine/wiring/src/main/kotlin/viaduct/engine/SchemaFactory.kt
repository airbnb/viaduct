@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine

import graphql.schema.GraphQLScalarType
import graphql.schema.idl.FastSchemaGenerator
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.errors.SchemaProblem
import io.github.classgraph.ClassGraph
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import viaduct.bootstrap.ViaductResourceIndexes
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.graphql.Scalars
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.utils.resources.ClasspathResourceIndex
import viaduct.utils.slf4j.logger

class SchemaFactory(
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
) {
    companion object {
        private val log by logger()
    }

    fun fromSdl(sdl: String): EngineSchema {
        return schemaFromSdl(sdl, coroutineInterop)
    }

    /**
     * Builds a schema from a registry that already contains Viaduct's default schema components.
     */
    fun fromPrebuiltTypeDefinitionRegistry(typeRegistry: TypeDefinitionRegistry): EngineSchema {
        return schemaFromTypeDefinitionRegistry(
            typeRegistry,
            coroutineInterop,
            customScalars = null,
            addDefaultSchemaComponents = false,
            useAppliedDirectivesOnly = true,
        )
    }

    fun fromResources(
        grtPackagePrefix: String? = null,
        filesIncluded: Regex? = null,
    ): EngineSchema {
        return schemaFromRuntimeSchemaFiles(
            grtPackagePrefix,
            filesIncluded ?: Regex(".*graphqls"),
            Thread.currentThread().contextClassLoader ?: SchemaFactory::class.java.classLoader,
        )
    }

    internal fun fromResources(
        grtPackagePrefix: String?,
        filesIncluded: Regex?,
        classLoader: ClassLoader,
    ): EngineSchema = schemaFromRuntimeSchemaFiles(grtPackagePrefix, filesIncluded ?: Regex(".*graphqls"), classLoader)

    /**
     * This function is used to get the full schema files available during runtime
     */
    @OptIn(ExperimentalTime::class)
    private fun schemaFromRuntimeSchemaFiles(
        grtPackagePrefix: String?,
        filesIncluded: Regex = Regex(".*graphqls"),
        classLoader: ClassLoader,
    ): EngineSchema {
        val resourceContents = mutableMapOf<String, String>()

        val (resources, elapsedTime) = measureTimedValue {
            val index = ClasspathResourceIndex.load(ViaductResourceIndexes.SCHEMA, classLoader)
            if (index != null) {
                val acceptedPath = grtPackagePrefix?.replace('.', '/')
                index.paths
                    .filter { path ->
                        (acceptedPath == null || path == acceptedPath || path.startsWith("$acceptedPath/")) &&
                            filesIncluded.matches(path)
                    }
                    .map { path ->
                        val url = index.resourceUrl(path)
                        val uniqueKey = url.toExternalForm()
                        val content = url.openStream().use { stream ->
                            stream.reader(Charsets.UTF_8).readText().trim()
                        }
                        if (content.isEmpty()) {
                            log.warn("Empty schema file found: {}", uniqueKey)
                        }
                        resourceContents[uniqueKey] = content
                        uniqueKey
                    }
            } else {
                log.warn(
                    "No Viaduct schema resource index found at {}; falling back to a ClassGraph scan",
                    ViaductResourceIndexes.SCHEMA,
                )
                val classGraph = ClassGraph()
                if (grtPackagePrefix != null) {
                    classGraph.acceptPaths(grtPackagePrefix.replace('.', '/'))
                }
                classGraph.scan().use {
                    it.getResourcesMatchingPattern(filesIncluded.toPattern()).map { res ->
                        val origin =
                            res.classpathElementURI?.toString() ?: res.classpathElementURL?.toString() ?: "unknown"
                        val uniqueKey = "$origin!/${res.path}"
                        val content = res.open().use { stream ->
                            stream.reader(Charsets.UTF_8).readText().trim()
                        }
                        if (content.isEmpty()) {
                            log.warn("Empty schema file found: {}", uniqueKey)
                        }
                        resourceContents[uniqueKey] = content
                        uniqueKey
                    }
                }
            }
        }
        log.debug(
            "Got {} resources for pattern {} in {}",
            resources.size,
            filesIncluded.toString(),
            elapsedTime
        )

        if (resources.isEmpty()) {
            throw ViaductSchemaLoadException(
                "No GraphQL schema files found matching pattern '$filesIncluded' in package prefix '$grtPackagePrefix'. " +
                    "Please ensure your .graphqls files are available in the classpath."
            )
        }

        val sdl = resourceContents.values.joinToString("\n")
        if (sdl.isBlank()) {
            throw ViaductSchemaLoadException(
                "All GraphQL schema files are empty. Found files: ${resources.joinToString(", ")}. " +
                    "Please ensure your .graphqls files contain valid GraphQL schema definitions."
            )
        }

        return try {
            schemaFromSdl(
                sdl,
                coroutineInterop,
                listOf(Scalars.BackingData),
                resourceContents.keys.toList()
            )
        } catch (e: SchemaProblem) {
            // Let SchemaProblem pass through unchanged - it already contains detailed error info
            throw e
        } catch (e: Exception) {
            throw ViaductSchemaLoadException(
                "Failed to parse GraphQL schema from files: ${resources.joinToString(", ")}. " +
                    "Original error: ${e.message}",
                e
            )
        }
    }

    /**
     * This function is used to get the schema from a graphqls string
     */
    private fun schemaFromSdl(
        sdl: String,
        coroutineInterop: CoroutineInterop,
        customScalars: List<GraphQLScalarType>? = null,
        sourceFiles: List<String>? = null,
    ): EngineSchema {
        if (sdl.trim().isEmpty()) {
            val sourceInfo = if (sourceFiles?.isNotEmpty() == true) {
                " Source files: ${sourceFiles.joinToString(", ")}"
            } else {
                ""
            }
            throw ViaductSchemaLoadException(
                "GraphQL schema SDL is empty or contains only whitespace.$sourceInfo " +
                    "Please provide a valid GraphQL schema definition."
            )
        }

        val tdr = try {
            SchemaParser().parse(sdl)
        } catch (e: Exception) {
            val sourceInfo = if (sourceFiles?.isNotEmpty() == true) {
                " Source files: ${sourceFiles.joinToString(", ")}"
            } else {
                ""
            }
            throw ViaductSchemaLoadException(
                "Failed to parse GraphQL schema.$sourceInfo Original error: ${e.message}",
                e
            )
        }

        return schemaFromTypeDefinitionRegistry(
            tdr,
            coroutineInterop,
            customScalars,
            addDefaultSchemaComponents = true,
            useAppliedDirectivesOnly = false,
        )
    }

    private fun schemaFromTypeDefinitionRegistry(
        typeRegistry: TypeDefinitionRegistry,
        coroutineInterop: CoroutineInterop,
        customScalars: List<GraphQLScalarType>?,
        addDefaultSchemaComponents: Boolean,
        useAppliedDirectivesOnly: Boolean,
    ): EngineSchema {
        if (addDefaultSchemaComponents) {
            try {
                DefaultSchemaFactory.addDefaults(typeRegistry)
            } catch (e: Exception) {
                throw ViaductSchemaLoadException(
                    "Failed to add default schema components.",
                    e
                )
            }
        }

        val definedScalars = DefaultSchemaFactory.defaultScalars() + (customScalars ?: emptySet())
        val actualWiringFactory = ViaductWiringFactory(coroutineInterop)
        val wiring = RuntimeWiring.newRuntimeWiring().wiringFactory(actualWiringFactory).apply {
            definedScalars.forEach { scalar(it) }
        }.build()

        // Let SchemaProblem and other GraphQL validation errors pass through
        // FastSchemaGenerator does not preserve schema-level directives/extensions. Fall back to
        // SchemaGenerator for compatibility when that metadata is present.
        val options =
            SchemaGenerator.Options
                .defaultOptions()
                .useAppliedDirectivesOnly(useAppliedDirectivesOnly)
        val schema =
            if (hasSchemaLevelMetadata(typeRegistry)) {
                SchemaGenerator().makeExecutableSchema(options, typeRegistry, wiring)
            } else {
                FastSchemaGenerator().makeExecutableSchema(options, typeRegistry, wiring)
            }
        return EngineSchema(schema)
    }

    private fun hasSchemaLevelMetadata(typeRegistry: graphql.schema.idl.TypeDefinitionRegistry): Boolean {
        val schemaDefinition = typeRegistry.schemaDefinition().getOrNull()
        return (schemaDefinition?.directives?.isNotEmpty() == true) || typeRegistry.schemaExtensionDefinitions.isNotEmpty()
    }
}
