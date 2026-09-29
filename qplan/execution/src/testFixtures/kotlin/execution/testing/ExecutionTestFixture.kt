package execution.testing

import execution.QPlanExecutionStrategy
import execution.QPlanInstrumentation
import execution.QPlanWiringFactory
import graphql.Directives
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.language.AstPrinter
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.parser.Parser
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import model.ObjectEngineResult
import model.SourceSchemaAdapter
import model.testing.TestWorld
import semantics.resolver26.ResolutionDispatcherFactory
import semantics.resolver26.configuredResolutionThreadCount
import semantics.shared.ResolverObserver
import viaduct.graphql.schema.ViaductSchema

/**
 * End-to-end GraphQL-Java harness backed by one qplan reasoning world.
 */
class ExecutionTestFixture private constructor(
    private val graphQL: GraphQL,
    private val root: ObjectEngineResult? = null,
    private val ownedResolverDispatcher: ExecutorCoroutineDispatcher? = null,
) : AutoCloseable {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        incrementalSupport: Boolean = false,
        resolverObserver: ResolverObserver = ResolverObserver.NOP,
    ): ExecutionResult =
        try {
            runQueryAsync(query, variables, incrementalSupport, resolverObserver).join()
        } catch (exception: CompletionException) {
            throw exception.cause ?: exception
        }

    fun runQueryAsync(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        incrementalSupport: Boolean = false,
        resolverObserver: ResolverObserver = ResolverObserver.NOP,
    ): CompletableFuture<ExecutionResult> {
        val input =
            ExecutionInput
                .newExecutionInput()
                .query(query)
                .variables(variables)
                .graphQLContext { context -> context.put(ResolverObserver::class.java, resolverObserver) }
        if (incrementalSupport) {
            GraphQL
                .unusualConfiguration(input)
                .incrementalSupport()
                .enableIncrementalSupport(true)
                .enableEarlyIncrementalFieldExecution(true)
        }
        root?.let(input::root)
        return graphQL.executeAsync(input.build())
    }

    override fun close() {
        ownedResolverDispatcher?.close()
    }

    internal fun ownedResolverDispatcherIsShutdown(): Boolean? =
        ownedResolverDispatcher?.let { dispatcher ->
            (dispatcher.executor as ExecutorService).isShutdown
        }

    companion object {
        fun fromSDL(schemaSDL: String): ExecutionTestFixture =
            fromWorld(
                schemaSDL = schemaSDL,
                world = TestWorld.fromSDL(schemaSDL),
            )

        fun fromResolverDSL(
            schemaSDL: String,
            resolverSchemaSDL: String,
        ): ExecutionTestFixture =
            fromWorld(
                schemaSDL = schemaSDL,
                world = TestWorld.fromDSL(resolverSchemaSDL),
            )

        internal fun fromResolverDSL(
            schemaSDL: String,
            resolverSchemaSDL: String,
            resolverCoroutineContext: CoroutineContext,
        ): ExecutionTestFixture =
            fromWorld(
                schemaSDL = schemaSDL,
                world = TestWorld.fromDSL(resolverSchemaSDL),
                resolverCoroutineContext = resolverCoroutineContext,
            )

        /**
         * Builds a fixture directly from resolver-test DSL.
         *
         * The executable GraphQL schema is derived by removing fixture-only directives from the
         * DSL document. The qplan world independently compiles the same document into resolvers.
         */
        fun fromResolverDSL(resolverSchemaSDL: String): ExecutionTestFixture =
            fromResolverDSL(
                schemaSDL = executableSchemaSDL(resolverSchemaSDL),
                resolverSchemaSDL = resolverSchemaSDL,
            )

        internal fun fromResolverDSL(
            resolverSchemaSDL: String,
            resolverCoroutineContext: CoroutineContext,
        ): ExecutionTestFixture =
            fromResolverDSL(
                schemaSDL = executableSchemaSDL(resolverSchemaSDL),
                resolverSchemaSDL = resolverSchemaSDL,
                resolverCoroutineContext = resolverCoroutineContext,
            )

        @Suppress("TooGenericExceptionCaught") // Release the dispatcher even if setup throws an Error.
        internal fun fromWorld(
            schemaSDL: String,
            world: TestWorld,
        ): ExecutionTestFixture {
            val dispatcher =
                ResolutionDispatcherFactory.create(configuredResolutionThreadCount())
            return try {
                createFromWorld(
                    schemaSDL = schemaSDL,
                    world = world,
                    resolverCoroutineContext = dispatcher,
                    ownedResolverDispatcher = dispatcher,
                )
            } catch (throwable: Throwable) {
                dispatcher.close()
                throw throwable
            }
        }

        internal fun fromWorld(
            schemaSDL: String,
            world: TestWorld,
            resolverCoroutineContext: CoroutineContext,
        ): ExecutionTestFixture =
            createFromWorld(
                schemaSDL = schemaSDL,
                world = world,
                resolverCoroutineContext = resolverCoroutineContext,
            )

        private fun createFromWorld(
            schemaSDL: String,
            world: TestWorld,
            resolverCoroutineContext: CoroutineContext,
            ownedResolverDispatcher: ExecutorCoroutineDispatcher? = null,
        ): ExecutionTestFixture {
            val runtimeWiring =
                RuntimeWiring
                    .newRuntimeWiring()
                    .wiringFactory(QPlanWiringFactory(SourceSchemaAdapter(world.schema)))
                    .build()
            val graphQLSchema =
                SchemaGenerator().makeExecutableSchema(
                    SchemaParser().parse(schemaSDL),
                    runtimeWiring,
                ).transform { builder -> builder.additionalDirective(Directives.DeferDirective) }
            val graphQL =
                GraphQL
                    .newGraphQL(graphQLSchema)
                    .queryExecutionStrategy(
                        QPlanExecutionStrategy(
                            world = world.assumptions,
                            resolverCoroutineContext = resolverCoroutineContext,
                        ),
                    )
                    .instrumentation(QPlanInstrumentation())
                    .build()
            return ExecutionTestFixture(
                graphQL = graphQL,
                ownedResolverDispatcher = ownedResolverDispatcher,
            )
        }

        /**
         * Builds a vanilla GraphQL-Java executor that completes fields from [root].
         */
        fun fromResolvedRoot(
            schemaSDL: String,
            schema: ViaductSchema,
            root: ObjectEngineResult,
        ): ExecutionTestFixture {
            val runtimeWiring =
                RuntimeWiring
                    .newRuntimeWiring()
                    .wiringFactory(QPlanWiringFactory(SourceSchemaAdapter(schema)))
                    .build()
            val graphQLSchema =
                SchemaGenerator().makeExecutableSchema(
                    SchemaParser().parse(schemaSDL),
                    runtimeWiring,
                ).transform { builder -> builder.additionalDirective(Directives.DeferDirective) }
            return ExecutionTestFixture(
                graphQL = GraphQL.newGraphQL(graphQLSchema).build(),
                root = root,
            )
        }
    }
}

private fun executableSchemaSDL(resolverSchemaSDL: String): String {
    val document = Parser.parse(resolverSchemaSDL)
    val stripped =
        document.transform { builder ->
            builder.definitions(document.definitions.map(::stripResolverDirectives))
        }
    return BUILT_IN_SCHEMA + "\n" + AstPrinter.printAst(stripped)
}

private fun stripResolverDirectives(definition: Definition<*>): Definition<*> =
    when (definition) {
        is ObjectTypeExtensionDefinition ->
            definition.transformExtension { builder ->
                builder
                    .directives(definition.directives.withoutResolverDirectives())
                    .fieldDefinitions(
                        definition.fieldDefinitions.map { it.withoutResolverDirectives() },
                    )
            }
        is ObjectTypeDefinition ->
            definition.transform { builder ->
                builder
                    .directives(definition.directives.withoutResolverDirectives())
                    .fieldDefinitions(
                        definition.fieldDefinitions.map { it.withoutResolverDirectives() },
                    )
            }
        else -> definition
    }

private fun FieldDefinition.withoutResolverDirectives(): FieldDefinition = transform { builder -> builder.directives(directives.withoutResolverDirectives()) }

private fun List<Directive>.withoutResolverDirectives(): List<Directive> = filterNot { directive -> directive.name in RESOLVER_DIRECTIVES }

private val RESOLVER_DIRECTIVES = setOf("resolver", "nodeResolver")

private val BUILT_IN_SCHEMA =
    """
    directive @parent on FIELD_DEFINITION
    interface Node { id: ID! }
    type Query { node(id: ID!): Node }
    """.trimIndent()
