@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.Scalars.GraphQLID
import graphql.execution.CoercedVariables
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.execution.values.InputInterceptor
import graphql.execution.values.legacycoercing.LegacyCoercingInputInterceptor
import graphql.language.Argument as GJArgument
import graphql.language.Field as GJField
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.StringValue as GJStringValue
import graphql.language.TypeName as GJTypeName
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil.unwrapNonNull
import graphql.schema.TypeResolver
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createLocalContext
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.observability.ExecutionObservabilityContext

class ExecutionParametersTest {
    private val viaductSchema = createSchema(
        """
        interface Node {
            id: ID!
        }

        type Query {
            foo(id: ID!): Foo!
            node: Node
        }

        type Foo implements Node {
            id: ID!
            name: String!
            foo: String
            fooSpecific: String
        }
        """.trimIndent(),
        resolvers = emptyMap(),
        typeResolvers = mapOf(
            "Node" to TypeResolver { env ->
                env.schema.getObjectType("Foo")
            }
        )
    )
    private val schema: GraphQLSchema = viaductSchema.schema
    private val fooType: GraphQLObjectType = schema.getObjectType("Foo")
    private val queryType: GraphQLObjectType = schema.queryType
    private val fooFieldDefinition = requireNotNull(queryType.getFieldDefinition("foo"))
    private val emptyVariables = CoercedVariables.of(emptyMap<String, Any?>())
    private val defaultRootValue = mapOf("viewerId" to "root")
    private val emptyAstSelectionSet: GJSelectionSet = GJSelectionSet.newSelectionSet().build()
    private val defaultLocalContext: CompositeLocalContext = createLocalContext(viaductSchema)

    @Test
    fun `forChildPlan derives CurrentQueryResult and uses active query engine result for query plans`() {
        val parentSource = mapOf("viewer" to "parent")
        val rootValue = mapOf("viewer" to "root")
        val parentPlan = queryPlanFor(type = queryType)
        val childPlan = queryPlanFor(
            type = queryType,
            attribution = ExecutionAttribution.fromOperation("RootQuery")
        )
        val parameters = createExecutionParameters(
            source = parentSource,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = parentPlan,
            currentObjectEngineResult = ObjectEngineResultImpl.newForType(fooType),
            rootValue = rootValue
        )

        val target = parameters.targetForChildPlan(childPlan)
        val result = parameters.forChildPlan(childPlan, emptyVariables, target)

        assertSame(ExecutionOrigin.Root, parameters.executionOrigin)
        assertSame(parentPlan, parameters.queryPlan)
        assertSame(childPlan, result.queryPlan)
        assertNotSame(parameters.queryPlan, result.queryPlan)
        assertSame(ChildQueryPlanTarget.CurrentQueryResult, target)
        assertSame(parameters.queryEngineResult, result.currentObjectEngineResult)
        assertSame(parameters.queryEngineResult, result.queryEngineResult)
        assertEquals(rootValue, result.source)
        assertEquals(queryType, result.executionStepInfo.type)
        assertEquals(ResultPath.rootPath(), result.executionStepInfo.path)
        assertEquals(childPlan.attribution, result.attribution)
        val origin = result.executionOrigin as ExecutionOrigin.ChildQueryPlan
        assertSame(parameters, origin.parameters)
        assertSame(target, origin.target)
    }

    @Test
    fun `forChildPlan derives CurrentObjectResult and reuses field context for object plans`() {
        val parentSource = mapOf("viewer" to "parent")
        val childAst = selectionSet("name")
        val parentPlan = queryPlanFor(type = fooType)
        val childPlan = queryPlanFor(
            type = fooType,
            astSelectionSet = childAst,
            attribution = ExecutionAttribution.fromResolver("FooResolver")
        )
        val fooMergedField = mergedField("foo", selectionSet("id"))
        val fooStepInfo = executionStepInfoForField(fooMergedField)
        val idMergedField = mergedField("id")
        val idStepInfo = executionStepInfoForField(idMergedField, fooType, fooStepInfo)
        val parameters = createExecutionParameters(
            source = parentSource,
            executionStepInfo = idStepInfo,
            queryPlan = parentPlan,
            currentObjectEngineResult = ObjectEngineResultImpl.newForType(fooType)
        )

        val target = parameters.targetForChildPlan(childPlan)
        val result = parameters.forChildPlan(childPlan, emptyVariables, target)

        assertSame(ChildQueryPlanTarget.CurrentObjectResult, target)
        assertSame(parentPlan, parameters.queryPlan)
        assertSame(childPlan, result.queryPlan)
        assertNotSame(parameters.queryPlan, result.queryPlan)
        assertSame(parameters.currentObjectEngineResult, result.currentObjectEngineResult)
        assertEquals(parentSource, result.source)
        assertEquals(fooType, result.executionStepInfo.type)
        assertEquals(ResultPath.rootPath().segment("foo"), result.executionStepInfo.path)
        assertEquals(childAst, result.executionStepInfo.field.singleField.selectionSet)
        assertEquals(childPlan.attribution, result.attribution)
        val origin = result.executionOrigin as ExecutionOrigin.ChildQueryPlan
        assertSame(parameters, origin.parameters)
        assertSame(target, origin.target)
    }

    @Test
    fun `ExplicitObjectResult target uses supplied result without replacing query context`() {
        val completionResult = ObjectEngineResultImpl.newForType(fooType)
        val childPlan = queryPlanFor(type = fooType)
        val fooStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id")))
        val parameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = executionStepInfoForField(mergedField("id"), fooType, fooStepInfo),
            queryPlan = childPlan,
            currentObjectEngineResult = ObjectEngineResultImpl.newForType(fooType),
        )

        val target = ChildQueryPlanTarget.ExplicitObjectResult(completionResult)
        val result = parameters.forChildPlan(
            childPlan,
            emptyVariables,
            target,
        )

        assertSame(completionResult, result.currentObjectEngineResult)
        assertSame(parameters.queryEngineResult, result.queryEngineResult)
        assertSame(parameters.rootEngineResult, result.rootEngineResult)
        assertEquals(target, (result.executionOrigin as ExecutionOrigin.ChildQueryPlan).target)
    }

    @Test
    fun `forChildPlan with ResolvedFieldObjectResult target switches to field engine result for object plans`() {
        val parentSource = mapOf("viewer" to "parent")
        val childSelection = selectionSet("name")
        val childPlan = queryPlanFor(
            type = fooType,
            astSelectionSet = childSelection
        )
        val fieldEngineResult = ObjectEngineResultImpl.newForType(fooType)
        val fieldResolutionResult = FieldResolutionResult(
            engineResult = fieldEngineResult,
            errors = emptyList(),
            localContext = CompositeLocalContext.empty,
            extensions = emptyMap(),
            originalSource = mapOf("child" to 1)
        )
        val parameters = createExecutionParameters(
            source = parentSource,
            executionStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id"))),
            queryPlan = childPlan,
            currentObjectEngineResult = ObjectEngineResultImpl.newForType(fooType)
        )

        val target =
            ChildQueryPlanTarget.ResolvedFieldObjectResult(fieldEngineResult, fieldResolutionResult.originalSource)
        val result = parameters.forChildPlan(
            childPlan,
            emptyVariables,
            target,
        )

        assertSame(fieldEngineResult, result.currentObjectEngineResult)
        assertEquals(fieldResolutionResult.originalSource, result.source)
        assertEquals(fooType, result.executionStepInfo.type)
        assertEquals(childSelection, result.executionStepInfo.field.singleField.selectionSet)
        val origin = result.executionOrigin as ExecutionOrigin.ChildQueryPlan
        assertSame(parameters, origin.parameters)
        assertSame(target, origin.target)
    }

    @Test
    fun `forChildPlan wraps selection set for abstract parent type`() {
        val baseParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType)
        )

        val nodeSelectionSet = GJSelectionSet
            .newSelectionSet()
            .selection(
                GJInlineFragment
                    .newInlineFragment()
                    .typeCondition(GJTypeName("Foo"))
                    .selectionSet(selectionSet("foo"))
                    .build()
            )
            .build()
        val nodeMergedField = mergedField("node", nodeSelectionSet)
        val nodeCollectedField = collectedField("node", nodeMergedField, QueryPlan.SelectionSet.empty)
        val nodeFieldParameters = baseParameters.forField(queryType, nodeCollectedField)
        val nodeEngineResult = ObjectEngineResultImpl.newForType(fooType)
        val nodeTraversalParameters = nodeFieldParameters.forObjectTraversal(
            field = nodeCollectedField,
            engineResult = nodeEngineResult,
            localContext = nodeFieldParameters.localContext,
            source = mapOf("id" to "node-1")
        )

        val fooMergedField = mergedField("foo")
        val fooCollectedField = collectedField("foo", fooMergedField)
        val fooFieldParameters = nodeTraversalParameters.forField(fooType, fooCollectedField)

        val childSelection = selectionSet("fooSpecific")
        val childPlan = queryPlanFor(
            type = fooType,
            astSelectionSet = childSelection,
            attribution = ExecutionAttribution.fromResolver("FooInterfaceResolver")
        )

        val result = fooFieldParameters.forChildPlan(
            childPlan,
            emptyVariables,
            ChildQueryPlanTarget.CurrentObjectResult,
        )

        val selectionSet = checkNotNull(result.executionStepInfo.field?.singleField?.selectionSet) {
            "Expected selection set on field to be present"
        }
        val inlineFragment = selectionSet.selections.single() as GJInlineFragment
        assertEquals("Foo", inlineFragment.typeCondition?.name)
        assertEquals(childSelection, inlineFragment.selectionSet)
    }

    @Test
    fun `forChildPlan with ResolvedFieldObjectResult target uses active query engine result for root query plans`() {
        val parentSource = mapOf("viewer" to "parent")
        val childPlan = queryPlanFor(
            type = queryType,
            astSelectionSet = emptyAstSelectionSet,
            attribution = ExecutionAttribution.fromOperation("RootChild")
        )
        val parameters = createExecutionParameters(
            source = parentSource,
            executionStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id"))),
            queryPlan = childPlan
        )
        val fieldResolutionResult = FieldResolutionResult(
            engineResult = null,
            errors = emptyList(),
            localContext = CompositeLocalContext.empty,
            extensions = emptyMap(),
            originalSource = Any()
        )

        // For Query-typed plans, the ResolvedFieldObjectResult target's OER and source are ignored —
        // the engine always uses the active queryEngineResult and the execution root.
        val result = parameters.forChildPlan(
            childPlan,
            emptyVariables,
            ChildQueryPlanTarget.ResolvedFieldObjectResult(
                ObjectEngineResultImpl.newForType(queryType),
                fieldResolutionResult.originalSource,
            ),
        )

        assertSame(parameters.queryEngineResult, result.currentObjectEngineResult)
        assertSame(parameters.queryEngineResult, result.queryEngineResult)
        assertEquals(defaultRootValue, result.source)
        assertEquals(ResultPath.rootPath(), result.executionStepInfo.path)
        assertEquals(queryType, result.executionStepInfo.type)
        assertEquals(childPlan.attribution, result.attribution)
        assertSame(parameters, (result.executionOrigin as ExecutionOrigin.ChildQueryPlan).parameters)
    }

    @Test
    fun `forChildPlan with IsolatedRootResult replaces root and query constants for object plans`() {
        val childPlan = queryPlanFor(
            type = fooType,
            astSelectionSet = selectionSet("name"),
        )
        val isolatedRootResult = ObjectEngineResultImpl.newForType(fooType)
        val isolatedQueryResult = ObjectEngineResultImpl.newForType(queryType)
        val parameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = run {
                val fooStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id")))
                executionStepInfoForField(mergedField("id"), fooType, fooStepInfo)
            },
            queryPlan = childPlan,
            rootEngineResult = ObjectEngineResultImpl.newForType(queryType),
            queryEngineResult = ObjectEngineResultImpl.newForType(queryType),
        )

        val result = parameters.forChildPlan(
            childPlan,
            emptyVariables,
            ChildQueryPlanTarget.IsolatedRootResults(
                rootResult = isolatedRootResult,
                queryResult = isolatedQueryResult,
            ),
        )

        assertSame(isolatedRootResult, result.currentObjectEngineResult)
        assertSame(isolatedRootResult, result.rootEngineResult)
        assertSame(isolatedQueryResult, result.queryEngineResult)
        assertEquals(defaultRootValue, result.source)
        assertSame(parameters, (result.executionOrigin as ExecutionOrigin.ChildQueryPlan).parameters)
    }

    @Test
    fun `forChildPlan with IsolatedRootResult uses isolated query result for root query plans`() {
        val childPlan = queryPlanFor(
            type = queryType,
            astSelectionSet = emptyAstSelectionSet,
        )
        val isolatedRootResult = ObjectEngineResultImpl.newForType(queryType)
        val isolatedQueryResult = ObjectEngineResultImpl.newForType(queryType)
        val parameters = createExecutionParameters(
            source = mapOf("viewer" to "parent"),
            executionStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id"))),
            queryPlan = childPlan,
            rootEngineResult = ObjectEngineResultImpl.newForType(queryType),
            queryEngineResult = ObjectEngineResultImpl.newForType(queryType),
        )

        val result = parameters.forChildPlan(
            childPlan,
            emptyVariables,
            ChildQueryPlanTarget.IsolatedRootResults(
                rootResult = isolatedRootResult,
                queryResult = isolatedQueryResult,
            ),
        )

        assertSame(isolatedQueryResult, result.currentObjectEngineResult)
        assertSame(isolatedRootResult, result.rootEngineResult)
        assertSame(isolatedQueryResult, result.queryEngineResult)
        assertEquals(defaultRootValue, result.source)
        assertEquals(ResultPath.rootPath(), result.executionStepInfo.path)
        assertSame(parameters, (result.executionOrigin as ExecutionOrigin.ChildQueryPlan).parameters)
    }

    @Test
    fun `forChildPlan reuses isolated query engine result for later root query plans`() {
        val childPlan = queryPlanFor(
            type = queryType,
            astSelectionSet = emptyAstSelectionSet,
            attribution = ExecutionAttribution.fromOperation("RootChild")
        )
        val isolatedRootResult = ObjectEngineResultImpl.newForType(queryType)
        val isolatedQueryResult = ObjectEngineResultImpl.newForType(queryType)
        val parameters = createExecutionParameters(
            source = mapOf("viewer" to "parent"),
            executionStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id"))),
            queryPlan = childPlan,
            queryEngineResult = ObjectEngineResultImpl.newForType(queryType),
        )

        val subqueryParameters = parameters.forChildPlan(
            childPlan,
            emptyVariables,
            ChildQueryPlanTarget.IsolatedRootResults(
                rootResult = isolatedRootResult,
                queryResult = isolatedQueryResult,
            ),
        )
        val nestedQueryParameters = subqueryParameters.forChildPlan(
            childPlan,
            emptyVariables,
            subqueryParameters.targetForChildPlan(childPlan),
        )

        assertSame(isolatedQueryResult, nestedQueryParameters.currentObjectEngineResult)
        assertSame(isolatedRootResult, nestedQueryParameters.rootEngineResult)
        assertSame(isolatedQueryResult, nestedQueryParameters.queryEngineResult)
        assertEquals(defaultRootValue, nestedQueryParameters.source)
        assertEquals(ResultPath.rootPath(), nestedQueryParameters.executionStepInfo.path)
        assertEquals(queryType, nestedQueryParameters.executionStepInfo.type)
        assertSame(subqueryParameters, (nestedQueryParameters.executionOrigin as ExecutionOrigin.ChildQueryPlan).parameters)
    }

    @Test
    fun `forChildPlan preserves child plan attribution`() {
        val resolverAttribution = ExecutionAttribution.fromResolver("ChildResolver")
        val childPlan = queryPlanFor(
            type = fooType,
            astSelectionSet = selectionSet("name"),
            attribution = resolverAttribution
        )
        val fooMergedField = mergedField("foo", selectionSet("id"))
        val fooStepInfo = executionStepInfoForField(fooMergedField)
        val idMergedField = mergedField("id")
        val idStepInfo = executionStepInfoForField(idMergedField, fooType, fooStepInfo)
        val parameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = idStepInfo,
            queryPlan = childPlan,
            currentObjectEngineResult = ObjectEngineResultImpl.newForType(fooType)
        )

        val result = parameters.forChildPlan(
            childPlan,
            emptyVariables,
            ChildQueryPlanTarget.CurrentObjectResult,
        )

        // The child plan's query plan should carry the resolver attribution, which
        // flows to FieldExecutionScope.attribution via FieldExecutionHelpers
        assertEquals(resolverAttribution, result.queryPlan.attribution)
        assertEquals(ExecutionAttribution.Type.RESOLVER, result.queryPlan.attribution?.type)
    }

    @Test
    fun `forChildPlan adds explicit query plan index without changing constants`() {
        val rss = createRSS("Query", "foo")
        val indexedPlan = queryPlanFor(type = queryType, requiredSelectionSetId = rss.id)
        val dynamicPlan = queryPlanFor(type = queryType, childPlans = listOf(indexedPlan))
        val parameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id"))),
            queryPlan = queryPlanFor(type = fooType),
        )

        val result = parameters.forChildPlan(
            dynamicPlan,
            emptyVariables,
            ChildQueryPlanTarget.CurrentQueryResult,
        )

        assertNull(parameters.queryPlanIndex.find(rss.id))
        assertSame(indexedPlan, result.queryPlanIndex.find(rss.id))
        assertSame(parameters.constants, result.constants)
    }

    @Test
    fun `forField builds execution step info for collected field`() {
        val baseParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType)
        )
        val argumentValue = "foo-id"
        val mergedField = mergedField("foo", selectionSet("id"), mapOf("id" to argumentValue))
        val collectedField = collectedFooField(mergedField)

        val result = baseParameters.forField(queryType, collectedField)

        assertSame(baseParameters.queryPlan, result.queryPlan)
        assertSame(baseParameters.currentObjectEngineResult, result.currentObjectEngineResult)
        assertSame(collectedField, result.field)
        assertEquals(ResultPath.rootPath().segment("foo"), result.executionStepInfo.path)
        assertSame(fooFieldDefinition, result.executionStepInfo.fieldDefinition)
        assertSame(queryType, result.executionStepInfo.objectType)
        assertSame(mergedField, result.executionStepInfo.field)
        assertEquals(argumentValue, result.executionStepInfo.arguments["id"])
        assertSame(baseParameters, (result.executionOrigin as ExecutionOrigin.Field).parameters)
    }

    @Test
    fun `nearestObjectAncestor returns null from request root`() {
        val rootParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
        )

        assertNull(rootParameters.nearestObjectAncestor())
    }

    @Test
    fun `forObjectTraversal updates state and preserves field execution origin`() {
        val baseParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType)
        )
        val argumentValue = "foo-id"
        val mergedField = mergedField("foo", selectionSet("id"), mapOf("id" to argumentValue))
        val collectedField = collectedFooField(mergedField)
        val fieldParameters = baseParameters.forField(queryType, collectedField)
        val nextEngineResult = ObjectEngineResultImpl.newForType(fooType)
        val updatedLocalContext = fieldParameters.localContext.addOrUpdate(
            ExecutionObservabilityContext(ExecutionAttribution.fromResolver("FooChild"))
        )
        val childSource = mapOf("id" to "foo-1")

        val result = fieldParameters.forObjectTraversal(collectedField, nextEngineResult, updatedLocalContext, childSource)

        assertSame(fieldParameters.queryPlan, result.queryPlan)
        assertSame(fieldParameters.field, result.field)
        assertSame(nextEngineResult, result.currentObjectEngineResult)
        assertSame(updatedLocalContext, result.localContext)
        assertEquals(childSource, result.source)
        assertSame(collectedField.selectionSet, result.selectionSet)
        assertEquals(fieldParameters.executionStepInfo.path, result.executionStepInfo.path)
        assertEquals(nextEngineResult.type, unwrapNonNull(result.executionStepInfo.type))
        assertSame(fieldParameters, (result.executionOrigin as ExecutionOrigin.ObjectTraversal).parameters)
        assertSame(baseParameters, result.nearestObjectAncestor())
    }

    @Test
    fun `forObjectTraversal requires field execution parameters`() {
        val rootParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
        )
        val fooField = collectedFooField(mergedField("foo", selectionSet("id")))

        assertThrows<IllegalStateException> {
            rootParameters.forObjectTraversal(
                fooField,
                ObjectEngineResultImpl.newForType(fooType),
                rootParameters.localContext,
                mapOf("id" to "foo-1"),
            )
        }
    }

    @Test
    fun `nearestObjectAncestor skips field execution scope`() {
        val rootParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
        )
        val fooField = collectedFooField(mergedField("foo", selectionSet("id")))
        val fooFieldParameters = rootParameters.forField(queryType, fooField)
        val fooParameters = fooFieldParameters.forObjectTraversal(
            fooField,
            ObjectEngineResultImpl.newForType(fooType),
            fooFieldParameters.localContext,
            mapOf("id" to "foo-1"),
        )
        val nestedField = collectedField("foo", mergedField("foo"))
        val nestedFieldParameters = fooParameters.forField(fooType, nestedField)

        assertSame(rootParameters, nestedFieldParameters.nearestObjectAncestor())
    }

    @Test
    fun `nearestObjectAncestor skips child QueryPlan scope`() {
        val rootParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
        )
        val fooField = collectedFooField(mergedField("foo", selectionSet("id")))
        val fooFieldParameters = rootParameters.forField(queryType, fooField)
        val fooParameters = fooFieldParameters.forObjectTraversal(
            fooField,
            ObjectEngineResultImpl.newForType(fooType),
            fooFieldParameters.localContext,
            mapOf("id" to "foo-1"),
        )
        val nestedField = collectedField("foo", mergedField("foo"))
        val nestedFieldParameters = fooParameters.forField(fooType, nestedField)
        val childPlanParameters = nestedFieldParameters.forChildPlan(
            queryPlanFor(type = fooType),
            emptyVariables,
            ChildQueryPlanTarget.CurrentObjectResult,
        )
        val fieldInChildPlanParameters = childPlanParameters.forField(fooType, nestedField)

        assertSame(rootParameters, fieldInChildPlanParameters.nearestObjectAncestor())
    }

    @Test
    fun `forParentFieldTraversal reuses nearest object ancestor execution origin`() {
        val baseParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType)
        )
        val collectedField = collectedFooField(mergedField("foo", selectionSet("id")))
        val fieldParameters = baseParameters.forField(queryType, collectedField)
        val childEngineResult = ObjectEngineResultImpl.newForType(fooType)
        val childSource = mapOf("id" to "foo-1")
        val childParameters = fieldParameters.forObjectTraversal(
            collectedField,
            childEngineResult,
            fieldParameters.localContext,
            childSource
        )
        val parentFieldParameters = childParameters.forField(fooType, collectedField)
        val ancestor = requireNotNull(parentFieldParameters.nearestObjectAncestor())

        val result = parentFieldParameters.forParentFieldTraversal(
            collectedField,
            ancestor,
            childParameters.localContext,
        )

        assertSame(ancestor.currentObjectEngineResult, result.currentObjectEngineResult)
        assertSame(ancestor.source, result.source)
        assertEquals(ancestor.executionOrigin, result.executionOrigin)
    }

    @Test
    fun `buildDataFetchingEnvironment updates query engine result in local context`() {
        val rootEngineResult = ObjectEngineResultImpl.newForType(queryType)
        val staleQueryEngineResult = ObjectEngineResultImpl.newForType(queryType)
        val activeQueryEngineResult = ObjectEngineResultImpl.newForType(queryType)
        val currentObjectEngineResult = ObjectEngineResultImpl.newForType(queryType)
        val extantLocalContext = defaultLocalContext.addOrUpdate(
            EngineResultLocalContext(
                rootEngineResult = rootEngineResult,
                currentObjectEngineResult = ObjectEngineResultImpl.newForType(fooType),
                queryEngineResult = staleQueryEngineResult,
                executionStrategyParams = mockk(),
                executionContext = mockk()
            )
        )
        val baseParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
            currentObjectEngineResult = currentObjectEngineResult,
            localContext = extantLocalContext,
            queryEngineResult = activeQueryEngineResult,
            rootEngineResult = rootEngineResult,
        )
        val collectedField = collectedFooField(mergedField("foo", selectionSet("id")))
        val fieldParameters = baseParameters.forField(queryType, collectedField)

        val dfe = FieldExecutionHelpers.buildDataFetchingEnvironment(fieldParameters, collectedField, currentObjectEngineResult)
        val localContext = dfe.getLocalContextForType<EngineResultLocalContext>()

        assertSame(currentObjectEngineResult, localContext?.currentObjectEngineResult)
        assertSame(activeQueryEngineResult, localContext?.queryEngineResult)
        assertSame(rootEngineResult, localContext?.rootEngineResult)
    }

    @Test
    fun `forChildPlan throws when plan type is not an object`() {
        val interfacePlan = queryPlanFor(
            type = GraphQLInterfaceType.newInterface()
                .name("Node")
                .field { it.name("id").type(GraphQLID) }
                .build()
        )
        val parameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = interfacePlan
        )

        assertThrows<IllegalArgumentException> {
            parameters.forChildPlan(
                interfacePlan,
                emptyVariables,
                ChildQueryPlanTarget.CurrentObjectResult,
            )
        }
    }

    @Test
    fun `forChildPlan with ResolvedFieldObjectResult throws when plan type is not an object`() {
        val interfacePlan = queryPlanFor(
            type = GraphQLInterfaceType.newInterface()
                .name("Node")
                .field { it.name("id").type(GraphQLID) }
                .build()
        )
        val parameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = executionStepInfoForField(mergedField("foo", selectionSet("id"))),
            queryPlan = interfacePlan
        )
        val fieldResolutionResult = FieldResolutionResult(
            engineResult = ObjectEngineResultImpl.newForType(fooType),
            errors = emptyList(),
            localContext = CompositeLocalContext.empty,
            extensions = emptyMap(),
            originalSource = Any()
        )

        assertThrows<IllegalArgumentException> {
            parameters.forChildPlan(
                interfacePlan,
                emptyVariables,
                ChildQueryPlanTarget.ResolvedFieldObjectResult(
                    fieldResolutionResult.engineResult as ObjectEngineResultImpl,
                    fieldResolutionResult.originalSource,
                ),
            )
        }
    }

    private fun createExecutionParameters(
        source: Any?,
        executionStepInfo: ExecutionStepInfo,
        queryPlan: QueryPlan,
        currentObjectEngineResult: ObjectEngineResultImpl = ObjectEngineResultImpl.newForType(fooType),
        localContext: CompositeLocalContext = defaultLocalContext,
        rootValue: Any = defaultRootValue,
        queryEngineResult: ObjectEngineResultImpl = ObjectEngineResultImpl.newForType(queryType),
        rootEngineResult: ObjectEngineResultImpl = ObjectEngineResultImpl.newForType(queryType),
        rootExecutionJob: Job = Job(),
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): ExecutionParameters {
        val executionContext = executionContext(rootValue, localContext)
        val constants = ExecutionParameters.Constants(
            executionContext = executionContext,
            rootEngineResult = rootEngineResult,
            supervisorScopeFactory = { CoroutineScope(coroutineContext + rootExecutionJob) },
            rootCoroutineContext = coroutineContext,
        )
        return ExecutionParameters(
            _engineExecutionContext = mockk<EngineExecutionContextImpl>(relaxed = true),
            constants = constants,
            currentObjectEngineResult = currentObjectEngineResult,
            queryEngineResult = queryEngineResult,
            coercedVariables = emptyVariables,
            queryPlan = queryPlan,
            queryPlanIndex = queryPlan.index,
            localContext = localContext,
            source = source,
            executionStepInfo = executionStepInfo,
            selectionSet = queryPlan.selectionSet,
            errorAccumulator = ErrorAccumulator()
        )
    }

    private fun executionContext(
        rootValue: Any?,
        localContext: CompositeLocalContext
    ): ExecutionContext {
        val gqlContext = GraphQLContext.newContext()
            .of(InputInterceptor::class.java, LegacyCoercingInputInterceptor.migratesValues())
            .build()
        val instrumentation = mockk<ViaductModernGJInstrumentation>(relaxed = true)
        val executionContext = mockk<ExecutionContext>(relaxed = true)
        every { executionContext.graphQLSchema } returns schema
        every { executionContext.getRoot<Any>() } returns rootValue
        every { executionContext.getLocalContext<CompositeLocalContext?>() } returns localContext
        every { executionContext.instrumentation } returns instrumentation
        every { executionContext.transform(any()) } answers { executionContext }
        every { executionContext.graphQLContext } returns gqlContext
        every { executionContext.locale } returns Locale.US
        return executionContext
    }

    private fun queryPlanFor(
        type: GraphQLOutputType,
        astSelectionSet: GJSelectionSet = emptyAstSelectionSet,
        attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
        childPlans: List<QueryPlan> = emptyList(),
        requiredSelectionSetId: RequiredSelectionSet.Id? = null,
    ): QueryPlan =
        QueryPlan(
            selectionSet = QueryPlan.SelectionSet.empty,
            fragments = QueryPlan.Fragments.empty,
            variablesResolvers = emptyList(),
            parentType = type,
            childPlanIds = childPlans.map { requireNotNull(it.requiredSelectionSetId) },
            baseIndex = childPlans.fold(QueryPlanIndex.empty()) { index, plan -> plan.index.merge(index) },
            astSelectionSet = astSelectionSet,
            attribution = attribution,
            executionCondition = QueryPlanExecutionCondition.ALWAYS_EXECUTE,
            variableDefinitions = emptyList(),
            requiredSelectionSetId = requiredSelectionSetId,
        )

    private fun executionStepInfoForField(
        field: MergedField,
        fieldContainer: GraphQLOutputType = queryType,
        parent: ExecutionStepInfo? = null
    ): ExecutionStepInfo {
        val containerType = fieldContainer as GraphQLObjectType
        val fieldName = field.singleField.name
        val fieldDefinition = requireNotNull(containerType.getFieldDefinition(fieldName)) {
            "Field $fieldName not found on type ${containerType.name}"
        }
        val path = if (parent != null) {
            parent.path.segment(fieldName)
        } else {
            ResultPath.rootPath().segment(fieldName)
        }

        return ExecutionStepInfo.newExecutionStepInfo()
            .type(fieldDefinition.type)
            .fieldDefinition(fieldDefinition)
            .fieldContainer(containerType)
            .field(field)
            .path(path)
            .parentInfo(parent)
            .build()
    }

    private fun mergedField(
        name: String,
        selectionSet: GJSelectionSet? = null,
        arguments: Map<String, String> = emptyMap()
    ): MergedField {
        val fieldBuilder = GJField.newField(name)
        fieldBuilder.arguments(
            arguments.map {
                GJArgument.newArgument()
                    .name(it.key)
                    .value(GJStringValue(it.value))
                    .build()
            }
        )
        selectionSet?.let { fieldBuilder.selectionSet(it) }
        return MergedField.newMergedField(fieldBuilder.build()).build()
    }

    private fun selectionSet(vararg fields: String): GJSelectionSet {
        val builder = GJSelectionSet.newSelectionSet()
        fields.forEach { builder.selection(GJField.newField(it).build()) }
        return builder.build()
    }

    private fun collectedFooField(
        mergedField: MergedField,
        selectionSet: QueryPlan.SelectionSet = QueryPlan.SelectionSet.empty
    ): QueryPlan.CollectedField =
        QueryPlan.CollectedField(
            responseKey = mergedField.name,
            selectionSet = selectionSet,
            mergedField = mergedField,
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty
        )

    private fun collectedField(
        responseKey: String,
        mergedField: MergedField,
        selectionSet: QueryPlan.SelectionSet? = null
    ): QueryPlan.CollectedField =
        QueryPlan.CollectedField(
            responseKey = responseKey,
            selectionSet = selectionSet,
            mergedField = mergedField,
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty
        )
}
