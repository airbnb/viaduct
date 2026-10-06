package viaduct.engine.runtime2.bootstrap

import graphql.GraphQLContext
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import java.util.Locale
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldSelectivityProvider
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.DispatcherExecutionContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.ResolverVariableDefinitions
import viaduct.engine.runtime2.execution.QPlanEngineExecutionContext
import viaduct.engine.runtime2.execution.toEngineSelectionSet
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.inputType
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.SelectiveFieldResolverFunction
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDeclaration
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.nodeResolverOf
import viaduct.engine.runtime2.model.registry.selectionAwareFieldResolverOf
import viaduct.engine.runtime2.model.registry.selectiveFieldResolverOf
import viaduct.engine.runtime2.model.registry.selectiveNodeResolverOf
import viaduct.engine.runtime2.model.requireArg
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.resolution.currentVariablesProviderResolutionContext
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.fragmentFromDocument
import viaduct.graphql.schema.ViaductSchema as QPlanSchema

class ExecutorRegistryInputs(
    val fieldResolvers: Map<QPlanSchema.Field, FieldResolverDefinition>,
    val nodeResolvers: Map<QPlanSchema.Object, NodeResolverFunction>,
    val variableProviders: Map<Arguments.Variable, VariableDeclaration>,
    val fieldCheckers: Map<QPlanSchema.ObjectField, FieldCheckerResolver> = emptyMap(),
    val typeCheckers: Map<QPlanSchema.Object, TypeCheckerResolver> = emptyMap(),
)

/** Returned resolver functions retain [context]; do not share them across independent request contexts. */
fun executorRegistryInputs(
    fullSchema: EngineSchema,
    schemas: ViaductAndGJSchema,
    fieldExecutors: Iterable<Pair<Coordinate, FieldResolverExecutor>>,
    nodeExecutors: Iterable<Pair<String, NodeResolverExecutor>>,
    context: EngineExecutionContext,
    fieldSelectivityProvider: FieldSelectivityProvider = FieldSelectivityProvider.Never,
    includeDefaultQueryNodeResolvers: Boolean = true,
): ExecutorRegistryInputs =
    executorRegistryInputs(
        fullSchema = fullSchema,
        schemas = schemas,
        fieldExecutors = fieldExecutors,
        nodeExecutors = nodeExecutors,
        compilationContext = context,
        contextForInvocation = { context },
        fieldSelectivityProvider = fieldSelectivityProvider,
        includeDefaultQueryNodeResolvers = includeDefaultQueryNodeResolvers,
    )

private fun executorRegistryInputs(
    fullSchema: EngineSchema,
    schemas: ViaductAndGJSchema,
    fieldExecutors: Iterable<Pair<Coordinate, FieldResolverExecutor>>,
    nodeExecutors: Iterable<Pair<String, NodeResolverExecutor>>,
    compilationContext: EngineExecutionContext?,
    contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
    fieldSelectivityProvider: FieldSelectivityProvider,
    includeDefaultQueryNodeResolvers: Boolean,
): ExecutorRegistryInputs {
    val fieldResolverExecutors = fieldExecutors.toList()
    val nodeResolverExecutors = nodeExecutors.toList()
    validateExecutorRegistrations(fieldResolverExecutors, nodeResolverExecutors)
    val schema = schemas.loweredSchema
    val sourceSchema = SourceSchemaAdapter(schema)
    val qPlanContextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext =
        { resolutionContext ->
            QPlanEngineExecutionContext(
                contextForInvocation(resolutionContext),
                schemas,
                resolutionContext,
            )
        }
    val variableProviders = linkedMapOf<Arguments.Variable, VariableDeclaration>()
    val supplied =
        fieldResolverExecutors.associate { (coordinate, executor) ->
            val field =
                sourceSchema.field(coordinate.first, coordinate.second)
                    as? QPlanSchema.ObjectField
                    ?: throw IllegalArgumentException(
                        "Field executor ${coordinate.render()} does not map to a concrete object field",
                    )
            val sourceField =
                requireNotNull(fullSchema.schema.getObjectType(coordinate.first))
                    .getFieldDefinition(coordinate.second)
            val objectFragment = executor.objectFragment(schemas, field)
            val queryFragment = executor.queryFragment(schemas, field)
            val variables =
                executor.compileVariableDeclarations(
                    schema = schemas,
                    field = field,
                    objectFragment = objectFragment,
                    queryFragment = queryFragment,
                    context = compilationContext,
                    contextForInvocation =
                        qPlanContextForInvocation.takeIf { compilationContext == null },
                )
            variables.declarations
                .forEach { (variable, declaration) ->
                    require(variableProviders.put(variable, declaration) == null) {
                        "Duplicate variable provider \$${variable.variableName} for ${coordinate.render()}"
                    }
                }

            suspend fun invokeExecutor(
                input: EngineObjectData.Sync,
                queryValue: EngineObjectData.Sync,
                arguments: Arguments.Resolved,
                selections: EngineSelectionSet?,
                executorContext: EngineExecutionContext,
            ): ResolverOutputData? {
                val selector =
                    FieldResolverExecutor.Selector(
                        arguments = arguments.fieldValues,
                        selections = selections,
                        syncObjectValueGetter = { input },
                        syncQueryValueGetter = { queryValue },
                    )
                val output =
                    executor.batchResolve(listOf(selector), executorContext)[selector]
                        ?: Result.failure(
                            IllegalStateException(
                                "Field executor ${coordinate.render()} omitted its selector",
                            ),
                        )
                return output.fold(
                    onSuccess = { normalizeSourceOutput(sourceField.type, it, sourceSchema) },
                    onFailure = { EngineErrorData.of(it) },
                )
            }
            val isSelective =
                executor.isSelective || fieldSelectivityProvider.isSelective(coordinate)
            val resolverFunction: SelectiveFieldResolverFunction =
                { input, queryValue, arguments, selections, resolutionContext ->
                    val selectionSet =
                        (field.type.baseTypeDef as? QPlanSchema.CompositeTypeDef)?.let {
                                type ->
                            type.takeIf { fullSchema.schema.getType(it.name) != null }
                                ?.let { selections.toEngineSelectionSet(it, fullSchema, sourceSchema) }
                        }
                    invokeExecutor(
                        input,
                        queryValue,
                        arguments,
                        selectionSet,
                        qPlanContextForInvocation(resolutionContext),
                    )
                }
            val resolver =
                if (isSelective) {
                    selectiveFieldResolverOf(objectFragment, queryFragment, resolverFunction)
                } else {
                    selectionAwareFieldResolverOf(objectFragment, queryFragment, resolverFunction)
                }
            val resolverWithVariablesProvider =
                variables.provider?.let { provider ->
                    resolver.withVariablesProvider(variables.providerNames, provider)
                } ?: resolver
            field to resolverWithVariablesProvider
        }

    require(supplied.size == fieldResolverExecutors.size) {
        "Field executor coordinates must map to distinct canonical fields"
    }
    return ExecutorRegistryInputs(
        fieldResolvers =
            supplied +
                namespaceFieldResolvers(fullSchema, schema, sourceSchema, supplied.keys) +
                if (includeDefaultQueryNodeResolvers) {
                    builtInNodeFieldResolvers(
                        fullSchema,
                        schema,
                        requireNotNull(compilationContext),
                        supplied.keys,
                    )
                } else {
                    emptyMap()
                },
        variableProviders = variableProviders,
        nodeResolvers =
            qplanNodeResolvers(
                fullSchema,
                schemas,
                fieldResolverExecutors,
                nodeResolverExecutors,
                contextForInvocation,
            ),
    )
}

/**
 * Adapts production dispatchers into runtime2 resolver functions.
 *
 * The adapter discovers registrations through schema-coordinate lookups and keeps dispatcher
 * invocation as the physical resolver boundary. Checker required-selection sets are lowered
 * to runtime2's paired object/Query inputs while checker execution remains dispatcher-backed.
 */
fun dispatcherRegistryInputs(
    fullSchema: EngineSchema,
    schemas: ViaductAndGJSchema,
    dispatcherRegistry: DispatcherRegistry,
    fieldSelectivityProvider: FieldSelectivityProvider = FieldSelectivityProvider.Never,
): ExecutorRegistryInputs {
    val objectTypes =
        fullSchema.schema.allTypesAsList
            .filterIsInstance<GraphQLObjectType>()
            .filterNot { it.name.startsWith("__") }
    val sourceSchema = SourceSchemaAdapter(schemas.loweredSchema)
    val fieldExecutors =
        objectTypes.flatMap { type ->
            type.fieldDefinitions.mapNotNull { field ->
                dispatcherRegistry
                    .getFieldResolverDispatcher(type.name, field.name)
                    ?.let { dispatcher ->
                        val coordinate = type.name to field.name
                        coordinate to DispatcherFieldExecutor(coordinate, dispatcher)
                    }
            }
        }
    val nodeExecutors =
        objectTypes.mapNotNull { type ->
            dispatcherRegistry
                .getNodeResolverDispatcher(type.name)
                ?.let { dispatcher -> type.name to DispatcherNodeExecutor(type.name, dispatcher) }
        }
    val contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext =
        { resolutionContext ->
            resolutionContext.engineExecutionContext as? DispatcherExecutionContext
                ?: error("Runtime2 dispatcher invocation requires a DispatcherExecutionContext")
        }
    val adapted = executorRegistryInputs(
        fullSchema = fullSchema,
        schemas = schemas,
        fieldExecutors = fieldExecutors,
        nodeExecutors = nodeExecutors,
        compilationContext = null,
        contextForInvocation = contextForInvocation,
        fieldSelectivityProvider = fieldSelectivityProvider,
        includeDefaultQueryNodeResolvers = false,
    )
    val queryType = schemas.loweredSchema.requireQueryTypeDef()
    val fieldCheckers =
        objectTypes.flatMap { sourceType ->
            sourceType.fieldDefinitions.mapNotNull { sourceField ->
                dispatcherRegistry
                    .getFieldCheckerDispatcher(sourceType.name, sourceField.name)
                    ?.let { dispatcher ->
                        val field = sourceSchema.field(sourceType.name, sourceField.name)
                        require(field is QPlanSchema.ObjectField) {
                            "Field checker ${sourceType.name}.${sourceField.name} does not map to " +
                                "a concrete object field"
                        }
                        field to
                            fieldCheckerResolver(
                                schemas = schemas,
                                field = field,
                                queryType = queryType,
                                dispatcher = dispatcher,
                                contextForInvocation = contextForInvocation,
                            )
                    }
            }
        }.toMap()
    val typeCheckers =
        objectTypes.mapNotNull { sourceType ->
            dispatcherRegistry.getTypeCheckerDispatcher(sourceType.name)?.let { dispatcher ->
                val type = schemas.loweredSchema.requireType(sourceType.name)
                require(type is QPlanSchema.Object) {
                    "Type checker ${sourceType.name} does not map to a concrete object type"
                }
                type to
                    typeCheckerResolver(
                        schemas = schemas,
                        type = type,
                        queryType = queryType,
                        dispatcher = dispatcher,
                        contextForInvocation = contextForInvocation,
                    )
            }
        }.toMap()
    return ExecutorRegistryInputs(
        fieldResolvers = adapted.fieldResolvers,
        nodeResolvers = adapted.nodeResolvers,
        variableProviders = adapted.variableProviders,
        fieldCheckers = fieldCheckers,
        typeCheckers = typeCheckers,
    )
}

private fun fieldCheckerResolver(
    schemas: ViaductAndGJSchema,
    field: QPlanSchema.ObjectField,
    queryType: QPlanSchema.Object,
    dispatcher: CheckerDispatcher,
    contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
): FieldCheckerResolver {
    val inputs =
        checkerInputs(
            schemas,
            ResolverTarget.FieldCheckerTarget(field),
            dispatcher,
            contextForInvocation,
        )
    return FieldCheckerResolver.of(
        field = field,
        queryType = queryType,
        fragmentTemplates = inputs.fragmentTemplates,
    ) { arguments, values, resolutionContext ->
        dispatcher.execute(
            arguments = arguments.fieldValues,
            objectDataFactories = inputs.factories(values),
            context = checkerInvocationContext(schemas, resolutionContext, contextForInvocation),
            checkerType = CheckerExecutor.CheckerType.FIELD,
        )
    }
}

private fun typeCheckerResolver(
    schemas: ViaductAndGJSchema,
    type: QPlanSchema.Object,
    queryType: QPlanSchema.Object,
    dispatcher: CheckerDispatcher,
    contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
): TypeCheckerResolver {
    val inputs =
        checkerInputs(
            schemas,
            ResolverTarget.TypeCheckerTarget(type),
            dispatcher,
            contextForInvocation,
        )
    return TypeCheckerResolver.of(
        type = type,
        queryType = queryType,
        fragmentTemplates = inputs.fragmentTemplates,
    ) { values, resolutionContext ->
        dispatcher.execute(
            arguments = emptyMap(),
            objectDataFactories = inputs.factories(values),
            context = checkerInvocationContext(schemas, resolutionContext, contextForInvocation),
            checkerType = CheckerExecutor.CheckerType.TYPE,
        )
    }
}

private fun checkerInvocationContext(
    schemas: ViaductAndGJSchema,
    resolutionContext: ResolutionExecutionContext,
    contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
): EngineExecutionContext =
    QPlanEngineExecutionContext(
        contextForInvocation(resolutionContext),
        schemas,
        resolutionContext,
    )

private enum class CheckerInputRoot {
    OBJECT,
    QUERY,
}

private class CheckerInputs(
    val fragmentTemplates: Map<String, ResolverFragmentTemplates>,
    private val outerRoots: Map<String, CheckerInputRoot>,
) {
    fun factories(values: Map<String, CheckerInput>): Map<String, EngineObjectDataFactory> =
        outerRoots.mapValues { (name, root) ->
            EngineObjectDataFactory {
                val value = values.getValue(name)
                when (root) {
                    CheckerInputRoot.OBJECT -> value.objectValue
                    CheckerInputRoot.QUERY -> value.queryValue
                }
            }
        }
}

private fun checkerInputs(
    schemas: ViaductAndGJSchema,
    target: ResolverTarget,
    dispatcher: CheckerDispatcher,
    contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
): CheckerInputs {
    val variableDefinitions = dispatcher.variableDefinitions
    val compiled =
        dispatcher.requiredSelectionSets.mapValues { (name, required) ->
            compileCheckerInput(
                schemas = schemas,
                target = target,
                name = name,
                required = required,
                variableDefinitions = variableDefinitions.getValue(name),
                contextForInvocation = contextForInvocation,
            )
        }
    return CheckerInputs(
        fragmentTemplates = compiled.mapValues { (_, input) -> input.templates },
        outerRoots = compiled.mapValues { (_, input) -> input.outerRoot },
    )
}

private class CompiledCheckerInput(
    val templates: ResolverFragmentTemplates,
    val outerRoot: CheckerInputRoot,
)

private fun compileCheckerInput(
    schemas: ViaductAndGJSchema,
    target: ResolverTarget,
    name: String,
    required: RequiredSelectionSet?,
    variableDefinitions: ResolverVariableDefinitions,
    contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
): CompiledCheckerInput {
    if (required == null) {
        return CompiledCheckerInput(
            ResolverFragmentTemplates(
                objectFragmentTemplate = materializeSelectionForestOf(),
                queryFragmentTemplate = materializeSelectionForestOf(),
            ),
            CheckerInputRoot.OBJECT,
        )
    }

    val compiler =
        CheckerInputCompiler(
            schemas = schemas,
            target = target,
            inputName = name,
            variableDefinitions = variableDefinitions,
            contextForInvocation = contextForInvocation,
        )
    val outerRoot = compiler.add(required)
    return CompiledCheckerInput(compiler.templates(), outerRoot)
}

/**
 * Compiles dispatcher-supplied checker variable declarations into one runtime2 fragment pair.
 *
 * Opaque RSS execution conditions become private provider-backed Boolean guards. This preserves
 * runtime2's frozen resolver API while preventing excluded RSS fields from activating.
 */
private class CheckerInputCompiler(
    private val schemas: ViaductAndGJSchema,
    private val target: ResolverTarget,
    private val inputName: String,
    private val variableDefinitions: ResolverVariableDefinitions,
    private val contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
) {
    private var objectSelections: MaterializeSelectionForest = materializeSelectionForestOf()
    private var querySelections: MaterializeSelectionForest = materializeSelectionForestOf()
    private val variables = linkedMapOf<Arguments.Variable, VariableDefinition>()
    private val conditionProviders = linkedMapOf<String, QueryPlanExecutionCondition>()
    private val reservedVariableNames =
        mutableSetOf<String>().apply {
            addAll(variableDefinitions.fromArguments.variableNames)
            addAll(variableDefinitions.fromObjectFields.variableNames)
            addAll(variableDefinitions.fromQueryFields.variableNames)
            addAll(variableDefinitions.fromFunction?.variableNames.orEmpty())
        }
    private var nextConditionVariable = 0

    fun add(required: RequiredSelectionSet): CheckerInputRoot {
        val outerRoot = rootOf(required)
        addSelections(required)
        addVariableDefinitions()
        return outerRoot
    }

    private fun addSelections(required: RequiredSelectionSet) {
        val fragment =
            schemas.fragmentFromDocument(
                document = required.selections.toDocument(),
                variableTarget = target,
            )
        val selections = fragment.materializeSelections.guardedBy(conditionFor(required.executionCondition))
        when (rootOf(required)) {
            CheckerInputRoot.OBJECT -> objectSelections += selections
            CheckerInputRoot.QUERY -> querySelections += selections
        }
        // Variable declarations come from the dispatcher. The legacy graph is traversed only to
        // retain the nested selection templates on which from-field declarations depend.
        required.variablesResolvers.mapNotNull { it.requiredSelectionSet }.forEach(::addSelections)
    }

    private fun addVariableDefinitions() {
        variableDefinitions.fromArguments.variables.forEach { (name, path) ->
            addFromArgument(name, path)
        }
        variableDefinitions.fromObjectFields.variables.forEach { (name, path) ->
            addFromField(name, path, CheckerInputRoot.OBJECT)
        }
        variableDefinitions.fromQueryFields.variables.forEach { (name, path) ->
            addFromField(name, path, CheckerInputRoot.QUERY)
        }
        variableDefinitions.fromFunction?.variableNames.orEmpty().forEach { name ->
            putVariable(name, VariableDefinition.FromProvider)
        }
    }

    fun templates(): ResolverFragmentTemplates =
        ResolverFragmentTemplates(
            objectFragmentTemplate = objectSelections,
            queryFragmentTemplate = querySelections,
            variables = variables,
            variablesProvider =
                if (variableDefinitions.fromFunction == null && conditionProviders.isEmpty()) {
                    null
                } else {
                    { arguments ->
                        val resolutionContext = currentVariablesProviderResolutionContext()
                        buildMap {
                            conditionProviders.forEach { (name, condition) ->
                                // A null DFE is valid when execution has no field environment.
                                put(name, condition.shouldExecute(null))
                            }
                            variableDefinitions.fromFunction?.let { provider ->
                                val values =
                                    provider.provideVariables(
                                        engineObjectDataOf(target.checkerObjectType()),
                                        arguments.fieldValues,
                                        checkerInvocationContext(
                                            schemas,
                                            resolutionContext,
                                            contextForInvocation,
                                        ),
                                    )
                                check(values.keys == provider.variableNames) {
                                    "Checker variables provider $inputName returned invalid variables: " +
                                        "expected ${provider.variableNames}, got ${values.keys}"
                                }
                                provider.variableNames.forEach { name -> put(name, values.getValue(name)) }
                            }
                        }
                    }
                },
        )

    private fun conditionFor(condition: QueryPlanExecutionCondition): InclusionCondition {
        if (condition === QueryPlanExecutionCondition.ALWAYS_EXECUTE) return InclusionCondition.Always
        val name =
            generateSequence { "__engine2ExecutionCondition${nextConditionVariable++}" }
                .first { it !in reservedVariableNames }
        reservedVariableNames += name
        conditionProviders[name] = condition
        val variable = Arguments.Variable.of(target, name)
        putVariable(name, VariableDefinition.FromProvider)
        return InclusionCondition.requires(mapOf(variable to true))
    }

    private fun rootOf(required: RequiredSelectionSet): CheckerInputRoot {
        val objectTypeName = target.checkerObjectTypeName()
        return when (required.selections.typeName) {
            objectTypeName -> CheckerInputRoot.OBJECT
            schemas.graphQLSchema.queryType.name -> CheckerInputRoot.QUERY
            else ->
                throw IllegalArgumentException(
                    "Checker input $inputName is rooted at ${required.selections.typeName}; " +
                        "expected $objectTypeName or Query",
                )
        }
    }

    private fun addFromArgument(
        name: String,
        path: String,
    ) {
        val field =
            (target as? ResolverTarget.FieldCheckerTarget)?.field
                ?: throw IllegalArgumentException(
                    "Type checker input $inputName cannot define variable $name " +
                        "from an argument",
                )
        val components = path.split('.')
        val argumentName = components.firstOrNull()
            ?: throw IllegalArgumentException("Argument path for variable $name is empty")
        val argument = field.requireArg(argumentName)
        var inputType = argument.inputType.baseTypeDef
        val inputPath =
            components.drop(1).map { component ->
                val input = inputType as? QPlanSchema.Input
                    ?: throw IllegalArgumentException(
                        "Argument path for variable $name cannot traverse $inputType",
                    )
                val pathField = input.requireField(component)
                inputType = pathField.inputType.baseTypeDef
                pathField
            }
        putVariable(
            name,
            VariableDefinition.FromArgument.of(argument, inputPath),
        )
    }

    private fun addFromField(
        name: String,
        path: String,
        root: CheckerInputRoot,
    ) {
        val responsePath = path.split('.')
        val providerSelections =
            when (root) {
                CheckerInputRoot.OBJECT -> objectSelections
                CheckerInputRoot.QUERY -> querySelections
            }
        putVariable(
            name,
            VariableDefinition.FromField.of(
                providerFragment =
                    when (root) {
                        CheckerInputRoot.OBJECT -> ProviderFragment.OBJECT
                        CheckerInputRoot.QUERY -> ProviderFragment.QUERY
                    },
                path = providerSelections.keysAtResponsePath(responsePath, name),
                responsePath = responsePath,
            ),
        )
    }

    private fun putVariable(
        name: String,
        definition: VariableDefinition,
    ) {
        val variable = Arguments.Variable.of(target, name)
        val existing = variables[variable]
        require(existing == null || existing == definition) {
            "Checker input $inputName defines variable $name inconsistently"
        }
        variables[variable] = definition
    }
}

private fun ResolverTarget.checkerObjectTypeName(): String =
    when (this) {
        is ResolverTarget.FieldCheckerTarget -> field.containingDef.name
        is ResolverTarget.TypeCheckerTarget -> type.name
        is ResolverTarget.FieldValueResolverTarget -> error("A value resolver cannot own checker inputs")
    }

private fun ResolverTarget.checkerObjectType(): QPlanSchema.Object =
    when (this) {
        is ResolverTarget.FieldCheckerTarget -> field.containingDef
        is ResolverTarget.TypeCheckerTarget -> type
        is ResolverTarget.FieldValueResolverTarget -> error("A value resolver cannot own checker inputs")
    }

private fun MaterializeSelectionForest.keysAtResponsePath(
    responsePath: List<String>,
    variableName: String,
): List<ObjectEngineResult.Key> {
    require(responsePath.isNotEmpty()) { "Field path for variable $variableName is empty" }
    var current = this
    return responsePath.map { responseKey ->
        val matches = mutableListOf<viaduct.engine.runtime2.model.MaterializeSelection>()
        current.forEach { selection ->
            if (selection.responseKey == responseKey) matches += selection
        }
        require(matches.isNotEmpty()) {
            "Field path for variable $variableName has no selection named $responseKey"
        }
        val key = matches.first().key
        require(matches.all { it.key == key }) {
            "Field path for variable $variableName has incompatible selections named $responseKey"
        }
        current = matches.fold(materializeSelectionForestOf()) { selections, match ->
            selections + match.subselections
        }
        key
    }
}

/** Field-executor-shaped shim whose invocation remains dispatcher-backed. */
private class DispatcherFieldExecutor(
    private val coordinate: Coordinate,
    private val dispatcher: FieldResolverDispatcher,
) : FieldResolverExecutor {
    override val objectSelectionSet get() = dispatcher.objectSelectionSet
    override val querySelectionSet get() = dispatcher.querySelectionSet
    override val argumentVariables get() = dispatcher.variableDefinitions.fromArguments
    override val objectFieldVariables get() = dispatcher.variableDefinitions.fromObjectFields
    override val queryFieldVariables get() = dispatcher.variableDefinitions.fromQueryFields
    override val variablesFromFunctionProvider get() = dispatcher.variableDefinitions.fromFunction
    override val isSelective get() = dispatcher.isSelective
    override val resolverId: String get() = coordinate.render()
    override val metadata get() = dispatcher.resolverMetadata
    override val isBatching: Boolean = false

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        require(selectors.size == 1) {
            "Runtime2 dispatcher shims require singleton field invocations"
        }
        val selector = selectors.single()
        return mapOf(
            selector to
                runCatching {
                    dispatcher.resolve(
                        arguments = selector.arguments,
                        objectValueFactory = EngineObjectDataFactory { selector.syncObjectValueGetter() },
                        queryValueFactory = EngineObjectDataFactory { selector.syncQueryValueGetter() },
                        selections = selector.selections,
                        context = context,
                    )
                },
        )
    }
}

/** Node-executor-shaped shim whose invocation remains dispatcher-backed. */
private class DispatcherNodeExecutor(
    override val typeName: String,
    private val dispatcher: NodeResolverDispatcher,
) : NodeResolverExecutor {
    override val metadata get() = dispatcher.resolverMetadata
    override val isBatching: Boolean = false
    override val isSelective get() = dispatcher.isSelective

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        require(selectors.size == 1) {
            "Runtime2 dispatcher shims require singleton node invocations"
        }
        val selector = selectors.single()
        return mapOf(
            selector to
                runCatching {
                    dispatcher.resolve(selector.id, selector.selections, context)
                },
        )
    }
}

private fun namespaceFieldResolvers(
    fullSchema: EngineSchema,
    schema: QPlanSchema,
    sourceSchema: SourceSchemaAdapter,
    suppliedFields: Set<QPlanSchema.Field>,
): Map<QPlanSchema.Field, FieldResolverDefinition> =
    fullSchema.schema.allTypesAsList
        .filterIsInstance<GraphQLObjectType>()
        .flatMap { sourceParent ->
            sourceParent.fieldDefinitions.mapNotNull { sourceField ->
                val sourceOutput = GraphQLTypeUtil.unwrapAll(sourceField.type) as? GraphQLObjectType
                    ?: return@mapNotNull null
                if (!sourceOutput.hasAppliedDirective("namespaceType")) return@mapNotNull null
                val field = sourceSchema.field(sourceParent.name, sourceField.name)
                require(field is QPlanSchema.ObjectField) {
                    "Namespace field ${sourceParent.name}/${sourceField.name} " +
                        "does not map to a concrete object field"
                }
                if (field in suppliedFields) return@mapNotNull null
                val outputType = schema.requireType(sourceOutput.name)
                require(outputType is QPlanSchema.Object) {
                    "Namespace field ${sourceParent.name}/${sourceField.name} " +
                        "does not return a canonical object"
                }
                field to
                    fieldResolverOf(schema.emptyFragmentOf(field.containingDef.name)) { _, _ ->
                        engineObjectDataOf(outputType)
                    }
            }
        }.toMap()

private fun FieldResolverExecutor.objectFragment(
    schema: ViaductAndGJSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    objectSelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.loweredSchema.emptyFragmentOf(field.containingDef.name)

private fun FieldResolverExecutor.queryFragment(
    schema: ViaductAndGJSchema,
    field: QPlanSchema.ObjectField,
): Fragment =
    querySelectionSet?.let { required ->
        schema.fragmentFromDocument(
            document = required.selections.toDocument(),
            variableField = field,
        )
    } ?: schema.loweredSchema.emptyFragmentOf(schema.loweredSchema.requireQueryTypeDef().name)

private fun builtInNodeFieldResolvers(
    fullSchema: EngineSchema,
    schema: QPlanSchema,
    context: EngineExecutionContext,
    suppliedFields: Set<QPlanSchema.Field>,
): Map<QPlanSchema.Field, FieldResolverDefinition> {
    val sourceSchema = SourceSchemaAdapter(schema)
    val query = schema.emptyFragmentOf(schema.requireQueryTypeDef().name)
    return buildMap {
        fullSchema.schema.queryType.getFieldDefinition("node")?.let { sourceField ->
            val field = sourceSchema.field(fullSchema.schema.queryType.name, sourceField.name)
            if (field !in suppliedFields) {
                put(
                    field,
                    fieldResolverOf(query) { _, arguments ->
                        nodeReference(arguments.fieldValues["id"], context)
                    },
                )
            }
        }
        fullSchema.schema.queryType.getFieldDefinition("nodes")?.let { sourceField ->
            val field = sourceSchema.field(fullSchema.schema.queryType.name, sourceField.name)
            if (field !in suppliedFields) {
                put(
                    field,
                    fieldResolverOf(query) { _, arguments ->
                        val ids = arguments.fieldValues["ids"]
                        if (ids !is List<*>) {
                            EngineErrorData.of()
                        } else {
                            ids.map { nodeReference(it, context) }
                        }
                    },
                )
            }
        }
    }
}

private fun nodeReference(
    globalId: Any?,
    context: EngineExecutionContext,
): Any {
    if (globalId !is String) return EngineErrorData.of()
    return try {
        val (typeName) = context.globalIDCodec.deserialize(globalId)
        val type =
            context.fullSchema.schema.getObjectType(typeName)
                ?: return EngineErrorData.of()
        if (type.interfaces.none { it.name == "Node" }) return EngineErrorData.of()
        normalizeNodeReference(context.createNodeReference(globalId, type))
    } catch (_: IllegalArgumentException) {
        EngineErrorData.of()
    }
}

private fun qplanNodeResolvers(
    fullSchema: EngineSchema,
    schemas: ViaductAndGJSchema,
    fieldResolverExecutors: List<Pair<Coordinate, FieldResolverExecutor>>,
    nodeResolverExecutors: List<Pair<String, NodeResolverExecutor>>,
    contextForInvocation: (ResolutionExecutionContext) -> EngineExecutionContext,
): Map<QPlanSchema.Object, NodeResolverFunction> {
    val schema = schemas.loweredSchema
    val sourceSchema = SourceSchemaAdapter(schema)
    val supplied =
        nodeResolverExecutors.associate { (typeName, executor) ->
            val type = schema.requireType(typeName) as QPlanSchema.Object
            val fieldResolverOwnedFields =
                fieldResolverExecutors
                    .mapNotNullTo(linkedSetOf()) { (coordinate, _) ->
                        coordinate.second.takeIf { coordinate.first == typeName }
                    }

            suspend fun invokeExecutor(
                id: String,
                selections: EngineSelectionSet,
                executorContext: EngineExecutionContext,
            ): ResolverOutputData? {
                if (
                    executor.isSelective &&
                    selections.selections().all { selection -> selection.fieldName == "id" }
                ) {
                    return ResolvedEngineObjectData(
                        requireNotNull(fullSchema.schema.getObjectType(typeName)),
                        emptyMap(),
                    )
                }
                val selector = NodeResolverExecutor.Selector(id, selections)
                val output =
                    executor.resolve(listOf(selector), executorContext)[selector]
                        ?: Result.failure(
                            IllegalStateException(
                                "Node executor $typeName omitted its selector",
                            ),
                        )
                return output.fold(
                    onSuccess = {
                        when (
                            val normalized =
                                normalizeSourceOutput(
                                    requireNotNull(fullSchema.schema.getObjectType(typeName)),
                                    it,
                                    sourceSchema,
                                )
                        ) {
                            is RootFieldReferenceData -> normalized
                            is EngineObjectData.Sync ->
                                if (executor.isSelective) {
                                    normalized.projectTopLevel(
                                        selections = selections,
                                        excludedFields = fieldResolverOwnedFields,
                                    )
                                } else {
                                    normalized
                                }
                            else -> error("Node executor $typeName returned a non-object value")
                        }
                    },
                    onFailure = { EngineErrorData.of(it) },
                )
            }
            type to
                if (executor.isSelective) {
                    selectiveNodeResolverOf { id, selections, resolutionContext ->
                        invokeExecutor(
                            id,
                            selections.toEngineSelectionSet(type, fullSchema, sourceSchema),
                            QPlanEngineExecutionContext(
                                contextForInvocation(resolutionContext),
                                schemas,
                                resolutionContext,
                            ),
                        )
                    }
                } else {
                    nodeResolverOf { id, resolutionContext ->
                        invokeExecutor(
                            id,
                            contextForInvocation(resolutionContext).engineSelectionSetFactory.engineSelectionSet(
                                typeName,
                                "id",
                                emptyMap(),
                            ),
                            QPlanEngineExecutionContext(
                                contextForInvocation(resolutionContext),
                                schemas,
                                resolutionContext,
                            ),
                        )
                    }
                }
        }
    return supplied
}

private fun EngineObjectData.Sync.projectTopLevel(
    selections: EngineSelectionSet,
    excludedFields: Set<String>,
): EngineObjectData.Sync {
    val demandedFields =
        selections
            .selections()
            .mapTo(linkedSetOf()) { it.fieldName }
            .minus(excludedFields)
    val fields =
        getSelections()
            .filter { fieldName -> fieldName in demandedFields }
            .associateWithTo(linkedMapOf(), ::get)
    return ResolvedEngineObjectData(
        type,
        fields,
    )
}

private fun normalizeSourceOutput(
    expectedType: GraphQLOutputType,
    value: Any?,
    sourceSchema: SourceSchemaAdapter,
): Any? =
    if (value is EngineErrorData) {
        value
    } else if (value is RootFieldReference) {
        sourceSchema.lowerRootFieldReference(
            rootFieldPath = value.rootFieldPath,
            sourceTypeName = value.type.name,
            arguments = value.args,
        )
    } else {
        when (expectedType) {
            is GraphQLNonNull ->
                normalizeSourceOutput(expectedType.wrappedType as GraphQLOutputType, value, sourceSchema)
            is GraphQLList -> {
                if (value !is List<*>) {
                    value
                } else {
                    value.map {
                        normalizeSourceOutput(
                            expectedType.wrappedType as GraphQLOutputType,
                            it,
                            sourceSchema,
                        )
                    }
                }
            }
            is GraphQLObjectType ->
                when (value) {
                    is NodeReference -> normalizeNodeReference(value)
                    is EngineObjectData.Sync -> normalizeSourceObject(expectedType, value, sourceSchema)
                    is Map<*, *> -> normalizeSourceObjectMap(expectedType, value, sourceSchema)
                    else -> value
                }
            is GraphQLCompositeType ->
                when (value) {
                    is NodeReference -> normalizeNodeReference(value)
                    is EngineObjectData.Sync -> normalizeSourceObject(value, sourceSchema)
                    else -> value
                }
            is GraphQLScalarType ->
                value?.let {
                    expectedType.coercing.serialize(
                        it,
                        GraphQLContext.getDefault(),
                        Locale.getDefault(),
                    )
                }
            else -> value
        }
    }

private fun normalizeSourceObjectMap(
    expectedType: GraphQLObjectType,
    value: Map<*, *>,
    sourceSchema: SourceSchemaAdapter,
): EngineObjectData.Sync {
    require(value.keys.all { it is String }) {
        "Qplan requires string keys in map object executor outputs"
    }
    @Suppress("UNCHECKED_CAST")
    return normalizeSourceObject(
        ResolvedEngineObjectData(expectedType, value as Map<String, Any?>),
        sourceSchema,
    )
}

private fun normalizeNodeReference(reference: NodeReference): EngineObjectData.Sync =
    ResolvedEngineObjectData(
        reference.type,
        mapOf("id" to reference.id),
    )

private fun normalizeSourceObject(
    value: EngineObjectData,
    sourceSchema: SourceSchemaAdapter,
): EngineObjectData.Sync {
    require(value is EngineObjectData.Sync) {
        "Qplan requires synchronous EngineObjectData executor outputs"
    }
    return normalizeSourceObject(value.type, value, sourceSchema)
}

private fun normalizeSourceObject(
    type: GraphQLObjectType,
    value: EngineObjectData.Sync,
    sourceSchema: SourceSchemaAdapter,
): EngineObjectData.Sync {
    val fields =
        value.getSelections().associateWith { selection ->
            val field =
                requireNotNull(type.getFieldDefinition(selection)) {
                    "Executor output ${type.name} has no field named $selection"
                }
            normalizeSourceOutput(field.type, value.get(selection), sourceSchema)
        }
    return ResolvedEngineObjectData(type, fields)
}

private fun Pair<String, String>.render(): String = "$first.$second"

internal fun validateExecutorRegistrations(
    fieldResolverExecutors: List<Pair<Coordinate, FieldResolverExecutor>>,
    nodeResolverExecutors: List<Pair<String, NodeResolverExecutor>>,
) {
    require(fieldResolverExecutors.map { it.first }.toSet().size == fieldResolverExecutors.size) {
        "Qplan requires unique field executor coordinates"
    }
    require(nodeResolverExecutors.map { it.first }.toSet().size == nodeResolverExecutors.size) {
        "Qplan requires unique node executor types"
    }
    fieldResolverExecutors.forEach { (coordinate, executor) ->
        if (executor.isBatching) TODO("Qplan does not support batching field executor ${coordinate.render()}")
    }
    nodeResolverExecutors.forEach { (typeName, executor) ->
        if (executor.isBatching) TODO("Qplan does not support batching node executor $typeName")
    }
}
