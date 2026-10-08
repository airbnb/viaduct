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
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil.unwrapNonNull
import graphql.schema.TypeResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.incrementalExecutionEnabled
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createLocalContext
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.observability.ExecutionObservabilityContext
import viaduct.engine.runtime.observability.ResolverOutputContext
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class ExecutionParametersTest {
    private val viaductSchema = createSchema(
        """
        directive @parent on FIELD_DEFINITION

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
            child: Foo
            parent: Foo @parent
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
    private val defaultLocalContext: CompositeLocalContext = createLocalContext(viaductSchema)

    @Test
    fun `withNewDeferMap reuses parameters for the same map`() {
        val usage = DeferUsage(mkDefer("A"), null)
        val group = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
        val parameters = rootCollectionParameters(queryPlanFor(queryType)).copy(deferMap = mapOf(usage to group))

        val result = parameters.withNewDeferMap(parameters.deferMap)

        assertSame(parameters, result)
    }

    @Test
    fun `withNewDeferMap replaces the map without changing the original parameters`() {
        val oldUsage = DeferUsage(mkDefer("old"), null)
        val newUsage = DeferUsage(mkDefer("new"), null)
        val oldMap = mapOf(oldUsage to DeferDeliveryGroup(ResultPath.rootPath(), "old", null))
        val newMap = mapOf(newUsage to DeferDeliveryGroup(ResultPath.rootPath(), "new", null))
        val parameters = rootCollectionParameters(queryPlanFor(queryType)).copy(deferMap = oldMap)

        val result = parameters.withNewDeferMap(newMap)

        assertNotSame(parameters, result)
        assertSame(newMap, result.deferMap)
        assertSame(oldMap, parameters.deferMap)
    }

    @Test
    fun `withNewDeferMap replaces equal maps that have different identities`() {
        val usage = DeferUsage(mkDefer("A"), null)
        val group = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
        val parameters = rootCollectionParameters(queryPlanFor(queryType)).copy(deferMap = mapOf(usage to group))
        val equalMap = parameters.deferMap.toMutableMap()

        val result = parameters.withNewDeferMap(equalMap)

        assertNotSame(parameters, result)
        assertSame(equalMap, result.deferMap)
    }

    @Test
    fun `withNewDeferMap clears an existing map`() {
        val usage = DeferUsage(mkDefer("A"), null)
        val group = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
        val parameters = rootCollectionParameters(queryPlanFor(queryType)).copy(deferMap = mapOf(usage to group))

        val result = parameters.withNewDeferMap(emptyMap())

        assertEquals(emptyMap<DeferUsage, DeferDeliveryGroup>(), result.deferMap)
        assertSame(group, parameters.deferMap.getValue(usage))
    }

    @Test
    fun `withNewDeferMap keeps execution handles isolated`() {
        val usage = DeferUsage(mkDefer("A"), null)
        val group = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
        val parameters = rootCollectionParameters(queryPlanFor(queryType))

        val result = parameters.withNewDeferMap(mapOf(usage to group))

        assertNotSame(parameters.engineExecutionContext, result.engineExecutionContext)
        assertSame(result, result.engineExecutionContext.executionHandle)
        assertSame(parameters, parameters.engineExecutionContext.executionHandle)
    }

    @Test
    fun `normal traversal preserves the defer map`() {
        val usage = DeferUsage(mkDefer("A"), null)
        val group = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
        val root = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo().type(queryType).path(ResultPath.rootPath()).build(),
            queryPlan = queryPlanFor(type = queryType),
        ).copy(deferMap = mapOf(usage to group))
        val field = collectedFooField(mergedField("foo", selectionSet("id")))
        val fieldParameters = root.forField(queryType, field)
        val objectParameters = fieldParameters.forObjectTraversal(
            field,
            ObjectEngineResultImpl.newForType(fooType),
            defaultLocalContext,
            null,
        )

        assertSame(root.deferMap, fieldParameters.deferMap)
        assertSame(root.deferMap, objectParameters.deferMap)
    }

    @Test
    fun `child query plans start with an empty defer map`() {
        val usage = DeferUsage(mkDefer("A"), null)
        val group = DeferDeliveryGroup(ResultPath.rootPath(), "A", null)
        val root = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo().type(queryType).path(ResultPath.rootPath()).build(),
            queryPlan = queryPlanFor(type = queryType),
        ).copy(deferMap = mapOf(usage to group))
        val field = collectedFooField(mergedField("foo", selectionSet("id")))
        val objectParameters = root.forField(queryType, field).forObjectTraversal(
            field,
            ObjectEngineResultImpl.newForType(fooType),
            defaultLocalContext,
            null,
        )

        val child = objectParameters.forChildPlan(queryPlanFor(fooType), emptyVariables, ChildQueryPlanTarget.CurrentObjectResult)

        assertEquals(emptyMap<DeferUsage, DeferDeliveryGroup>(), child.deferMap)
        assertSame(group, objectParameters.deferMap.getValue(usage))
    }

    @Test
    fun `field collection receives the incremental execution flag from the request`() {
        val plan = buildPlan("{ ... @defer(label: \"later\") { foo { id } } }", viaductSchema)
        val disabledParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo().type(queryType).path(ResultPath.rootPath()).build(),
            queryPlan = plan,
        )
        val enabledParameters = disabledParameters.copy(
            _engineExecutionContext = ContextMocks(
                myFullSchema = viaductSchema,
                myFlagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION),
            ).engineExecutionContext,
        )

        assertEquals(false, disabledParameters.engineExecutionContext.incrementalExecutionEnabled)
        assertEquals(true, enabledParameters.engineExecutionContext.incrementalExecutionEnabled)
        assertSame(enabledParameters.engineExecutionContext.fullSchema, QueryPlanFilterCtx(enabledParameters).schema)
        assertEquals(true, QueryPlanFilterCtx(enabledParameters).incrementalExecutionEnabled)
        assertEquals(false, QueryPlanFilterCtx(disabledParameters).incrementalExecutionEnabled)
        val disabledResult = FieldExecutionHelpers.collectFields(queryType, disabledParameters)
        val enabledResult = FieldExecutionHelpers.collectFields(queryType, enabledParameters)
        assertEquals(emptyList<DeferUsage>(), disabledResult.newDeferUsages)
        assertNull(disabledResult.collectedFieldsMap.getValue("foo").occurrences.single().deferUsage)
        assertEquals("later", enabledResult.newDeferUsages.single().defer.label)
        assertSame(enabledResult.newDeferUsages.single(), enabledResult.collectedFieldsMap.getValue("foo").occurrences.single().deferUsage)
        assertNotSame(disabledResult, enabledResult)
        assertSame(enabledResult, FieldExecutionHelpers.collectFields(queryType, enabledParameters.copy()))
        assertSame(disabledResult, FieldExecutionHelpers.collectFields(queryType, disabledParameters.copy()))
    }

    @Test
    fun `object collection preserves parent defers and child plans start a fresh collection`() {
        val plan = buildPlan(
            """
                {
                    foo(id: "foo-id") { ...F }
                    ... @defer(label: "A") {
                        foo(id: "foo-id") {
                            ...F
                            name
                            ... @defer(label: "B") { foo }
                            child { ... @defer(label: "C") { fooSpecific } }
                        }
                    }
                }
                fragment F on Foo { id }
            """.trimIndent(),
            viaductSchema,
        )
        val root = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo().type(queryType).path(ResultPath.rootPath()).build(),
            queryPlan = plan,
            engineExecutionContext = ContextMocks(
                myFullSchema = viaductSchema,
                myFlagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION),
            ).engineExecutionContext,
        )
        val rootFields = FieldExecutionHelpers.collectFields(queryType, root)
        val a = rootFields.newDeferUsages.single()
        val field = rootFields.collectedFieldsMap.getValue("foo")
        val fieldParameters = root.forField(queryType, field)
        val objectParameters = fieldParameters.forObjectTraversal(field, ObjectEngineResultImpl.newForType(fooType), root.localContext, emptyMap<String, Any?>())

        val subfields = FieldExecutionHelpers.collectFields(fooType, objectParameters)

        assertEquals(listOf("id", "name", "foo", "child"), subfields.collectedFieldsMap.keys.toList())
        assertEquals(listOf(null, a), subfields.collectedFieldsMap.getValue("id").occurrences.map { it.deferUsage })
        assertSame(a, subfields.collectedFieldsMap.getValue("name").occurrences.single().deferUsage)
        val b = subfields.newDeferUsages.single()
        assertEquals("B", b.defer.label)
        assertSame(a, b.parent)
        assertSame(b, subfields.collectedFieldsMap.getValue("foo").occurrences.single().deferUsage)
        val childField = subfields.collectedFieldsMap.getValue("child")
        assertSame(a, childField.occurrences.single().deferUsage)

        val executionPlan = BuildExecutionPlan(subfields.collectedFieldsMap)
        assertEquals(listOf("id"), executionPlan.collectedFieldsMap.keys.toList())
        assertEquals(listOf("name", "child"), executionPlan.newCollectedFieldsMaps.getValue(setOf(a)).keys.toList())
        assertEquals(listOf("foo"), executionPlan.newCollectedFieldsMaps.getValue(setOf(b)).keys.toList())

        val childObjectParameters = objectParameters.forField(fooType, childField).forObjectTraversal(
            childField,
            ObjectEngineResultImpl.newForType(fooType),
            objectParameters.localContext,
            emptyMap<String, Any?>(),
        )
        val childSubfields = FieldExecutionHelpers.collectFields(fooType, childObjectParameters)
        val c = childSubfields.newDeferUsages.single()
        assertEquals("C", c.defer.label)
        assertSame(a, c.parent)
        assertSame(c, childSubfields.collectedFieldsMap.getValue("fooSpecific").occurrences.single().deferUsage)

        val childParameters = objectParameters.forChildPlan(queryPlanFor(fooType), emptyVariables, ChildQueryPlanTarget.CurrentObjectResult)
        assertEquals(emptyMap<String, CollectedField>(), FieldExecutionHelpers.collectFields(fooType, childParameters).collectedFieldsMap)

        val disabledParameters = objectParameters.copy(
            _engineExecutionContext = ContextMocks(
                myFullSchema = viaductSchema,
                myFlagManager = MockFlagManager.Disabled,
            ).engineExecutionContext,
        )
        val disabledSubfields = FieldExecutionHelpers.collectFields(fooType, disabledParameters)
        assertEquals(listOf("id", "name", "foo", "child"), disabledSubfields.collectedFieldsMap.keys.toList())
        assertEquals(1, disabledSubfields.collectedFieldsMap.getValue("id").occurrences.size)
        disabledSubfields.collectedFieldsMap.values.forEach { collected ->
            collected.occurrences.forEach { assertNull(it.deferUsage) }
        }
        assertEquals(emptyList<DeferUsage>(), disabledSubfields.newDeferUsages)
        val repeatedDisabled = FieldExecutionHelpers.collectFields(fooType, disabledParameters.copy())
        assertSame(disabledSubfields, repeatedDisabled)
    }

    @Test
    fun `object collection separates inherited defer contexts and reuses cached usages`() {
        val plan = buildPlan(
            """
                {
                    ...F @defer(label: "A")
                    ...F @defer(label: "B")
                }
                fragment F on Query { foo(id: "foo-id") { id ... @defer { name } } }
            """.trimIndent(),
            viaductSchema,
        )
        val root = rootCollectionParameters(plan)
        val rootFields = FieldExecutionHelpers.collectFields(queryType, root)
        val (a, b) = rootFields.newDeferUsages
        val field = rootFields.collectedFieldsMap.getValue("foo")
        val parameters = root.forField(queryType, field).forObjectTraversal(
            field,
            ObjectEngineResultImpl.newForType(fooType),
            root.localContext,
            emptyMap<String, Any?>(),
        )

        val result = FieldExecutionHelpers.collectFields(fooType, parameters)

        val parents = result.collectedFieldsMap.getValue("id").occurrences.map { it.deferUsage }
        assertEquals(2, parents.size)
        assertSame(a, parents.first())
        assertSame(b, parents.last())
        assertEquals(2, result.newDeferUsages.size)
        assertSame(a, result.newDeferUsages.first().parent)
        assertSame(b, result.newDeferUsages.last().parent)
        assertEquals(result.newDeferUsages.first().defer, result.newDeferUsages.last().defer)
        val names = result.collectedFieldsMap.getValue("name").occurrences
        assertSame(result.newDeferUsages.first(), names.first().deferUsage)
        assertSame(result.newDeferUsages.last(), names.last().deferUsage)

        val repeated = FieldExecutionHelpers.collectFields(fooType, parameters.copy())
        assertEquals(result.collectedFieldsMap.keys, repeated.collectedFieldsMap.keys)
        assertSame(result.newDeferUsages.first(), repeated.newDeferUsages.first())
        assertSame(result.newDeferUsages.last(), repeated.newDeferUsages.last())
    }

    @Test
    fun `parent traversal collects RSS selections without inheriting client defers`() {
        val plan = buildPlan(
            "{ ... @defer(label: \"client\") { foo(id: \"foo-id\") { id child { id } } } }",
            viaductSchema,
        )
        val root = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo().type(queryType).path(ResultPath.rootPath()).build(),
            queryPlan = plan,
            engineExecutionContext = ContextMocks(
                myFullSchema = viaductSchema,
                myFlagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION),
            ).engineExecutionContext,
        )
        val fooField = FieldExecutionHelpers.collectFields(queryType, root).collectedFieldsMap.getValue("foo")
        val fooParameters = root.forField(queryType, fooField).forObjectTraversal(
            fooField,
            ObjectEngineResultImpl.newForType(fooType),
            root.localContext,
            emptyMap<String, Any?>(),
        )
        val childField = FieldExecutionHelpers.collectFields(fooType, fooParameters).collectedFieldsMap.getValue("child")
        val childParameters = fooParameters.forField(fooType, childField).forObjectTraversal(
            childField,
            ObjectEngineResultImpl.newForType(fooType),
            fooParameters.localContext,
            emptyMap<String, Any?>(),
        )
        val parentField = collectedFooField(
            mergedField("parent", selectionSet("fooSpecific")),
            queryPlanSelectionSet(fooType, "fooSpecific"),
        )
        val rssPlan = queryPlanFor(fooType, QueryPlan.SelectionSet(fooType, parentField.toQueryPlanFields()))
        val rssParameters = childParameters.forChildPlan(rssPlan, emptyVariables, ChildQueryPlanTarget.CurrentObjectResult)
        val parentFieldParameters = rssParameters.forField(fooType, parentField)
        val ancestor = requireNotNull(parentFieldParameters.nearestObjectAncestor())

        val parentParameters = parentFieldParameters.forParentFieldTraversal(parentField, ancestor, rssParameters.localContext)
        val result = FieldExecutionHelpers.collectFields(fooType, parentParameters)

        assertSame(fooParameters, ancestor)
        assertSame(ancestor.executionOrigin, parentParameters.executionOrigin)
        assertEquals(listOf("fooSpecific"), result.collectedFieldsMap.keys.toList())
        assertNull(result.collectedFieldsMap.getValue("fooSpecific").occurrences.single().deferUsage)
        assertEquals(emptyList<DeferUsage>(), result.newDeferUsages)
    }

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
        val childSelection = queryPlanSelectionSet(fooType, "name")
        val parentPlan = queryPlanFor(type = fooType)
        val childPlan = queryPlanFor(
            type = fooType,
            selectionSet = childSelection,
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
        assertEquals(
            childSelection.toAstSelectionSet().selections,
            result.executionStepInfo.field.singleField.selectionSet.selections,
        )
        assertEquals(childPlan.attribution, result.attribution)
        val origin = result.executionOrigin as ExecutionOrigin.ChildQueryPlan
        assertSame(parameters, origin.parameters)
        assertSame(target, origin.target)
    }

    @Test
    fun `forChildPlan clears the parent plan field`() {
        val rootParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
        )
        val fieldParameters = rootParameters.forField(
            queryType,
            collectedFooField(mergedField("foo", selectionSet("id"))),
        )
        val childPlan = queryPlanFor(type = queryType)

        val result = fieldParameters.forChildPlan(
            childPlan,
            emptyVariables,
            ChildQueryPlanTarget.CurrentQueryResult,
        )

        assertNull(result.field)
        assertSame(fieldParameters, (result.executionOrigin as ExecutionOrigin.ChildQueryPlan).parameters)
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
        val childSelection = queryPlanSelectionSet(fooType, "name")
        val childPlan = queryPlanFor(
            type = fooType,
            selectionSet = childSelection,
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
        assertEquals(
            childSelection.toAstSelectionSet().selections,
            result.executionStepInfo.field.singleField.selectionSet.selections,
        )
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
        val nodeType = schema.getType("Node") as GraphQLCompositeType
        val nodeCollectedField = collectedField(
            "node",
            nodeMergedField,
            QueryPlan.SelectionSet.empty(nodeType),
        )
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

        val childSelection = queryPlanSelectionSet(fooType, "fooSpecific")
        val childPlan = queryPlanFor(
            type = fooType,
            selectionSet = childSelection,
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
        assertEquals(childSelection.toAstSelectionSet().selections, inlineFragment.selectionSet.selections)
    }

    @Test
    fun `forChildPlan with ResolvedFieldObjectResult target uses active query engine result for root query plans`() {
        val parentSource = mapOf("viewer" to "parent")
        val childPlan = queryPlanFor(
            type = queryType,
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
            selectionSet = queryPlanSelectionSet(fooType, "name"),
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
            selectionSet = queryPlanSelectionSet(fooType, "name"),
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
        assertSame(collectedField.mergedField, result.executionStepInfo.field)
        val arguments = result.executionStepInfo.arguments
        assertEquals(argumentValue, arguments["id"])
        assertSame(arguments, result.executionStepInfo.arguments)
        assertSame(baseParameters, (result.executionOrigin as ExecutionOrigin.Field).parameters)
    }

    @Test
    fun `forFieldMaterialization updates selection context and keeps original arguments`() {
        val schemaSDL =
            """
            interface Foo {
              x: String
            }

            extend type Query {
              foo: Foo
            }

            type Bar implements Foo {
              x: String
              y(x: Int!): Baz
            }

            type Baz {
              x: String
              y: String
            }
            """.trimIndent()

        fun parameters(
            variableName: String,
            x: Int,
            selection: String,
        ): ExecutionParameters =
            mkExecutionParameters(
                schemaSDL = schemaSDL,
                coordinate = "Bar" to "y",
                query =
                    """
                    query(${'$'}$variableName: Int! = $x) {
                      foo {
                        ... on Bar {
                          y(x: ${'$'}$variableName) {
                            $selection
                          }
                        }
                      }
                    }
                    """.trimIndent(),
            ) {
                field("Query" to "foo") {
                    valueFromContext {
                        createEngineObjectData("Bar")
                    }
                }
            }

        val originalParameters = parameters("originalX", 1, "x")
        val selectionParameters = parameters("laterX", 2, "y")
        val parentDefer = mkDefer("parent")
        val childDefer = mkDefer("child")
        val usage = DeferUsage(childDefer, DeferUsage(parentDefer, null))
        val originalField = checkNotNull(originalParameters.field).let { field ->
            field.withOccurrences(field.occurrences.map { it.copy(deferUsage = usage) })
        }
        val requestedSelectionSet = checkNotNull(selectionParameters.field?.selectionSet)
        val materializationField =
            FieldExecutionHelpers.withMaterializationSelectionSet(
                originalField = originalField,
                originalParameters = originalParameters,
                selectionSet = requestedSelectionSet,
            )
        val materializationPlan =
            selectionParameters.queryPlan.copy(selectionSet = requestedSelectionSet)

        val result =
            originalParameters.forFieldMaterialization(
                field = materializationField,
                materializationPlan = materializationPlan,
                selectionParameters = selectionParameters,
            )

        val currentSelection =
            result.executionStepInfo.field.singleField.selectionSet.selections.single() as GJField
        val enclosingTypeSelection =
            result.executionStepInfo.parent.field.singleField.selectionSet.selections.single()
                as GJInlineFragment
        val enclosingField =
            enclosingTypeSelection.selectionSet.selections.single() as GJField
        val enclosingChildSelection =
            enclosingField.selectionSet.selections.single() as GJField

        assertSame(usage, materializationField.occurrences.single().deferUsage)
        assertSame(requestedSelectionSet, materializationField.occurrences.single().field.selectionSet)
        assertEquals("y", currentSelection.name)
        assertEquals("Bar", enclosingTypeSelection.typeCondition.name)
        assertEquals("y", enclosingField.name)
        assertEquals("y", enclosingChildSelection.name)
        assertEquals(1, result.executionStepInfo.arguments["x"])
        assertSame(originalParameters.source, result.source)
        assertSame(originalParameters.currentObjectEngineResult, result.currentObjectEngineResult)
        assertEquals(selectionParameters.matBatchDepth + 1, result.matBatchDepth)
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
    fun `forObjectTraversal marks returned object as resolver output`() {
        val dispatcherRegistry = mockk<DispatcherRegistry>(relaxed = true) {
            every { getFieldResolverDispatcher("Query", "foo") } returns
                mockk<FieldResolverDispatcher>()
        }
        val baseParameters = rootParameters(dispatcherRegistry, missingFieldErrorsEnabled = true)
        val fooField = collectedFooField(mergedField("foo", selectionSet("id")))
        val fieldParameters = baseParameters.forField(queryType, fooField)

        val result = fieldParameters.forObjectTraversal(
            fooField,
            ObjectEngineResultImpl.newForType(fooType),
            fieldParameters.localContext,
            mapOf("id" to "foo-1"),
        )

        val outputContext = result.localContext.get<ResolverOutputContext>()
        assertNotNull(outputContext)
        assertEquals(true, outputContext?.missingFieldErrorsEnabled)
    }

    @Test
    fun `forObjectTraversal marks node object as resolver output`() {
        val dispatcherRegistry = mockk<DispatcherRegistry>(relaxed = true) {
            every { getFieldResolverDispatcher("Query", "node") } returns
                mockk<FieldResolverDispatcher>()
            every { getNodeResolverDispatcher("Foo") } returns
                mockk<NodeResolverDispatcher>()
        }
        val baseParameters = rootParameters(dispatcherRegistry, missingFieldErrorsEnabled = true)
        val nodeField = collectedField(
            "node",
            mergedField("node", selectionSet("id")),
            QueryPlan.SelectionSet.empty(schema.getType("Node") as GraphQLCompositeType),
        )
        val fieldParameters = baseParameters.forField(queryType, nodeField)
        val nodeSource = mockk<NodeEngineObjectData> {
            every { type } returns fooType
        }

        val result = fieldParameters.forObjectTraversal(
            nodeField,
            ObjectEngineResultImpl.newForType(fooType),
            fieldParameters.localContext,
            nodeSource,
        )

        assertNotNull(result.localContext.get<ResolverOutputContext>())
    }

    @Test
    fun `forObjectTraversal preserves resolver output marker through nested unowned object`() {
        val dispatcherRegistry = mockk<DispatcherRegistry>(relaxed = true) {
            every { getFieldResolverDispatcher("Query", "foo") } returns
                mockk<FieldResolverDispatcher>()
            every { getFieldResolverDispatcher("Foo", "child") } returns null
        }
        val rootParameters = rootParameters(dispatcherRegistry, missingFieldErrorsEnabled = true)
        val fooField = collectedFooField(mergedField("foo", selectionSet("child")))
        val fooFieldParameters = rootParameters.forField(queryType, fooField)
        val fooParameters = fooFieldParameters.forObjectTraversal(
            fooField,
            ObjectEngineResultImpl.newForType(fooType),
            fooFieldParameters.localContext,
            mapOf("child" to mapOf("id" to "child-1")),
        )
        val resolverOutputContext = fooParameters.localContext.get<ResolverOutputContext>()
        val childField = collectedField(
            "child",
            mergedField("child", selectionSet("id")),
            queryPlanSelectionSet(fooType, "id"),
        )
        val childFieldParameters = fooParameters.forField(fooType, childField)

        val result = childFieldParameters.forObjectTraversal(
            childField,
            ObjectEngineResultImpl.newForType(fooType),
            childFieldParameters.localContext,
            mapOf("id" to "child-1"),
        )

        assertNotNull(resolverOutputContext)
        assertSame(resolverOutputContext, result.localContext.get<ResolverOutputContext>())
    }

    @Test
    fun `forObjectTraversal marks returned object when field errors are disabled`() {
        val dispatcherRegistry = mockk<DispatcherRegistry>(relaxed = true) {
            every { getFieldResolverDispatcher("Query", "foo") } returns
                mockk<FieldResolverDispatcher>()
        }
        val baseParameters = rootParameters(
            dispatcherRegistry,
            missingFieldErrorsEnabled = false,
        )
        val fooField = collectedFooField(mergedField("foo", selectionSet("id")))
        val fieldParameters = baseParameters.forField(queryType, fooField)

        val result = fieldParameters.forObjectTraversal(
            fooField,
            ObjectEngineResultImpl.newForType(fooType),
            fieldParameters.localContext,
            mapOf("id" to "foo-1"),
        )

        val outputContext = result.localContext.get<ResolverOutputContext>()
        assertNotNull(outputContext)
        assertEquals(false, outputContext?.missingFieldErrorsEnabled)
        verify(exactly = 1) { dispatcherRegistry.getFieldResolverDispatcher("Query", "foo") }
        verify(exactly = 0) { dispatcherRegistry.getNodeResolverDispatcher(any()) }
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
    fun `nearestObjectAncestor preserves traversal through materialization child plan`() {
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
        val materializationParameters = fooParameters.forChildPlan(
            queryPlanFor(type = fooType),
            emptyVariables,
            ChildQueryPlanTarget.CurrentObjectResult,
        )

        assertSame(rootParameters, materializationParameters.nearestObjectAncestor())
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
    fun `Query-rooted child plan keeps shadow loader scope in DFE local context`() {
        val productionEngineExecutionContext =
            checkNotNull(defaultLocalContext.get<EngineExecutionContextImpl>())
        val rootParameters = createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
            engineExecutionContext = productionEngineExecutionContext,
        )
        val collectedField = collectedFooField(mergedField("foo", selectionSet("id")))
        val shadowParameters = rootParameters
            .forField(queryType, collectedField)
            .forShadowFieldExecution(EmptyCoroutineContext)
        val queryRssParameters = shadowParameters
            .forChildPlan(
                queryPlanFor(type = queryType),
                emptyVariables,
                ChildQueryPlanTarget.CurrentQueryResult,
            )
            .forField(queryType, collectedField)

        val dfe = FieldExecutionHelpers.buildDataFetchingEnvironment(
            queryRssParameters,
            collectedField,
            queryRssParameters.currentObjectEngineResult,
        )
        val shadowContext = dfe.engineExecutionContext as EngineExecutionContextImpl
        val localContext = checkNotNull(dfe.getLocalContextForType<EngineExecutionContextImpl>())

        assertNotSame(
            productionEngineExecutionContext.fieldDataLoaders,
            shadowContext.fieldDataLoaders,
        )
        assertSame(
            shadowContext.fieldDataLoaders,
            localContext.fieldDataLoaders,
            "DFE context views must use the same shadow field-loader scope",
        )
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

    private fun rootCollectionParameters(
        plan: QueryPlan,
        incrementalExecutionEnabled: Boolean = true,
    ): ExecutionParameters =
        createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo().type(queryType).path(ResultPath.rootPath()).build(),
            queryPlan = plan,
            engineExecutionContext = ContextMocks(
                myFullSchema = viaductSchema,
                myFlagManager =
                    if (incrementalExecutionEnabled) {
                        MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION)
                    } else {
                        MockFlagManager.Disabled
                    },
            ).engineExecutionContext,
        )

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
        engineExecutionContext: EngineExecutionContext = ContextMocks(
            myFullSchema = viaductSchema,
            myFlagManager = MockFlagManager.Disabled,
        ).engineExecutionContext,
    ): ExecutionParameters {
        val executionContext = executionContext(rootValue, localContext)
        val constants = ExecutionParameters.Constants(
            executionContext = executionContext,
            rootEngineResult = rootEngineResult,
            supervisorScopeFactory = { CoroutineScope(coroutineContext + rootExecutionJob) },
            rootCoroutineContext = coroutineContext,
        )
        return ExecutionParameters(
            _engineExecutionContext = engineExecutionContext,
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

    private fun rootParameters(
        dispatcherRegistry: DispatcherRegistry,
        missingFieldErrorsEnabled: Boolean,
    ): ExecutionParameters {
        val engineExecutionContext = ContextMocks(
            myFullSchema = viaductSchema,
            myDispatcherRegistry = dispatcherRegistry,
            myFlagManager =
                if (missingFieldErrorsEnabled) {
                    MockFlagManager.create(
                        FlagManager.Flags.ENABLE_RESOLVER_OUTPUT_MISSING_FIELD_ERRORS
                    )
                } else {
                    MockFlagManager.Disabled
                },
        ).engineExecutionContext
        return createExecutionParameters(
            source = defaultRootValue,
            executionStepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(queryType)
                .path(ResultPath.rootPath())
                .build(),
            queryPlan = queryPlanFor(type = queryType),
            engineExecutionContext = engineExecutionContext,
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
        selectionSet: QueryPlan.SelectionSet =
            QueryPlan.SelectionSet.empty(type as GraphQLCompositeType),
        attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
        childPlans: List<QueryPlan> = emptyList(),
        requiredSelectionSetId: RequiredSelectionSet.Id? = null,
    ): QueryPlan =
        QueryPlan(
            selectionSet = selectionSet,
            fragments = QueryPlan.Fragments.empty,
            variablesResolvers = emptyList(),
            childPlanIds = childPlans.map { requireNotNull(it.requiredSelectionSetId) },
            baseIndex = childPlans.fold(QueryPlanIndex.empty()) { index, plan -> plan.index.merge(index) },
            attribution = attribution,
            executionCondition = QueryPlanExecutionCondition.ALWAYS_EXECUTE,
            variableDefinitions = emptyList(),
            requiredSelectionSetId = requiredSelectionSetId,
        )

    private fun queryPlanSelectionSet(
        type: GraphQLCompositeType,
        vararg fields: String,
    ): QueryPlan.SelectionSet =
        QueryPlan.SelectionSet(
            type,
            fields.map { fieldName ->
                QueryPlan.Field(
                    resultKey = fieldName,
                    constraints = Constraints.Unconstrained,
                    field = GJField.newField(fieldName).build(),
                    selectionSet = null,
                    childPlans = emptyList(),
                    fieldTypeChildPlans = FieldTypeChildPlans.empty,
                )
            },
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
        selectionSet: QueryPlan.SelectionSet = QueryPlan.SelectionSet.empty(fooType)
    ): CollectedField =
        mkCollectedField(
            responseKey = mergedField.name,
            selectionSet = selectionSet,
            mergedField = mergedField,
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty,
            schema = schema,
        )

    private fun collectedField(
        responseKey: String,
        mergedField: MergedField,
        selectionSet: QueryPlan.SelectionSet? = null
    ): CollectedField =
        mkCollectedField(
            responseKey = responseKey,
            selectionSet = selectionSet,
            mergedField = mergedField,
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty,
            schema = schema,
        )
}
