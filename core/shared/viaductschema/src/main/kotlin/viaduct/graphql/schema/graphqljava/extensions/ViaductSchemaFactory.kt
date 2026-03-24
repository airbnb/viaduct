package viaduct.graphql.schema.graphqljava.extensions

import graphql.schema.GraphQLSchema
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.net.URL
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.timer.Timer

/**
 * Factory for creating [ViaductSchema] instances.
 *
 * This object provides static methods for Java interoperability, wrapping the
 * extension functions defined on [ViaductSchema.Companion].
 */
object ViaductSchemaFactory {
    /**
     * Creates a [ViaductSchema] from a list of schema file URLs.
     */
    @JvmStatic
    @JvmName("fromGraphQLSchemaUrls")
    fun fromGraphQLSchema(inputFiles: List<URL>): ViaductSchema = ViaductSchema.fromGraphQLSchema(inputFiles)

    /**
     * Creates a [ViaductSchema] from a list of schema files.
     */
    @JvmStatic
    @JvmOverloads
    fun fromGraphQLSchema(
        inputFiles: List<File>,
        timer: Timer = Timer()
    ): ViaductSchema = ViaductSchema.fromGraphQLSchema(inputFiles, timer)

    /**
     * Creates a [ViaductSchema] from a [TypeDefinitionRegistry].
     */
    @JvmStatic
    @JvmOverloads
    @JvmName("fromGraphQLSchemaRegistry")
    fun fromGraphQLSchema(
        registry: TypeDefinitionRegistry,
        timer: Timer = Timer()
    ): ViaductSchema = ViaductSchema.fromGraphQLSchema(registry, timer)

    /**
     * Creates a [ViaductSchema] from a [GraphQLSchema].
     */
    @JvmStatic
    @JvmName("fromGraphQLSchemaInstance")
    fun fromGraphQLSchema(schema: GraphQLSchema): ViaductSchema = ViaductSchema.fromGraphQLSchema(schema)

    /**
     * Creates a [ViaductSchema] from a list of schema file URLs using TypeDefinitionRegistry.
     */
    @JvmStatic
    @JvmName("fromTypeDefinitionRegistryUrls")
    fun fromTypeDefinitionRegistry(inputFiles: List<URL>): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(inputFiles)

    /**
     * Creates a [ViaductSchema] from a list of schema files using TypeDefinitionRegistry.
     */
    @JvmStatic
    @JvmOverloads
    fun fromTypeDefinitionRegistry(
        inputFiles: List<File>,
        timer: Timer = Timer()
    ): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(inputFiles, timer)

    /**
     * Creates a [ViaductSchema] from a [TypeDefinitionRegistry].
     */
    @JvmStatic
    @JvmOverloads
    @JvmName("fromTypeDefinitionRegistryInstance")
    fun fromTypeDefinitionRegistry(
        registry: TypeDefinitionRegistry,
        timer: Timer = Timer()
    ): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(registry, timer)

    /**
     * Creates a [ViaductSchema] from a GraphQL SDL string.
     */
    @JvmStatic
    @JvmOverloads
    fun fromTypeDefinitionRegistry(
        sdl: String,
        timer: Timer = Timer()
    ): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(sdl, timer)
}
