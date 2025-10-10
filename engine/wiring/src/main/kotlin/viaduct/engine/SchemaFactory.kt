package viaduct.engine

import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.errors.SchemaProblem
import io.github.classgraph.ClassGraph
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.graphql.Scalars
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.utils.slf4j.logger

class SchemaFactory
    @Inject
    constructor(
        private val coroutineInterop: CoroutineInterop,
    ) {
        companion object {
            private val log by logger()
        }

        fun fromSdl(sdl: String): ViaductSchema {
            return schemaFromSdl(sdl, coroutineInterop)
        }

        fun fromResources(
            packagePrefix: String? = null,
            filesIncluded: Regex? = null,
        ): ViaductSchema {
            return schemaFromRuntimeSchemaFiles(packagePrefix, filesIncluded ?: Regex(".*graphqls"))
        }

        /**
         * This function is used to get the full schema files available during runtime
         */
        @OptIn(ExperimentalTime::class)
        private fun schemaFromRuntimeSchemaFiles(
            packagePrefix: String?,
            filesIncluded: Regex = Regex(".*graphqls"),
        ): ViaductSchema {
            val resourceContents = mutableMapOf<String, String>()

            val (resources, elapsedTime) = measureTimedValue {
                ClassGraph().scan().use {
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
            log.debug(
                "Got {} resources for pattern {} in {}",
                resources.size,
                filesIncluded.toString(),
                elapsedTime
            )

            if (resources.isEmpty()) {
                throw ViaductSchemaLoadException(
                    "No GraphQL schema files found matching pattern '$filesIncluded' in package prefix '$packagePrefix'. " +
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
        ): ViaductSchema {
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

            // Add default Viaduct schema components
            try {
                DefaultSchemaProvider.addDefaults(tdr)
            } catch (e: Exception) {
                throw ViaductSchemaLoadException(
                    "Failed to add default schema components.",
                    e
                )
            }

            val definedScalars = DefaultSchemaProvider.defaultScalars() + (customScalars ?: emptySet())
            val actualWiringFactory = ViaductWiringFactory(coroutineInterop)
            val wiring = RuntimeWiring.newRuntimeWiring().wiringFactory(actualWiringFactory).apply {
                definedScalars.forEach { scalar(it) }
            }.build()

            // Let SchemaProblem and other GraphQL validation errors pass through
            return ViaductSchema(SchemaGenerator().makeExecutableSchema(tdr, wiring))
        }
    }
