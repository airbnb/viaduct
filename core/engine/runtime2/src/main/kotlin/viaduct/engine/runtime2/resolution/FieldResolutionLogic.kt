package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineObjectOrErrorData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.NodeReferenceIdentity
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.conformsToResolverOutputSchemaType
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.RootFieldReferenceInvocationObservation
import viaduct.engine.runtime2.resolution.framework.argumentsContainErrorValue
import viaduct.engine.runtime2.resolution.framework.fetchGroundedArguments
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.withAuthoritativeNodeId
import viaduct.graphql.schema.ViaductSchema

/** Invokes and publishes one already-installed field resolver or root-field reference. */
internal class FieldResolutionLogic(
    private val fieldResolverTask: FieldResolverTask,
) {
    /** Owns the bindings that must receive errors if the current invocation fails. */
    private var currentInvocation =
        fieldResolverTask.publication.sourceOccurrence as? FieldResolverOccurrence

    /** Validate inside the field-error boundary, before starting any provider-reader work. */
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
                require(resolver.target.field == selection.key.field) {
                    "Resolver field ${resolver.target.field.name} does not match ${selection.key.field.name}"
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
        // Activation itself can fail before the normal no-checker publication. A failed
        // value still needs a terminal null checker result so checked consumers can read its error.
        if (!publication.checkerScheduled) {
            publication.publicationCell.fieldCheckerResult.complete(null)
        }
        publication.publicationCell.value.complete(ErrorEngineResult.of(EngineErrorData.of(cause)))
    }

    suspend fun publishResult() {
        val publication = fieldResolverTask.publication
        val sourceOccurrence = publication.sourceOccurrence
        val selection = sourceOccurrence.selection
        val constructionDemand = sourceOccurrence.publicationConstructionDemand
        val invocationDemand: SelectionForest =
            when (sourceOccurrence) {
                is PassiveValueOccurrence -> sourceOccurrence.invocationDemand
                else -> constructionDemand.successorDemandFromConstructionDemand(
                    publication.operation.world,
                    (sourceOccurrence.publicationExpectedType.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes.orEmpty(),
                )
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
                prepareRootFieldReferenceInvocation(
                    reference = reference,
                    constructionDemand = constructionDemand.values,
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
            publication.operation.passiveValues.resolvePassiveValues(
                value = fieldValue,
                root = publication.oerOccurrence.root,
                expectedType = sourceOccurrence.publicationExpectedType,
                path = sourceOccurrence.publicationPath,
                invocationDemand = invocationDemand,
                constructionDemand = constructionDemand,
                parent = publication.oerOccurrence,
            )

        publication.publicationCell.value.complete(passiveValue)
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
                    is VariableBinding.Input ->
                        binding.value as? Boolean
                            ?: error("Inclusion-condition variable must contain a Boolean")
                }
            }
        check(publication.publicationCell.setActivated(activated)) {
            "Resolution field-task cell activation was already decided"
        }
        if (
            activated &&
            !publication.checkerScheduled &&
            !publication.publicationCell.fieldCheckerResult.isCompleted
        ) {
            check(publication.publicationCell.fieldCheckerResult.complete(null))
        }
        return activated
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

    private suspend fun materializeQueryFragment(fieldResolverOccurrence: FieldResolverOccurrence): EngineObjectData.Sync {
        val publication = fieldResolverTask.publication
        val queryFragment = fieldResolverOccurrence.fragments.queryFragment
        val queryOER = publication.queryOER
            ?: return engineObjectDataOf(publication.operation.world.schema.requireQueryTypeDef())
        check(queryFragment.constructionSelections.isEmpty() || queryOER.isDemanded()) {
            "Nonempty resolver Query fragment has no demanded shared Query OER"
        }
        val materializationSelections =
            fieldResolverOccurrence.resolver
                .instantiateQueryMaterializationSelections(
                    queryFragment.resolverOccurrenceId,
                )
        return queryOER.occurrence.target.materializeResolverInput(
            operation = publication.operation,
            cycleChecker = publication.operation.cycleChecker,
            selections = materializationSelections,
            reader =
                fieldResolverOccurrence.invocationRoot
                    .fieldResolverCycleTask(fieldResolverOccurrence.invocationPath),
            resultPath = emptyList(),
        )
    }

    private fun prepareRootFieldReferenceInvocation(
        reference: RootFieldReferenceData,
        constructionDemand: SelectionForest,
    ): FieldResolverOccurrence {
        val publication = fieldResolverTask.publication
        val invocationRoot = ObjectEngineResult.of(publication.operation.world.schema.requireQueryTypeDef())
        val prefixKeys =
            reference.path.dropLast(1).map { prefixField ->
                ObjectEngineResult.GroundKey.of(prefixField, emptyMap())
            }
        val invocationKey =
            ObjectEngineResult.GroundKey.of(reference.targetField, reference.arguments)
        val invocationPath: List<PathComponent> = prefixKeys + invocationKey
        val resolverOccurrenceId = ResolverOccurrenceId.at(invocationRoot, invocationPath)
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
                            key = invocationKey,
                            possibleTypes = setOf(reference.targetField.containingDef),
                            subselections = constructionDemand,
                        ),
                    ).merge(reference.targetField.containingDef).byKey().getValue(invocationKey),
                invocationRoot = invocationRoot,
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
        val queryProducer = fieldResolverTask.launchIndependentQueryFragmentProducer(fieldResolverOccurrence)
        completeVariablesProviderBindings(fieldResolverOccurrence, arguments)?.let { return it }
        val input = engineObjectDataOf(fieldResolverOccurrence.resolver.target.field.containingDef)
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
                withVariablesProviderResolutionContext(fieldResolverTask) {
                    provider(arguments)
                }
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

    private fun completeVariablesProviderBindingsWithError(fieldResolverOccurrence: FieldResolverOccurrence) {
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
