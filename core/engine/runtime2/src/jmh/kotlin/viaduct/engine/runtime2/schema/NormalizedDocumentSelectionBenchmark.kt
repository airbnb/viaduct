package viaduct.engine.runtime2.schema

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.execution.conditional.ConditionalNodes
import graphql.introspection.Introspection
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.normalized.NormalizedDocument
import graphql.normalized.NormalizedField
import graphql.normalized.NormalizedSelectionScope
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.DocumentValidationResult
import graphql.validation.QueryComplexityLimits
import graphql.validation.Validator
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.toSelectionForest
import viaduct.engine.runtime2.schema.lowering.loweredFieldFromSourceCoordinate
import viaduct.graphql.schema.ViaductSchema

/** Experimental comparison against graphql-java PR 4562, using only its schema-4 full query. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 30)
@Measurement(iterations = 3, time = 60)
@Fork(value = 2, jvmArgsAppend = ["-Xms2g", "-Xmx8g", "-XX:+UseG1GC"])
open class NormalizedDocumentSelectionBenchmark {
    private lateinit var sourceSchema: GraphQLSchema
    private lateinit var loweredSchema: ViaductSchema
    private lateinit var operation: OperationDefinition
    private lateinit var fragmentsByName: Map<String, FragmentDefinition>
    private lateinit var variables: CoercedVariables
    private lateinit var baselineContext: GraphQLContext
    private lateinit var normalizedContext: GraphQLContext
    private lateinit var parser: AstSelectionParser
    private lateinit var normalizedDocument: NormalizedDocument
    private lateinit var document: graphql.language.Document

    @Setup(Level.Trial)
    fun setup() {
        val schemaText = resourceText("large-schema-4.graphqls.gz")
        val queryText = resourceText("large-schema-4-query.graphql.gz")
        sourceSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(schemaText))
        document = Parser.parse(queryText)
        operation = document.getDefinitionsOfType(OperationDefinition::class.java).single()
        fragmentsByName = document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        baselineContext = GraphQLContext.newContext().build()
        normalizedContext = GraphQLContext.newContext().of(NormalizedDocument.ENABLED, true).build()
        variables = ValuesResolver.coerceVariableValues(
            sourceSchema,
            operation.variableDefinitions,
            RawVariables.of(emptyMap<String, Any>()),
            baselineContext,
            Locale.ENGLISH,
        )
        loweredSchema = ViaductAndGJSchema.fromGraphQLSchema(sourceSchema).loweredSchema
        parser = AstSelectionParser(sourceSchema, loweredSchema, emptyMap())
        check(validate(baselineContext).normalizedDocument() == null)
        normalizedDocument = requireNotNull(validate(normalizedContext).normalizedDocument())
        val fromAst = convertAst()
        val fromNormalized = convertNormalized()
        assertEquivalent(fromAst, fromNormalized)
        val shared = NormalizedSelectionSource(normalizedDocument, sourceSchema, loweredSchema, variables, normalizedContext, Locale.ENGLISH)
        val backed = shared.selections(operation)
        check(shared.decodedFieldCount == 0) { "Creating the ND view must not decode fields" }
        assertEquivalent(fromAst, backed)
        check(shared.decodedFieldCount <= normalizedDocument.fields.size)
    }

    @Benchmark
    fun validateBaseline(): DocumentValidationResult = validate(baselineContext)

    @Benchmark
    fun validateNormalized(): DocumentValidationResult = validate(normalizedContext)

    @Benchmark
    fun convertAst(): SelectionForest = parser.selectionsFrom(operation, variables, baselineContext, Locale.ENGLISH, fragmentsByName)

    @Benchmark
    fun convertNormalized(): SelectionForest =
        NormalizedSelectionForestConverter(sourceSchema, loweredSchema, variables, normalizedContext)
            .convert(normalizedDocument)

    fun convertNormalizedBacked(): SelectionForest =
        NormalizedSelectionSource(normalizedDocument, sourceSchema, loweredSchema, variables, normalizedContext, Locale.ENGLISH)
            .selections(operation)

    @Benchmark
    fun validateAndConvertBaseline(): SelectionForest {
        validate(baselineContext)
        return convertAst()
    }

    @Benchmark
    fun validateAndConvertNormalized(): SelectionForest {
        val fresh = requireNotNull(validate(normalizedContext).normalizedDocument())
        return NormalizedSelectionForestConverter(sourceSchema, loweredSchema, variables, normalizedContext)
            .convert(fresh)
    }

    private fun validate(context: GraphQLContext): DocumentValidationResult {
        val result = Validator().validateDocumentWithResult(
            sourceSchema,
            document,
            { true },
            Locale.ENGLISH,
            QueryComplexityLimits.NONE,
            context,
        )
        check(result.errors().isEmpty()) { result.errors().take(3).joinToString { it.message } }
        return result
    }

    private fun resourceText(name: String): String =
        GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/viaduct/engine/runtime2/schema/$name")))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun assertEquivalent(
        expected: SelectionForest,
        actual: SelectionForest,
    ) {
        val left = mutableListOf<Selection>()
        val right = mutableListOf<Selection>()
        expected.forEach(left::add)
        actual.forEach(right::add)
        check(left.size == right.size) { "Forest sizes differ: ${left.size} vs ${right.size}" }
        left.zip(right).forEachIndexed { index, (a, b) ->
            check(a.key == b.key && a.possibleTypes == b.possibleTypes && a.inclusionCondition == b.inclusionCondition) {
                "Selection differs at index $index: ${a.key} vs ${b.key}"
            }
            assertEquivalent(a.subselections, b.subselections)
        }
    }
}

/** Converts a validated shared selection graph directly to construction demand for one request. */
private class NormalizedSelectionForestConverter(
    private val sourceSchema: GraphQLSchema,
    private val schema: ViaductSchema,
    private val variables: CoercedVariables,
    private val graphQLContext: GraphQLContext,
) {
    private val keys = IdentityHashMap<NormalizedField, ObjectEngineResult.Key>()
    private val conditionalNodes = ConditionalNodes()
    private lateinit var normalized: NormalizedDocument

    fun convert(document: NormalizedDocument): SelectionForest {
        normalized = document
        val operation = document.operations.single()
        check(operation.source.operation == OperationDefinition.Operation.QUERY)
        return descend(
            operation.selectionSetId,
            schema.requireQueryTypeDef().possibleObjectTypes,
            InclusionCondition.Always,
        )
    }

    private fun descend(
        selectionSetId: Int,
        possibleTypes: Set<ViaductSchema.Object>,
        condition: InclusionCondition,
    ): SelectionForest {
        if (selectionSetId < 0) return emptyList<Selection>().toSelectionForest()
        val output = mutableListOf<Selection>()
        for (selection in normalized.selectionSets[selectionSetId].selections) {
            when (selection) {
                is NormalizedField -> {
                    val field = selection.source
                    if (field.name in Introspection.INTROSPECTION_SYSTEM_FIELDS) continue
                    val key = keys[selection] ?: decodeKey(selection).also { keys[selection] = it }
                    val children = if (selection.selectionSetId < 0) {
                        emptyList<Selection>().toSelectionForest()
                    } else {
                        val childType = key.field.type.baseTypeDef as ViaductSchema.CompositeTypeDef
                        descend(selection.selectionSetId, childType.possibleObjectTypes, InclusionCondition.Always)
                    }
                    output += Selection.of(
                        key,
                        possibleTypes,
                        children,
                        condition.and(include(field)),
                    )
                }
                is NormalizedSelectionScope -> {
                    val applicable = selection.typeCondition?.let { typeName ->
                        possibleTypes intersect (schema.requireType(typeName) as ViaductSchema.CompositeTypeDef).possibleObjectTypes
                    } ?: possibleTypes
                    val members = descend(selection.selectionSetId, applicable, condition.and(include(selection.source)))
                    members.forEach(output::add)
                }
            }
        }
        return output.toSelectionForest()
    }

    private fun include(source: graphql.language.DirectivesContainer<*>): InclusionCondition =
        if (conditionalNodes.shouldInclude(source, variables.toMap(), sourceSchema, graphQLContext)) {
            InclusionCondition.Always
        } else {
            InclusionCondition.Never
        }

    private fun decodeKey(field: NormalizedField): ObjectEngineResult.Key {
        val source = field.source
        val parent = sourceSchema.getType(field.parentTypeName) as GraphQLCompositeType
        val definition = requireNotNull(Introspection.getFieldDef(sourceSchema, parent, source.name))
        val values = ValuesResolver.getArgumentValues(
            sourceSchema.codeRegistry,
            definition.arguments,
            source.arguments,
            variables,
            graphQLContext,
            Locale.ENGLISH,
        )
        val arguments = definition.arguments.mapNotNull { argument ->
            if (argument.name in values) {
                argument.name to decodeExternalInputValue(argument.type, values[argument.name], schema)
            } else {
                null
            }
        }.toMap()
        val canonical = schema.loweredFieldFromSourceCoordinate(field.parentTypeName, source.name)
        return ObjectEngineResult.Key.of(canonical, Arguments.of(canonical, arguments))
    }
}
