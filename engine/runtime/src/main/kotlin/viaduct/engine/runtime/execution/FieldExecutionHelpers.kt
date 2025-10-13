package viaduct.engine.runtime.execution

import graphql.collect.ImmutableMapWithNullValues
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.ExecutionStepInfoFactory
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.NormalizedVariables
import graphql.execution.ValuesResolver
import graphql.execution.directives.QueryDirectivesImpl
import graphql.language.Argument
import graphql.normalized.ExecutableNormalizedField
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.DataFetchingFieldSelectionSetImpl
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.util.FpKit
import java.util.function.Supplier
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.gj
import viaduct.engine.api.observability.ExecutionObservabilityContext
import viaduct.engine.runtime.CheckerProxyEngineObjectData
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.findLocalContextForType

object FieldExecutionHelpers {
    val executionStepInfoFactory = ExecutionStepInfoFactory()

    fun coordinateOfField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField
    ): FieldCoordinates {
        val objectType = parameters.executionStepInfo.objectType
        val fieldName = field.mergedField.name
        return (objectType.name to fieldName).gj
    }

    /**
     * Builds the key for the [ObjectEngineResultImpl] for a given field.
     *
     * @param field The field for which to build the key.
     * @return The constructed key.
     */
    fun buildOERKeyForField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField
    ): ObjectEngineResult.Key =
        buildOERKeyForField(
            parameters,
            parameters.executionStepInfo.fieldDefinition,
            field
        )

    fun buildOERKeyForField(
        parameters: ExecutionParameters,
        def: GraphQLFieldDefinition,
        field: QueryPlan.CollectedField
    ): ObjectEngineResult.Key {
        val schemaArguments = def.arguments
        val keyArguments = if (schemaArguments.isNotEmpty()) {
            getArgumentValues(
                parameters,
                schemaArguments,
                field.mergedField.arguments
            ).get()
        } else {
            emptyMap()
        }
        return ObjectEngineResult.Key(field.fieldName, field.alias, keyArguments)
    }

    /**
     * Builds a DataFetchingEnvironment for the given field execution.
     *
     * IMPORTANT: This creates a context-sensitive environment where fragments and variables
     * are set based on the current execution depth:
     * - During root operation execution: uses operation's fragments/variables from client query
     * - During child plan execution (RSS/variables resolver): uses child plan's fragments/variables
     *
     * This ensures code always has the correct execution context, whether resolving the root query
     * or executing a required selection set.
     */
    fun buildDataFetchingEnvironment(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        parentOER: ObjectEngineResultImpl,
    ): DataFetchingEnvironment {
        val mergedField = checkNotNull(field.mergedField) {
            "FieldExecutionHelpers.buildDataFetchingEnvironment requires a merged field"
        }
        val fieldDef = parameters.executionStepInfo.fieldDefinition
        val execStepInfo = { parameters.executionStepInfo }
        val argumentValuesSupplier = { execStepInfo().arguments }
        val normalizedFieldSupplier = getNormalizedField(parameters.executionContext, parameters.gjParameters, execStepInfo)
        val normalizedVariableValuesSupplier: Supplier<NormalizedVariables> = Supplier {
            // ViaductExecutionStrategy does not use NormalizedVariables, though the GJ interface requires them.
            NormalizedVariables.emptyVariables()
        }
        val fieldCollector = DataFetchingFieldSelectionSetImpl.newCollector(
            parameters.graphQLSchema,
            fieldDef.type,
            normalizedFieldSupplier,
        )
        val queryDirectives = QueryDirectivesImpl(
            mergedField,
            parameters.graphQLSchema,
            parameters.coercedVariables,
            normalizedVariableValuesSupplier,
            parameters.executionContext.graphQLContext,
            parameters.executionContext.locale
        )
        val fieldResolverMetadata = field.collectedFieldMetadata?.resolverCoordinate?.let {
            parameters.constants.fieldResolverDispatcherRegistry.getFieldResolverDispatcher(it.first, it.second)?.resolverMetadata
        }
        val localContext = parameters.localContext.let { ctx ->
            // update the context with either a new EngineResultLocalContext or update the existing one
            ctx.get<EngineResultLocalContext>().let { extant ->
                ctx.addOrUpdate(
                    // if the context is already set, just update the parentOER
                    extant?.copy(
                        parentEngineResult = parentOER,
                    ) ?: EngineResultLocalContext(
                        // otherwise create it
                        parentEngineResult = parentOER,
                        queryEngineResult = parameters.queryEngineResult,
                        rootEngineResult = parameters.rootEngineResult,
                        executionStrategyParams = parameters.gjParameters,
                        executionContext = parameters.executionContext,
                    ),
                    ExecutionObservabilityContext(
                        resolverMetadata = fieldResolverMetadata
                    )
                )
            }
        }

        val dfe = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(parameters.executionContext)
            .source(parameters.source)
            .localContext(localContext)
            .arguments(argumentValuesSupplier)
            .fieldDefinition(fieldDef)
            .mergedField(mergedField)
            .fieldType(fieldDef.type)
            .executionStepInfo(execStepInfo)
            .parentType(parentOER.graphQLObjectType)
            .selectionSet(fieldCollector)
            .queryDirectives(queryDirectives)
            .build()

        // Get the EngineExecutionContext from local context and update it with
        // context-sensitive field scope (fragments/variables)
        val engineExecCtx = parameters.executionContext.findLocalContextForType<EngineExecutionContextImpl>()
        val fieldScope = EngineExecutionContextImpl.FieldExecutionScopeImpl(
            fragments = parameters.queryPlan.fragments.map.mapValues { it.value.gjDef },
            variables = parameters.coercedVariables.toMap()
        )
        val updatedEngineExecCtx = engineExecCtx.copy(fieldScope = fieldScope)

        return ViaductDataFetchingEnvironmentImpl(dfe, updatedEngineExecCtx)
    }

    fun createExecutionStepInfo(
        parameters: ExecutionStrategyParameters,
        fieldDefinition: GraphQLFieldDefinition,
        fieldContainer: GraphQLObjectType?,
        argumentValues: Map<String, Any?> = emptyMap()
    ): ExecutionStepInfo {
        val field = parameters.field
        val parentStepInfo = parameters.executionStepInfo
        val fieldType = fieldDefinition.type

        return ExecutionStepInfo.newExecutionStepInfo()
            .type(fieldType)
            .fieldDefinition(fieldDefinition)
            .fieldContainer(fieldContainer)
            .field(field)
            .path(parameters.path)
            .parentInfo(parentStepInfo)
            .arguments { ImmutableMapWithNullValues.copyOf(argumentValues) }
            .build()
    }

    internal fun getArgumentValues(
        parameters: ExecutionParameters,
        argDefs: List<GraphQLArgument>,
        args: List<Argument>
    ): Supplier<ImmutableMapWithNullValues<String, Any>> {
        val codeRegistry = parameters.graphQLSchema.codeRegistry
        val argValuesSupplier = Supplier {
            val resolvedValues = ValuesResolver.getArgumentValues(
                codeRegistry,
                argDefs,
                args,
                parameters.coercedVariables,
                parameters.executionContext.graphQLContext,
                parameters.executionContext.locale
            )
            ImmutableMapWithNullValues.copyOf(resolvedValues)
        }
        return FpKit.intraThreadMemoize(argValuesSupplier)
    }

    private fun getNormalizedField(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters,
        executionStepInfo: Supplier<ExecutionStepInfo>
    ): Supplier<ExecutableNormalizedField> {
        val normalizedQuery = executionContext.normalizedQueryTree
        return Supplier {
            normalizedQuery.get().getNormalizedField(
                parameters.field,
                executionStepInfo.get().objectType,
                executionStepInfo.get().path
            )
        }
    }

    /**
     * Run [CollectFields] for the given state
     * @param objectType the current concrete object
     * @param parameters the ExecutionParameters that contains the selection set and
     * variables to be collected
     */
    fun collectFields(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters
    ): QueryPlan.SelectionSet =
        CollectFields.shallowStrictCollect(
            parameters.selectionSet,
            parameters.coercedVariables,
            objectType,
            parameters.queryPlan.fragments
        )

    /**
     * Recursively resolve all values in the provided [variablesResolvers].
     * If any resolver in [variablesResolvers] depends on engine data, then this will return
     * after the dependee data have resolved.
     */
    suspend fun resolveVariables(
        variablesResolvers: List<VariablesResolver>,
        arguments: Map<String, Any?>,
        currentEngineData: ObjectEngineResult,
        queryEngineData: ObjectEngineResult,
        engineExecutionContext: EngineExecutionContext
    ): Map<String, Any?> =
        variablesResolvers.fold(emptyMap()) { acc, vr ->
            val variablesData: EngineObjectData = vr.requiredSelectionSet?.let { vrss ->
                // VariablesResolvers may have required selection sets which have their own variables resolvers.
                // Recursively resolve them
                val innerVariables = resolveVariables(vrss.variablesResolvers, arguments, currentEngineData, queryEngineData, engineExecutionContext)
                val vss = engineExecutionContext.rawSelectionSetFactory.rawSelectionSet(
                    vrss.selections,
                    variables = innerVariables
                )

                val engineResult = if (vrss.selections.typeName == engineExecutionContext.fullSchema.schema.queryType.name) {
                    queryEngineData
                } else {
                    assert(currentEngineData.graphQLObjectType.name == vrss.selections.typeName) {
                        "Expected current engine data type to match variable resolver selection set type `${vrss.selections.typeName}`, but instead found `${currentEngineData.graphQLObjectType.name}`"
                    }
                    currentEngineData
                }
                if (vrss.forChecker) {
                    CheckerProxyEngineObjectData(engineResult, "missing from variable RSS", vss)
                } else {
                    ProxyEngineObjectData(engineResult, "missing from variable RSS", vss)
                }
            } ?: ProxyEngineObjectData(currentEngineData, "missing from variable RSS", null)

            val resolved = vr.resolve(VariablesResolver.ResolveCtx(variablesData, arguments, engineExecutionContext))
            acc + resolved
        }
}
