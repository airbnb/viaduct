@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.language.AstPrinter
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.flatMap
import io.mockk.mockk
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import viaduct.arbitrary.common.CheckedArb
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.DeepArbSuite
import viaduct.arbitrary.common.withCheck
import viaduct.arbitrary.graphql.AppliedDirectiveWeight
import viaduct.arbitrary.graphql.BatchingResolverWeight
import viaduct.arbitrary.graphql.FieldSelectionWeight
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.IncludeRequiredResolvers
import viaduct.arbitrary.graphql.InterfaceTypeSize
import viaduct.arbitrary.graphql.ListTypeWeight
import viaduct.arbitrary.graphql.MaxValueDepth
import viaduct.arbitrary.graphql.ObjectTypeSize
import viaduct.arbitrary.graphql.OperationCount
import viaduct.arbitrary.graphql.ResolverConfig
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.UndeclaredFieldResolverWeight
import viaduct.arbitrary.graphql.UndeclaredNodeResolverWeight
import viaduct.arbitrary.graphql.UnionTypeSize
import viaduct.arbitrary.graphql.graphQLDocument
import viaduct.arbitrary.graphql.graphQLExecutionInput
import viaduct.arbitrary.graphql.viaductSchema
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolverType
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.ResolverSelectionProjector
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.graphql.utils.ParsedSelections

/**
 * Checks the runtime ownership intersection over generated schemas and operations.
 *
 * The two projections use independent representations and traversal implementations: the non-MAT
 * AST-backed selection tree and MAT's executable QueryPlan/KeyTree path.
 */
class ResolverOwnedSelectionsArbitrarySchemaTest :
    DeepArbSuite<GeneratedProjectionCase>(
        iterations = 200,
    ) {
    private val schemaConfig = Config.default +
        (SchemaSize to 16) +
        (ObjectTypeSize to 1..5) +
        (InterfaceTypeSize to 1..4) +
        (UnionTypeSize to 1..4) +
        (ListTypeWeight to CompoundingWeight(.4, 3)) +
        (MaxValueDepth to 6) +
        (AppliedDirectiveWeight to CompoundingWeight(.05, 3)) +
        (BatchingResolverWeight to 0.0) +
        (GenInterfaceStubsIfNeeded to true) +
        (IncludeRequiredResolvers to false) +
        (UndeclaredFieldResolverWeight to 0.25) +
        (UndeclaredNodeResolverWeight to 0.25)
    private val selectionConfig = schemaConfig +
        (FieldSelectionWeight to CompoundingWeight(.65, 7)) +
        (OperationCount to 1..1)

    override val comparator: Comparator<GeneratedProjectionCase> =
        compareBy {
            SchemaPrinter().print(it.schema.schema).length +
                AstPrinter.printAst(it.operation).length
        }

    override val checkedArb: CheckedArb<GeneratedProjectionCase> =
        Arb.viaductSchema(schemaConfig)
            .flatMap { schema ->
                Arb.graphQLDocument(schema, selectionConfig)
                    .flatMap { document ->
                        val operation = document
                            .getFirstDefinitionOfType(OperationDefinition::class.java)
                            .orElseThrow()
                        val fragments = document
                            .getDefinitionsOfType(FragmentDefinition::class.java)
                            .associateBy { it.name }
                        Arb.graphQLExecutionInput(schema, document, selectionConfig)
                            .flatMap { input ->
                                arbitrary { random ->
                                    GeneratedProjectionCase(
                                        schema = schema,
                                        document = input.query,
                                        operation = operation,
                                        fragments = fragments,
                                        variables = ValuesResolver.coerceVariableValues(
                                            schema.schema,
                                            operation.variableDefinitions,
                                            RawVariables(input.variables),
                                            GraphQLContext.getDefault(),
                                            Locale.getDefault(),
                                        ).toMap(),
                                        resolverConfig =
                                            ResolverConfig(schema, schemaConfig, random),
                                    )
                                }
                            }
                    }
            }.withCheck(::checkProjection)

    private fun checkProjection(case: GeneratedProjectionCase) {
        val queryPlan = buildPlan(case.document, case.schema)
        val rootType = requireNotNull(
            GraphQLTypeUtil.unwrapAll(queryPlan.parentType) as? GraphQLCompositeType
        )
        val nonMat = EngineSelectionSetFactoryImpl(case.schema).engineSelectionSet(
            ParsedSelections(
                typeName = rootType.name,
                selections = case.operation.selectionSet,
                fragmentMap = case.fragments,
            ),
            case.variables,
        )
        val native = ExecutionSelectionSet.create(
            schema = case.schema,
            queryPlan = queryPlan,
            variables = case.variables,
        )
        val registry = GeneratedDispatcherRegistry(case.resolverConfig)
        val projector = ResolverSelectionProjector(case.schema, registry)

        for (resolverType in listOf(ResolverType.FIELD, ResolverType.NODE)) {
            checkProjection(
                case = case,
                resolverType = resolverType,
                nonMat = nonMat,
                native = native,
                projector = projector,
            )
        }
    }

    private fun checkProjection(
        case: GeneratedProjectionCase,
        resolverType: ResolverType,
        nonMat: EngineSelectionSet,
        native: EngineSelectionSet,
        projector: ResolverSelectionProjector,
    ) {
        val projectedNonMat = projector.project(nonMat, resolverType)
        val projectedNative = projector.project(native, resolverType)
        val expected = projectedNonMat.snapshot()
        val actual = projectedNative.snapshot()
        val description = "${case.description()}\nResolver type: $resolverType"

        assertSnapshotsEqual(expected, actual, description)
        assertNoBoundaries(
            selections = projectedNative,
            case = case,
            resolverType = resolverType,
            description = description,
        )
        assertEquals(
            actual,
            projector.project(projectedNative, resolverType).snapshot(),
            description,
        )
        assertSelectionOrderPreserved(native, projectedNative, description)
        assertFragmentRoundTrips(projectedNative, case, description)
    }

    private fun assertFragmentRoundTrips(
        selections: EngineSelectionSet,
        case: GeneratedProjectionCase,
        description: String,
    ) {
        val fragment = selections.toFragment()
        assertEquals(selections.variables, fragment.variables.asMap(), description)
        if (fragment.document.isEmpty()) return

        val reparsed = EngineSelectionSetFactoryImpl(case.schema).engineSelectionSet(
            ParsedSelections.fromDocument(selections.type, fragment.parsedDocument),
            fragment.variables.asMap(),
        )
        assertSnapshotsEqual(
            selections.snapshot().withCanonicalTypenames(),
            reparsed.snapshot().withCanonicalTypenames(),
            "$description\nSerialized fragment:\n${fragment.document}",
        )
    }

    private fun assertSnapshotsEqual(
        expected: SelectionSnapshot,
        actual: SelectionSnapshot,
        description: String,
        path: String = expected.type,
    ) {
        assertEquals(expected.type, actual.type, "$description\nAt $path")
        assertEquals(
            expected.fields.size,
            actual.fields.size,
            "$description\n" +
                "Field count at $path\n" +
                "Expected: ${expected.fields.map { it.typeCondition to it.resultKey }}\n" +
                "Actual: ${actual.fields.map { it.typeCondition to it.resultKey }}",
        )
        expected.fields.zip(actual.fields).forEachIndexed { index, (expectedField, actualField) ->
            val fieldPath = "$path[$index:${expectedField.resultKey}]"
            assertEquals(
                expectedField.copy(children = null),
                actualField.copy(children = null),
                "$description\nAt $fieldPath",
            )
            val expectedChildren = expectedField.children
            val actualChildren = actualField.children
            assertEquals(
                expectedChildren == null,
                actualChildren == null,
                "$description\nChild presence at $fieldPath",
            )
            if (expectedChildren != null && actualChildren != null) {
                assertSnapshotsEqual(
                    expectedChildren,
                    actualChildren,
                    description,
                    fieldPath,
                )
            }
        }
    }

    private fun assertNoBoundaries(
        selections: EngineSelectionSet,
        case: GeneratedProjectionCase,
        resolverType: ResolverType,
        description: String,
        topLevel: Boolean = true,
    ) {
        val declaredType = requireNotNull(
            case.schema.schema.getType(selections.type) as? GraphQLCompositeType
        )
        for (objectType in case.schema.rels.possibleObjectTypes(declaredType)) {
            val concreteSelections =
                if (declaredType == objectType) {
                    selections
                } else {
                    selections.selectionSetForType(objectType.name)
                }
            if (
                objectType.name in case.resolverConfig.nodeResolvers &&
                (!topLevel || resolverType != ResolverType.NODE)
            ) {
                assertTrue(concreteSelections.isEmpty(), description)
                continue
            }

            val traversable = concreteSelections.traversableSelections()
                .map { it.typeCondition to it.selectionName }
                .toSet()
            for (selection in concreteSelections.selections()) {
                if (selection.fieldName.startsWith("__")) continue
                assertTrue(
                    (objectType.name to selection.fieldName) !in
                        case.resolverConfig.fieldResolvers,
                    description,
                )
                if (
                    selection.typeCondition to selection.selectionName !in traversable
                ) {
                    continue
                }

                val outputType = objectType.getFieldDefinition(selection.fieldName)
                    ?.type
                    ?.let(GraphQLTypeUtil::unwrapAll)
                assertTrue(
                    outputType !is GraphQLObjectType ||
                        outputType.name !in case.resolverConfig.nodeResolvers,
                    description,
                )
                assertNoBoundaries(
                    concreteSelections.selectionSetForSelection(
                        selection.typeCondition,
                        selection.selectionName,
                    ),
                    case,
                    resolverType,
                    description,
                    topLevel = false,
                )
            }
        }
    }

    private fun assertSelectionOrderPreserved(
        source: EngineSelectionSet,
        projected: EngineSelectionSet,
        description: String,
        path: String = source.type,
    ) {
        val sourceType = requireNotNull(
            source.schema.schema.getType(source.type) as? GraphQLCompositeType
        )
        if (sourceType !is GraphQLObjectType) {
            source.schema.rels.possibleObjectTypes(sourceType).forEach { concreteType ->
                assertSelectionOrderPreserved(
                    source.selectionSetForType(concreteType.name),
                    projected.selectionSetForType(concreteType.name),
                    description,
                    "$path<${concreteType.name}>",
                )
            }
            return
        }

        val sourceOrder = source.selections()
            .filterNot { it.fieldName.startsWith("__") }
            .map { it.selectionName }
            .distinct()
        val projectedOrder = projected.selections()
            .filterNot { it.fieldName.startsWith("__") }
            .map { it.selectionName }
            .distinct()
        assertSubsequence(
            expected = sourceOrder,
            actual = projectedOrder,
            description = "$description\nAt $path",
        )

        projected.traversableSelections()
            .distinctBy { it.typeCondition to it.selectionName }
            .forEach { selection ->
                val childPath = "$path.${selection.selectionName}"
                assertSelectionOrderPreserved(
                    source.selectionSetForSelection(
                        selection.typeCondition,
                        selection.selectionName,
                    ),
                    projected.selectionSetForSelection(
                        selection.typeCondition,
                        selection.selectionName,
                    ),
                    description,
                    childPath,
                )
            }
    }

    private fun EngineSelectionSet.snapshot(): SelectionSnapshot {
        val concreteType = schema.schema.getType(type) as? GraphQLObjectType
        val semanticSelections = selections().map {
            SemanticSelection(
                selection = it,
                typeCondition = concreteType?.name ?: it.typeCondition,
            )
        }
        val traversable = traversableSelections()
            .map { (concreteType?.name ?: it.typeCondition) to it.selectionName }
            .toSet()
        val uniqueSelections = semanticSelections
            .distinctBy { it.typeCondition to it.selection.selectionName }
        val typeConditionsWithData = uniqueSelections
            .filterNot { it.selection.fieldName.startsWith("__") }
            .mapTo(mutableSetOf()) { it.typeCondition }
        val normalizedSelections =
            uniqueSelections
                .filter {
                    !it.selection.fieldName.startsWith("__") ||
                        it.typeCondition !in typeConditionsWithData
                }
                .sortedWith(
                    compareBy(
                        { it.typeCondition },
                        { it.selection.selectionName },
                        { it.selection.fieldName },
                    )
                )
        return SelectionSnapshot(
            type = type,
            fields =
                normalizedSelections.map { semanticSelection ->
                    val selection = semanticSelection.selection
                    val lookupType = concreteType?.name ?: selection.typeCondition
                    SelectionFieldSnapshot(
                        typeCondition = semanticSelection.typeCondition,
                        fieldName = selection.fieldName,
                        resultKey = selection.selectionName,
                        arguments =
                            argumentsOfSelection(
                                lookupType,
                                selection.selectionName,
                            ),
                        children =
                            if (
                                semanticSelection.typeCondition to selection.selectionName in
                                traversable
                            ) {
                                selectionSetForSelection(
                                    lookupType,
                                    selection.selectionName,
                                ).snapshot()
                            } else {
                                null
                            },
                    )
                },
        )
    }

    private fun <T> assertSubsequence(
        expected: List<T>,
        actual: List<T>,
        description: String,
    ) {
        var expectedIndex = 0
        for (resultKey in actual) {
            while (
                expectedIndex < expected.size &&
                expected[expectedIndex] != resultKey
            ) {
                expectedIndex += 1
            }
            assertTrue(
                expectedIndex < expected.size,
                "$description\nExpected order: $expected\nActual order: $actual",
            )
            expectedIndex += 1
        }
    }
}

private data class SemanticSelection(
    val selection: EngineSelection,
    val typeCondition: String,
)

data class GeneratedProjectionCase(
    val schema: ViaductSchema,
    val document: String,
    val operation: OperationDefinition,
    val fragments: Map<String, FragmentDefinition>,
    val variables: Map<String, Any?>,
    val resolverConfig: ResolverConfig,
) {
    fun description(): String =
        """
        ${SchemaPrinter().print(schema.schema)}

        Generated operation:
        $document

        Field boundaries: ${resolverConfig.fieldResolvers}
        Node boundaries: ${resolverConfig.nodeResolvers}
        """.trimIndent()
}

private data class SelectionSnapshot(
    val type: String,
    val fields: List<SelectionFieldSnapshot>,
) {
    fun withCanonicalTypenames(): SelectionSnapshot =
        copy(
            fields = fields
                .map {
                    it.copy(
                        typeCondition =
                            if (it.fieldName == "__typename") type else it.typeCondition,
                        children = it.children?.withCanonicalTypenames(),
                    )
                }
                .distinctBy { it.typeCondition to it.resultKey },
        )
}

private data class SelectionFieldSnapshot(
    val typeCondition: String,
    val fieldName: String,
    val resultKey: String,
    val arguments: Map<String, Any?>?,
    val children: SelectionSnapshot?,
)

private class GeneratedDispatcherRegistry(
    resolverConfig: ResolverConfig,
) : DispatcherRegistry by DispatcherRegistry.Empty {
    private val fieldBoundaries: Set<Coordinate> = resolverConfig.fieldResolvers
    private val nodeBoundaries: Set<String> = resolverConfig.nodeResolvers
    private val fieldDispatcher = mockk<FieldResolverDispatcher>()
    private val nodeDispatcher = mockk<NodeResolverDispatcher>()

    override fun getFieldResolverDispatcher(
        typeName: String,
        fieldName: String,
    ): FieldResolverDispatcher? = fieldDispatcher.takeIf { typeName to fieldName in fieldBoundaries }

    override fun getNodeResolverDispatcher(typeName: String): NodeResolverDispatcher? = nodeDispatcher.takeIf { typeName in nodeBoundaries }
}
