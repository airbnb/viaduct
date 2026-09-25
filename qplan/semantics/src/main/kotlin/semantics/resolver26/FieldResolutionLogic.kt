package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import model.Arguments
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.EngineResult
import model.ErrorEngineResult
import model.NodeReferenceIdentity
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.ResolverOccurrenceId
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.Selection
import model.SelectionForest
import model.VariableBinding
import model.engineObjectDataOf
import model.guardedBy
import model.invariants.conformsToResolverOutputSchemaType
import model.materializeSelectionForestOf
import model.merge
import model.nodeReferenceIdentityOrNull
import model.registry.ProviderFragment
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.selectionForestOf
import model.satisfiableAlternatives
import semantics.shared.argumentsContainErrorValue
import semantics.shared.ResolverInvocationObservation
import semantics.shared.fieldResolverCycleTask
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.fetchGroundedArguments
import semantics.shared.withAuthoritativeNodeId
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver or root-field reference. */
internal class FieldResolutionLogic(
    private val fieldResolverTask: FieldResolverTask,
) {
    /** Owns the bindings that must receive errors if the current invocation fails. */
    private var currentInvocation =
        fieldResolverTask.publication.sourceOccurrence as? FieldResolverOccurrence

    fun validate() {
        val publication = fieldResolverTask.publication
        val sourceOccurrence = publication.sourceOccurrence
        val selection = sourceOccurrence.selection
        require(selection.key.field.containingDef == publication.oerOccurrence.target.type) {
            "Resolver selection does not belong to its target occurrence"
        }

        val objectFieldPublication =
            sourceOccurrence.publicationPath.lastOrNull() is ObjectEngineResult.ObjectKey
        if (objectFieldPublication) {
            require(publication.oerOccurrence.target.getCell(selection.key) === publication.publicationCell) {
                "Resolver cell does not belong to its target occurrence and selection"
            }
        }

        when (sourceOccurrence) {
            is FieldResolverOccurrence -> {
                val resolver = sourceOccurrence.resolver
                val resolverOccurrenceId = sourceOccurrence.resolverOccurrenceId
                require(resolver.field == selection.key.field) {
                    "Resolver field ${resolver.field.name} does not match ${selection.key.field.name}"
                }
                require(
                    resolverOccurrenceId ==
                        ResolverOccurrenceId.at(
                            publication.oerOccurrence.root,
                            publication.oerOccurrence.coordinate(selection.key),
                        ),
                ) {
                    "Resolver occurrence ID does not match its target occurrence and selection"
                }
            }
            is RootFieldReferenceOccurrence -> {
                require(sourceOccurrence.reference.targetField in publication.operation.world.resolverRegistry) {
                    "Root-field-reference target has no resolver"
                }
            }
            is PassiveValueOccurrence -> Unit
        }
    }

    fun publishFieldError(cause: Exception) {
        val publication = fieldResolverTask.publication
        currentInvocation?.variableDefinitions?.forEach { definition ->
            if (
                definition.definition == VariableDefinition.FromProvider ||
                definition.definition is VariableDefinition.FromArgument
            ) {
                publication.operation.variableBindings.completeBinding(
                    requireNotNull(definition.variable.instanceId),
                    VariableBinding.Error,
                )
            }
        }
        publication.publicationCell.setActivated(true)
        publication.publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of(cause)))
    }

    suspend fun publishResult() {
        val publication = fieldResolverTask.publication
        val sourceOccurrence = publication.sourceOccurrence
        val selection = sourceOccurrence.selection
        val constructionDemand = sourceOccurrence.publicationConstructionDemand
        val invocationDemand: SelectionForest =
            when (sourceOccurrence) {
                is PassiveValueOccurrence -> sourceOccurrence.invocationDemand
                else -> constructionDemand.successorDemand(publication.operation.world)
            }

        val activated = activatePublication()
        if (!activated) return

        var fieldValue: ResolverOutputData? =
            when (sourceOccurrence) {
                is FieldResolverOccurrence ->
                    runFieldResolver(
                        fieldResolverOccurrence = sourceOccurrence,
                        selection = selection,
                        invocationDemand = invocationDemand,
                    )
                is RootFieldReferenceOccurrence -> sourceOccurrence.reference
                is PassiveValueOccurrence -> sourceOccurrence.value
            }

        var authoritativeNodeIdentity: NodeReferenceIdentity? = null
        while (fieldValue is RootFieldReferenceData) {
            val reference = fieldValue
            authoritativeNodeIdentity =
                authoritativeNodeIdentity ?: reference.nodeReferenceIdentityOrNull()
            require(
                reference.conformsToResolverOutputSchemaType(
                    sourceOccurrence.publicationExpectedType,
                ),
            ) {
                "Root-field-reference target ${reference.type.name} does not conform to " +
                    "the consumer publication type"
            }
            val invocation =
                createRootFieldResolverOccurrence(
                    reference = reference,
                    constructionDemand = constructionDemand,
                )
            fieldValue =
                invokeRootFieldResolver(
                    fieldResolverOccurrence = invocation,
                    arguments = reference.arguments,
                    invocationDemand = invocationDemand,
                )
            publication.operation.resolverObserver.onRootFieldReferenceInvocation(
                RootFieldReferenceInvocationObservation(
                    publicationRoot = publication.oerOccurrence.root,
                    publicationPath = sourceOccurrence.publicationPath,
                    reference = reference,
                    invocationRoot = invocation.invocationRoot,
                    invocationPath = invocation.invocationPath,
                    invocationKey = invocation.selection.key,
                    suppliedDemand = invocationDemand,
                ),
            )
        }

        fieldValue =
            fieldValue.withAuthoritativeNodeId(
                identity = authoritativeNodeIdentity,
                demand = invocationDemand,
            )

        val passiveValue: EngineResult? =
            fieldValue.resolvePassiveValues(
                operation = publication.operation,
                root = publication.oerOccurrence.root,
                expectedType = sourceOccurrence.publicationExpectedType,
                path = sourceOccurrence.publicationPath,
                invocationDemand = invocationDemand,
                constructionDemand = constructionDemand,
                parent = publication.oerOccurrence,
            )

        publication.publicationCell.getValue().complete(passiveValue)
    }

    private suspend fun activatePublication(): Boolean {
        val publication = fieldResolverTask.publication
        val sourceOccurrence = publication.sourceOccurrence
        val publicationCellNeedsActivation =
            sourceOccurrence.publicationPath.lastOrNull() is ObjectEngineResult.ObjectKey
        if (!publicationCellNeedsActivation) return true

        val fieldResolverOccurrence = (publication.sourceOccurrence as? FieldResolverOccurrence)
        val fromArgumentVariableIds =
            fieldResolverOccurrence
                ?.variableDefinitions
                ?.filter { definition ->
                    definition.definition is VariableDefinition.FromArgument
                }?.mapTo(linkedSetOf()) { definition ->
                    requireNotNull(definition.variable.instanceId)
                }.orEmpty()
        val activated =
            sourceOccurrence.selection.inclusionCondition.includeAnyReadyAlternative { variable ->
                val variableId = requireNotNull(variable.instanceId)
                if (
                    fieldResolverOccurrence != null &&
                    variableId in fromArgumentVariableIds &&
                    !publication.operation.variableBindings.isBound(variableId)
                ) {
                    val groundedArguments =
                        sourceOccurrence.selection.key.fetchGroundedArguments(publication.operation)
                    completeFromArgumentBindings(fieldResolverOccurrence, groundedArguments)
                }
                when (
                    val binding = publication.operation.variableBindings.fetchBinding(
                        variableId,
                    )
                ) {
                    VariableBinding.Error -> error("Inclusion-condition variable failed")
                    is VariableBinding.Input -> binding.value as? Boolean
                        ?: error("Inclusion-condition variable must contain a Boolean")
                }
            }
        check(publication.publicationCell.setActivated(activated)) {
            "Resolver26 field-task cell activation was already decided"
        }
        return activated
    }

    /** Shared cells activate from any independently ready true demand alternative. */
    private suspend fun model.InclusionCondition.includeAnyReadyAlternative(
        binding: suspend (Arguments.Variable) -> Boolean,
    ): Boolean = supervisorScope {
        val alternatives = satisfiableAlternatives()
        if (alternatives.size <= 1) {
            return@supervisorScope alternatives.singleOrNull()?.include(binding) ?: false
        }
        val remaining =
            alternatives.mapTo(linkedSetOf()) { alternative ->
                async {
                    try {
                        Result.success(alternative.include(binding))
                    } catch (cause: Exception) {
                        Result.failure(cause)
                    }
                }
            }
        var failure: Throwable? = null
        while (remaining.isNotEmpty()) {
            val (completed, result) =
                select<Pair<Deferred<Result<Boolean>>, Result<Boolean>>> {
                    remaining.forEach { alternative ->
                        alternative.onAwait { result -> alternative to result }
                    }
                }
            remaining.remove(completed)
            result.fold(
                onSuccess = { included ->
                    if (included) {
                        remaining.forEach { it.cancel() }
                        remaining.forEach { alternative ->
                            try {
                                alternative.await()
                            } catch (_: CancellationException) {
                                currentCoroutineContext().ensureActive()
                            }
                        }
                        return@supervisorScope true
                    }
                },
                onFailure = { cause ->
                    currentCoroutineContext().ensureActive()
                    if (failure == null) failure = cause
                },
            )
        }
        failure?.let { throw it }
        false
    }

    private suspend fun runFieldResolver(
        fieldResolverOccurrence: FieldResolverOccurrence,
        selection: ObjectSelection,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val publication = fieldResolverTask.publication
        val groundedArguments =
            selection.key.fetchGroundedArguments(publication.operation)
        completeFromArgumentBindings(fieldResolverOccurrence, groundedArguments)
        if (groundedArguments.argumentsContainErrorValue()) {
            completeVariablesProviderBindingsWithError(fieldResolverOccurrence)
            return EngineErrorData.of()
        }

        val resolverArguments = groundedArguments as Arguments.Resolved
        val providerError =
            completeVariablesProviderBindings(
                fieldResolverOccurrence = fieldResolverOccurrence,
                arguments = resolverArguments,
            )
        if (providerError != null) return providerError

        val objectMaterializationSelections =
            fieldResolverOccurrence.resolver.instantiateObjectMaterializationSelections(
                fieldResolverOccurrence.resolverOccurrenceId,
            )
        val input: EngineObjectData.Sync =
            publication.oerOccurrence.target.materializeResolverInput(
                operation = publication.operation,
                cycleChecker = publication.operation.cycleChecker,
                selections = objectMaterializationSelections,
                reader =
                    fieldResolverOccurrence.invocationRoot
                        .fieldResolverCycleTask(fieldResolverOccurrence.invocationPath),
                resultPath = publication.oerOccurrence.path,
            )
        val queryValue = materializeQueryFragment(fieldResolverOccurrence)
        val queryMaterializationSelections =
            fieldResolverOccurrence.resolver
                .instantiateQueryMaterializationSelections(
                    fieldResolverOccurrence.resolverOccurrenceId,
                ).guardedBy(fieldResolverOccurrence.selection.inclusionCondition)
        publication.operation.resolverObserver.onResolverInvocation(
            ResolverInvocationObservation(
                occurrencePath = fieldResolverOccurrence.publicationPath,
                field = selection.key.field,
                input = input,
                inputSelections = objectMaterializationSelections,
                queryValue = queryValue,
                queryInputSelections = queryMaterializationSelections,
                arguments = resolverArguments,
                suppliedDemand = invocationDemand,
                resolverOccurrenceId = fieldResolverOccurrence.resolverOccurrenceId,
            ),
        )

        return fieldResolverOccurrence.resolver(
            input = input,
            queryValue = queryValue,
            arguments = resolverArguments,
            selections = invocationDemand,
            selectiveResolvers = publication.operation.world.selectiveResolvers,
            executionContext = fieldResolverTask,
        )
    }

    private suspend fun materializeQueryFragment(
        fieldResolverOccurrence: FieldResolverOccurrence,
    ): EngineObjectData.Sync {
        val publication = fieldResolverTask.publication
        val queryFragment = fieldResolverOccurrence.fragments.queryFragment
        check(queryFragment.constructionSelections.isEmpty() || publication.queryOER.isDemanded()) {
            "Nonempty resolver Query fragment has no demanded shared Query OER"
        }
        val materializationSelections =
            fieldResolverOccurrence.resolver
                .instantiateQueryMaterializationSelections(
                    queryFragment.resolverOccurrenceId,
                )
        return publication.queryOER.occurrence.target.materializeResolverInput(
            operation = publication.operation,
            cycleChecker = publication.operation.cycleChecker,
            selections = materializationSelections,
            reader =
                fieldResolverOccurrence.invocationRoot
                    .fieldResolverCycleTask(fieldResolverOccurrence.invocationPath),
            resultPath = emptyList(),
        )
    }

    private fun createRootFieldResolverOccurrence(
        reference: RootFieldReferenceData,
        constructionDemand: SelectionForest,
    ): FieldResolverOccurrence {
        val publication = fieldResolverTask.publication
        val queryRoot = ObjectEngineResult.of(publication.operation.world.schema.requireQueryTypeDef())
        val prefixKeys =
            reference.path.dropLast(1).map { prefixField ->
                ObjectEngineResult.GroundKey.of(prefixField, emptyMap())
            }
        val targetKey =
            ObjectEngineResult.GroundKey.of(reference.targetField, reference.arguments)
        val invocationPath: List<PathComponent> = prefixKeys + targetKey
        val resolverOccurrenceId = ResolverOccurrenceId.at(queryRoot, invocationPath)
        val resolver = publication.operation.world.resolverRegistry.resolver(reference.targetField)
        val fragments = resolver.instantiateFragments(resolverOccurrenceId)
        require(fragments.objectFragment.constructionSelections.isEmpty()) {
            "Root-field-reference target ${reference.targetField.containingDef.name}/" +
                "${reference.targetField.name} must not declare an object fragment"
        }
        require(
            resolver.variables.values.none { definition ->
                definition is VariableDefinition.FromField &&
                    definition.providerFragment == ProviderFragment.OBJECT
            },
        ) {
            "Root-field-reference target ${reference.targetField.containingDef.name}/" +
                "${reference.targetField.name} must not declare FromObjectField variables"
        }
        val fieldResolverOccurrence =
            FieldResolverOccurrence(
                selection =
                    selectionForestOf(
                        Selection.of(
                            key = targetKey,
                            possibleTypes = setOf(reference.targetField.containingDef),
                            subselections = constructionDemand,
                        ),
                    ).merge(reference.targetField.containingDef).byKey().getValue(targetKey),
                invocationRoot = queryRoot,
                invocationPath = invocationPath,
                resolverOccurrenceId = resolverOccurrenceId,
                resolver = resolver,
                variableDefinitions =
                    resolver.instantiatedVariableDefinitions(resolverOccurrenceId),
                fragments = fragments,
            )
        declareRootFieldInvocationBindings(fieldResolverOccurrence, reference.arguments)
        return fieldResolverOccurrence
    }

    private fun declareRootFieldInvocationBindings(
        fieldResolverOccurrence: FieldResolverOccurrence,
        arguments: Arguments.Resolved,
    ) {
        val publication = fieldResolverTask.publication
        fieldResolverOccurrence.variableDefinitions.forEach { variableDefinition ->
            val variableId = requireNotNull(variableDefinition.variable.instanceId)
            when (val definition = variableDefinition.definition) {
                VariableDefinition.FromProvider ->
                    publication.operation.variableBindings.declareBinding(variableId)
                is VariableDefinition.FromArgument ->
                    publication.operation.variableBindings.bindVariable(
                        variableId,
                        bindingFor(arguments, definition),
                    )
                is VariableDefinition.FromField -> {
                    require(definition.providerFragment == ProviderFragment.QUERY) {
                        "Root-field-reference targets cannot use object-field variables"
                    }
                    publication.operation.variableBindings.declareBinding(variableId)
                }
            }
        }
    }

    private suspend fun invokeRootFieldResolver(
        fieldResolverOccurrence: FieldResolverOccurrence,
        arguments: Arguments.Resolved,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val publication = fieldResolverTask.publication
        currentInvocation = fieldResolverOccurrence
        val queryProducer = fieldResolverTask.launchQueryFragmentProducer(fieldResolverOccurrence)
        completeVariablesProviderBindings(fieldResolverOccurrence, arguments)?.let { return it }
        val input = engineObjectDataOf(fieldResolverOccurrence.resolver.field.containingDef)
        val queryValue =
            when (val value = queryProducer.await()) {
                is EngineObjectOrErrorData.Success -> value.value
                is EngineObjectOrErrorData.Error -> return value.error
            }
        val queryMaterializationSelections =
            fieldResolverOccurrence.resolver
                .instantiateQueryMaterializationSelections(
                    fieldResolverOccurrence.resolverOccurrenceId,
                ).guardedBy(fieldResolverOccurrence.selection.inclusionCondition)
        publication.operation.resolverObserver.onResolverInvocation(
            ResolverInvocationObservation(
                occurrencePath = fieldResolverOccurrence.invocationPath,
                field = fieldResolverOccurrence.selection.key.field,
                input = input,
                inputSelections = materializeSelectionForestOf(),
                queryValue = queryValue,
                queryInputSelections = queryMaterializationSelections,
                arguments = arguments,
                suppliedDemand = invocationDemand,
                resolverOccurrenceId = fieldResolverOccurrence.resolverOccurrenceId,
            ),
        )
        return fieldResolverOccurrence.resolver(
            input = input,
            queryValue = queryValue,
            arguments = arguments,
            selections = invocationDemand,
            selectiveResolvers = publication.operation.world.selectiveResolvers,
            executionContext = fieldResolverTask,
        )
    }

    // Calls the tenant provider once for this occurrence and publishes its complete binding set.
    // A provider failure becomes the owning field's error while also unblocking fragment work.
    private suspend fun completeVariablesProviderBindings(
        fieldResolverOccurrence: FieldResolverOccurrence,
        arguments: Arguments.Resolved,
    ): EngineErrorData? {
        val publication = fieldResolverTask.publication
        val resolver = fieldResolverOccurrence.resolver
        val provider = resolver.variablesProvider ?: return null
        val providerDefinitions =
            fieldResolverOccurrence.variableDefinitions.filter { definition ->
                definition.definition == VariableDefinition.FromProvider
            }
        val expectedNames =
            providerDefinitions.mapTo(linkedSetOf()) { definition ->
                definition.variable.variableName
            }
        val values =
            try {
                provider(arguments)
            } catch (exception: Exception) {
                currentCoroutineContext().ensureActive()
                completeVariablesProviderBindingsWithError(fieldResolverOccurrence)
                return EngineErrorData.of(exception)
            }
        if (values.keys != expectedNames) {
            val extra = values.keys - expectedNames
            val missing = expectedNames - values.keys
            completeVariablesProviderBindingsWithError(fieldResolverOccurrence)
            error(
                buildString {
                    append("VariablesProvider returned invalid variables.")
                    if (extra.isNotEmpty()) append(" Extra keys: ${extra.joinToString(",")}")
                    if (missing.isNotEmpty()) append(" Missing keys: ${missing.joinToString(",")}")
                },
            )
        }
        providerDefinitions.forEach { definition ->
            publication.operation.variableBindings.completeBinding(
                requireNotNull(definition.variable.instanceId),
                values.getValue(definition.variable.variableName),
            )
        }
        return null
    }

    private fun completeVariablesProviderBindingsWithError(
        fieldResolverOccurrence: FieldResolverOccurrence,
    ) {
        val publication = fieldResolverTask.publication
        fieldResolverOccurrence.variableDefinitions.forEach { definition ->
            if (definition.definition != VariableDefinition.FromProvider) return@forEach
            publication.operation.variableBindings.completeBinding(
                requireNotNull(definition.variable.instanceId),
                VariableBinding.Error,
            )
        }
    }

    // Fills FromArgument bindings that were declared while their owning resolver key was symbolic.
    // Bindings for already-ground owners received their values during binding declaration.
    private fun completeFromArgumentBindings(
        fieldResolverOccurrence: FieldResolverOccurrence,
        groundedArguments: Arguments.Ground,
    ) {
        val publication = fieldResolverTask.publication
        if (fieldResolverOccurrence.selection.key is ObjectEngineResult.GroundKey) return
        fieldResolverOccurrence.variableDefinitions.forEach { variableDefinition ->
            if (variableDefinition.definition !is VariableDefinition.FromArgument) {
                return@forEach
            }
            val definition = variableDefinition.definition as VariableDefinition.FromArgument
            val variableId = requireNotNull(variableDefinition.variable.instanceId)
            if (publication.operation.variableBindings.isBound(variableId)) return@forEach
            publication.operation.variableBindings.completeBinding(
                variableId,
                bindingFor(groundedArguments, definition),
            )
        }
    }
}
