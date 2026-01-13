package viaduct.api.testing

import viaduct.api.context.ExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.apiannotations.StableApi
import viaduct.apiannotations.TestingApi

/**
 * Base interface for all resolver testers.
 * Provides common configuration and execution context setup for testing Viaduct resolvers.
 *
 * ## Usage
 *
 * Create a specific tester instance using the companion factory methods:
 * - [FieldResolverTester.create] for field resolvers
 * - [MutationResolverTester.create] for mutation resolvers
 * - [NodeResolverTester.create] for node resolvers
 *
 * Example:
 * ```kotlin
 * val tester = FieldResolverTester.create<
 *     MyObject, Query, MyArguments, MyOutput
 * >(
 *     ResolverTester.TesterConfig(schemaSDL = mySchemaSDL)
 * )
 *
 * val result = tester.test(MyResolver()) {
 *     objectValue = myObject
 *     arguments = myArgs
 * }
 * ```
 *
 * @since 1.0
 */
@StableApi
@TestingApi
interface ResolverTester {
    /**
     * The execution context used to build test objects.
     * This context should be used with Builder constructors like:
     * `MyObject.Builder(context).field(value).build()`
     */
    val context: ExecutionContext

    /**
     * Configuration for this tester instance.
     */
    val config: TesterConfig

    /**
     * Creates a GlobalID for use in tests.
     *
     * @param type The type information for the node
     * @param internalID The internal ID string
     * @return A GlobalID that can be used in resolver tests
     */
    fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> = context.globalIDFor(type, internalID)

    /**
     * Configuration data for resolver testers.
     *
     * @property schemaSDL GraphQL schema SDL string used to parse type information.
     *                     Can be loaded from resources or inline.
     * @property grtPackage Package name where generated GRTs are located.
     *                      Default is "viaduct.api.grts".
     * @property classLoader Custom class loader for reflection operations.
     *                       Default is system class loader.
     */
    data class TesterConfig(
        val schemaSDL: String,
        val grtPackage: String = "viaduct.api.grts",
        val classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
    ) {
        companion object {
            /**
             * Creates a TesterConfig from a schema SDL string loaded from resources.
             *
             * @param resourcePath Path to the schema file in resources (e.g., "/schema.graphql")
             * @param grtPackage Package name for generated GRTs
             * @param classLoader Class loader to use for loading the resource
             */
            fun fromResource(
                resourcePath: String,
                grtPackage: String = "viaduct.api.grts",
                classLoader: ClassLoader = ClassLoader.getSystemClassLoader()
            ): TesterConfig {
                val schemaSDL = classLoader.getResourceAsStream(resourcePath.removePrefix("/"))
                    ?.bufferedReader()
                    ?.readText()
                    ?: throw IllegalArgumentException("Schema resource not found: $resourcePath")
                return TesterConfig(schemaSDL, grtPackage, classLoader)
            }
        }
    }
}
