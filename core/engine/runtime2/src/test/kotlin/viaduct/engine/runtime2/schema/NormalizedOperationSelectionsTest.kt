package viaduct.engine.runtime2.schema

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ExecutionContext
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.execution.conditional.ConditionalNodeDecision
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.normalized.NormalizedDocument
import graphql.parser.Parser
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.QueryComplexityLimits
import graphql.validation.Validator
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.validation.IncrementalDirectiveValidation
import viaduct.engine.runtime2.execution.AstOperationSelectionProvider
import viaduct.engine.runtime2.execution.NormalizedOperationSelectionProvider
import viaduct.engine.runtime2.execution.RequestLifetimeInstrumentation
import viaduct.engine.runtime2.execution.Runtime2ExecutionStrategy
import viaduct.engine.runtime2.execution.Runtime2OperationSelectionProvider
import viaduct.engine.runtime2.execution.Runtime2WiringFactory
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.successorDemand

class NormalizedOperationSelectionsTest {
    @Test
    fun `construction and root collection do not decode descendants and shared children retain identity`() {
        val fixture = Fixture("type Query { first: Box second: Box } type Box { child: Child } type Child { value: Int }")
        val document = fixture.document("{ first { ...BoxFields } second { ...BoxFields } } fragment BoxFields on Box { child { value } }")
        val source = fixture.source(document)
        val forest = source.selections(document.operation)
        assertEquals(0, source.decodedFieldCount)
        val roots = forest.members()
        assertEquals(2, source.decodedFieldCount)
        val first = roots[0].subselections.single()
        val second = roots[1].subselections.single()
        assertEquals(3, source.decodedFieldCount)
        assertSame(first.subselections, second.subselections)
        assertEquals("value", first.subselections.single().key.field.name)
        assertEquals(4, source.decodedFieldCount)
        assertEquivalent(fixture.baseline(document), forest)
    }

    @Test
    fun `aliases arguments defaults and conditional fragment scopes match the existing parser`() {
        val fixture = Fixture(
            """
            type Query { pet: Pet search(filter: Filter = {enabled: true}, limit: Int = 3): Int }
            input Filter { enabled: Boolean = false tags: [String!] = ["all"] }
            interface Pet { name: String }
            type Cat implements Pet { name: String lives: Int }
            type Dog implements Pet { name: String bark: Int }
            """.trimIndent(),
        )
        val query = """
            query Test(${'$'}show: Boolean!, ${'$'}limit: Int, ${'$'}filter: Filter) {
              first: search(limit: ${'$'}limit, filter: ${'$'}filter)
              second: search(limit: ${'$'}limit, filter: ${'$'}filter)
              defaulted: search
              pet {
                __typename
                ...Names @include(if: ${'$'}show)
                ...Names @skip(if: ${'$'}show)
                ... on Cat { ... on Pet { name } lives @skip(if: ${'$'}show) }
                ... on Dog @include(if: ${'$'}show) { bark }
              }
            }
            fragment Names on Pet { name }
        """.trimIndent()
        for (show in listOf(false, true)) {
            for (optional in listOf(emptyMap(), mapOf("limit" to null, "filter" to null), mapOf("limit" to 7, "filter" to mapOf("tags" to listOf("one"))))) {
                val document = fixture.document(query, optional + ("show" to show))
                val actual = fixture.source(document).selections(document.operation)
                assertEquivalent(fixture.baseline(document), actual)
                assertEquals(if (optional.isEmpty()) 2 else 3, actual.merge(fixture.schemas.loweredSchema.requireQueryTypeDef()).size)
            }
        }
    }

    @Test
    fun `one cached ND can serve different binding environments concurrently`() {
        val fixture = Fixture("type Query { value(arg: Int!): Int }")
        val document = fixture.document("query(${'$'}arg: Int!) { value(arg: ${'$'}arg) }", mapOf("arg" to 1))
        val first = fixture.source(document)
        val second = fixture.source(document.copy(variables = CoercedVariables.of(mapOf("arg" to 2))))
        val a = first.selections(document.operation)
        val b = second.selections(document.operation)
        assertNotSame(a, b)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll(List(40) { index -> Callable { (if (index % 2 == 0) a else b).single() } })
                .map { it.get(5, TimeUnit.SECONDS) }
            results.forEachIndexed { index, selection ->
                assertEquals(if (index % 2 == 0) 1 else 2, selection.key.arguments.fieldExpressions()["arg"])
                assertSame(if (index % 2 == 0) a.single() else b.single(), selection)
            }
            assertEquals(1, first.decodedFieldCount)
            assertEquals(1, second.decodedFieldCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `operation selection uses the selected AST identity`() {
        val fixture = Fixture("type Query { first: Int second: Int }")
        val document = fixture.document("query First { first } query Second { second }", operationName = "Second")
        val source = fixture.source(document)
        assertEquals("second", source.selections(document.operation).single().key.field.name)
        val foreign = Parser.parse("{ first }").getDefinitionsOfType(OperationDefinition::class.java).single()
        assertThrows<IllegalArgumentException> { source.selections(foreign) }
    }

    @Test
    fun `merging conditional aliases defers children and preserves each branch guard`() {
        val fixture = Fixture("type Query { box: Box } type Box { first: Int second: Int }")
        for (show in listOf(false, true)) {
            val document = fixture.document(
                "query(${'$'}show: Boolean!) { a: box @include(if: ${'$'}show) { first } b: box @skip(if: ${'$'}show) { second } }",
                mapOf("show" to show),
            )
            val source = fixture.source(document)
            val merged = source.selections(document.operation).merge(fixture.schemas.loweredSchema.requireQueryTypeDef()).single()
            assertEquals(2, source.decodedFieldCount)
            assertEquals(InclusionCondition.Always, merged.inclusionCondition)
            val children = merged.subselections.members().associateBy { it.key.field.name }
            assertEquals(if (show) InclusionCondition.Always else InclusionCondition.Never, children.getValue("first").inclusionCondition)
            assertEquals(if (show) InclusionCondition.Never else InclusionCondition.Always, children.getValue("second").inclusionCondition)
        }
    }

    @Test
    fun `subscription roots and introspection-only forests match the existing parser`() {
        val fixture = Fixture("type Query { idle: Int } type Subscription { event: Event } type Event { value: Int }")
        for (query in listOf("subscription { event { value } }", "{ __typename }")) {
            val document = fixture.document(query)
            assertEquivalent(fixture.baseline(document), fixture.source(document).selections(document.operation))
        }
    }

    @Test
    fun `node lowering and nested abstract scopes match the existing parser`() {
        val fixture = Fixture(
            """
            type Query { node(id: ID!): Node i1: I1 }
            interface Node { id: ID! }
            type User implements Node { id: ID! name: String }
            interface I1 { value: Int }
            interface I2 { value: Int }
            interface I3 { value: Int }
            type A implements I1 { value: Int }
            type B implements I1 & I2 { value: Int }
            type C implements I2 & I3 { value: Int }
            type D implements I3 { value: Int }
            """.trimIndent(),
        )
        val document = fixture.document("{ node(id: \"123\") { id ... on User { name } } i1 { ... on I2 { ... on I3 { value } } } }")
        assertEquivalent(fixture.baseline(document), fixture.source(document).selections(document.operation))
    }

    @Test
    fun `long named-fragment chains are collected without recursive scope expansion`() {
        val fixture = Fixture("type Query { value: Int }")
        val query = buildString {
            append("{ ...F0 }\n")
            repeat(1_600) { index -> append("fragment F$index on Query { ...F${index + 1} }\n") }
            append("fragment F1600 on Query { value }")
        }
        val document = fixture.document(query)
        val source = fixture.source(document)
        assertEquals("value", source.selections(document.operation).single().key.field.name)
        assertEquals(1, source.decodedFieldCount)
    }

    @Test
    fun `production execution preserves aliases dependencies variables and mutation order`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              box: Box! @resolver(script: "{'raw': 4}")
              plus(value: Int!): Int! @resolver(script: "value + 1")
            }
            type Box {
              raw: Int!
              computed: Int! @resolver(of: "raw", script: "objectValue.raw * 2")
            }
            type Mutation {
              plus(value: Int!): Int! @resolver(script: "value + 1")
            }
            """.trimIndent(),
        )
        val providers = listOf(AstOperationSelectionProvider, NormalizedOperationSelectionProvider)
        for (provider in providers) {
            val cached = ConcurrentHashMap<String, PreparsedDocumentEntry>()
            val invocations = Collections.synchronizedList(mutableListOf<ResolverInvocationObservation>())
            val observer = object : ResolutionObserver {
                override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { invocations.add(it) }
            }
            val configure: (GraphQLContext.Builder) -> Unit = {
                provider.configureContext(it)
                it.put(ResolutionObserver::class.java, observer)
            }
            val strategy = Runtime2ExecutionStrategy(world.assumptions, world.schemas.graphQLSchema, Dispatchers.Default, operationSelectionProvider = provider)
            val graphql = GraphQL.newGraphQL(Runtime2WiringFactory(world.schema).wire(world.schemas.graphQLSchema))
                .queryExecutionStrategy(strategy)
                .mutationExecutionStrategy(strategy)
                .instrumentation(RequestLifetimeInstrumentation())
                .preparsedDocumentProvider(
                    IncrementalDirectiveValidation { input, compute ->
                        CompletableFuture.completedFuture(cached.computeIfAbsent(input.query) { compute.apply(input) })
                    },
                )
                .build()
            val query = "query(${'$'}value: Int!, ${'$'}show: Boolean!) { a: box { ...Fields } b: box { raw } plus(value: ${'$'}value) } fragment Fields on Box { computed @include(if: ${'$'}show) }"
            val result = graphql.execute(
                ExecutionInput.newExecutionInput().query(query).variables(mapOf("value" to 9, "show" to true))
                    .graphQLContext { configure(it) }.build(),
            )
            assertEquals(emptyList<Any>(), result.errors)
            assertEquals(mapOf("a" to mapOf("computed" to 8), "b" to mapOf("raw" to 4), "plus" to 10), result.getData())
            assertEquals(listOf("Box.computed", "Query.box", "Query.plus"), invocations.map { "${it.field.containingDef.name}.${it.field.name}" }.sorted())
            invocations.clear()
            val rebound = graphql.execute(
                ExecutionInput.newExecutionInput().query(query).variables(mapOf("value" to 10, "show" to false))
                    .graphQLContext { configure(it) }.build(),
            )
            assertEquals(emptyList<Any>(), rebound.errors)
            assertEquals(mapOf("a" to emptyMap<String, Any>(), "b" to mapOf("raw" to 4), "plus" to 11), rebound.getData())
            assertEquals(1, cached.size)
            assertEquals(listOf("Query.box", "Query.plus"), invocations.map { "${it.field.containingDef.name}.${it.field.name}" }.sorted())
            invocations.clear()
            val mutation = graphql.execute(
                ExecutionInput.newExecutionInput().query("mutation { a: plus(value: 1) b: plus(value: 2) }")
                    .graphQLContext { configure(it) }.build(),
            )
            assertEquals(emptyList<Any>(), mutation.errors)
            assertEquals(linkedMapOf("a" to 2, "b" to 3), mutation.getData())
            assertEquals(listOf(1, 2), invocations.map { it.arguments.fieldExpressions()["value"] })
        }
    }

    @Test
    fun `producer demand from a shared forest matches demand from expanded selections`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { box: Box @resolver(script: "{'raw': 4}") }
            type Box {
              raw: Int
              next: Box
              computed: Int @resolver(of: "raw", script: "objectValue.raw * 2")
            }
            """.trimIndent(),
        )
        val ast = Parser.parse("{ a: box { ...Fields } b: box { ...Fields } } fragment Fields on Box { computed next { raw } }")
        val operation = ast.getDefinitionsOfType(OperationDefinition::class.java).single()
        val context = GraphQLContext.newContext().of(NormalizedDocument.ENABLED, true).build()
        val validated = Validator().validateDocumentWithResult(world.schemas.graphQLSchema, ast, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE, context)
        assertEquals(emptyList<Any>(), validated.errors())
        val variables = CoercedVariables.emptyVariables()
        val baseline = AstSelectionParser(world.schemas.graphQLSchema, world.schema, emptyMap()).selectionsFrom(
            operation,
            variables,
            context,
            Locale.ENGLISH,
            ast.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name },
        )
        val source = NormalizedSelectionSource(requireNotNull(validated.normalizedDocument()), world.schemas.graphQLSchema, world.schema, variables, context, Locale.ENGLISH)
        val shared = source.selections(operation)
        assertEquivalent(baseline.successorDemand(world.assumptions, null), shared.successorDemand(world.assumptions, null))
        assertEquals(5, source.decodedFieldCount)
    }

    @Test
    fun `enabled execution rejects preparsed entries that discarded ND`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val strategy = Runtime2ExecutionStrategy(
            world.assumptions,
            world.schemas.graphQLSchema,
            Dispatchers.Default,
            operationSelectionProvider = NormalizedOperationSelectionProvider,
        )
        val graphql = GraphQL.newGraphQL(Runtime2WiringFactory(world.schema).wire(world.schemas.graphQLSchema))
            .queryExecutionStrategy(strategy)
            .instrumentation(RequestLifetimeInstrumentation())
            .preparsedDocumentProvider { input, compute ->
                val validated = compute.apply(input)
                CompletableFuture.completedFuture(PreparsedDocumentEntry(validated.document))
            }.build()
        val error = assertThrows<Exception> {
            graphql.execute(
                ExecutionInput.newExecutionInput().query("{ value }")
                    .graphQLContext(NormalizedOperationSelectionProvider::configureContext).build(),
            )
        }
        assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it.message.orEmpty().contains("retain it in the preparsed cache") })
    }

    @Test
    fun `custom inclusion decisions retain eager occurrence evaluation`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val calls = AtomicInteger()
        val duringPreparation = AtomicInteger()
        val provider = object : Runtime2OperationSelectionProvider {
            override fun selections(
                world: Assumptions,
                sourceSchema: GraphQLSchema,
                context: ExecutionContext
            ): SelectionForest {
                val before = calls.get()
                return NormalizedOperationSelectionProvider.selections(world, sourceSchema, context).also {
                    duringPreparation.set(calls.get() - before)
                }
            }
        }
        val strategy = Runtime2ExecutionStrategy(world.assumptions, world.schemas.graphQLSchema, Dispatchers.Default, operationSelectionProvider = provider)
        val graphql = GraphQL.newGraphQL(Runtime2WiringFactory(world.schema).wire(world.schemas.graphQLSchema))
            .queryExecutionStrategy(strategy)
            .instrumentation(RequestLifetimeInstrumentation())
            .build()
        val result = graphql.execute(
            ExecutionInput.newExecutionInput().query("{ value }").graphQLContext {
                NormalizedOperationSelectionProvider.configureContext(it)
                it.put(
                    ConditionalNodeDecision::class.java,
                    ConditionalNodeDecision {
                        calls.incrementAndGet()
                        true
                    }
                )
            }.build(),
        )
        assertEquals(emptyList<Any>(), result.errors)
        assertEquals(1, duringPreparation.get())
    }

    private class Fixture(sdl: String) {
        val sourceSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl))
        val schemas = ViaductAndGJSchema.fromGraphQLSchema(sourceSchema)
        private val context = GraphQLContext.newContext().of(NormalizedDocument.ENABLED, true).build()

        fun document(
            query: String,
            variables: Map<String, Any?> = emptyMap(),
            operationName: String? = null
        ): Validated {
            val ast = Parser.parse(query)
            val result = Validator().validateDocumentWithResult(sourceSchema, ast, { true }, Locale.ENGLISH, QueryComplexityLimits.NONE, context)
            assertTrue(result.errors().isEmpty(), result.errors().toString())
            val operation = ast.getDefinitionsOfType(OperationDefinition::class.java).single { operationName == null || it.name == operationName }
            @Suppress("UNCHECKED_CAST")
            val coerced = ValuesResolver.coerceVariableValues(sourceSchema, operation.variableDefinitions, RawVariables.of(variables as Map<String, Any>), context, Locale.ENGLISH)
            return Validated(requireNotNull(result.normalizedDocument()), operation, coerced)
        }

        fun source(document: Validated): NormalizedSelectionSource = NormalizedSelectionSource(document.graph, sourceSchema, schemas.loweredSchema, document.variables, context, Locale.ENGLISH)

        fun baseline(document: Validated): SelectionForest =
            AstSelectionParser(sourceSchema, schemas.loweredSchema, emptyMap()).selectionsFrom(
                document.operation,
                document.variables,
                context,
                Locale.ENGLISH,
                document.graph.document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name },
            )
    }

    private data class Validated(val graph: NormalizedDocument, val operation: OperationDefinition, val variables: CoercedVariables)

    private fun SelectionForest.members(): List<Selection> = buildList { this@members.forEach(::add) }

    private fun assertEquivalent(
        expected: SelectionForest,
        actual: SelectionForest
    ) {
        assertEquals(expected.size, actual.size)
        expected.members().zip(actual.members()).forEach { (a, b) ->
            assertEquals(a.key, b.key)
            assertEquals(a.possibleTypes, b.possibleTypes)
            assertEquals(a.inclusionCondition, b.inclusionCondition)
            assertEquivalent(a.subselections, b.subselections)
        }
    }
}
