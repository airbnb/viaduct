package viaduct.engine.runtime2.resolvers.resolver21

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineObjectOrErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.NodeReferenceIdentity
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.groundKey
import viaduct.engine.runtime2.model.invariants.conformsToResolverOutputSchemaType
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverFragment
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.resolution.framework.CycleTask
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.RootFieldReferenceInvocationObservation
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.withAuthoritativeNodeId
import viaduct.engine.runtime2.resolvers.emptyObjectInput
import viaduct.engine.runtime2.resolvers.materializeResolverInput
import viaduct.engine.runtime2.resolvers.prepareRootFieldReferenceInvocation
import viaduct.graphql.schema.ViaductSchema

/** Invokes and publishes one already-installed field resolver or root-field reference. */
internal class FieldResolutionLogic(
    private val fieldResolverTask: CoroutineFieldResolverTask,
) {
    /** Validate inside the field-error boundary, before starting any invocation or Query producer. */
    fun validate() {
        val publication = fieldResolverTask.publication
        val key = publication.selection.key
        require(key.field.containingDef == publication.oerOccurrence.target.type) {
            "Resolver selection does not belong to its target occurrence"
        }
        if (publication.publicationPath.lastOrNull() is ObjectEngineResult.ObjectKey) {
            require(publication.oerOccurrence.target.getCell(key) === publication.publicationCell) {
                "Resolver cell does not belong to its target occurrence and selection"
            }
        }
        publication.reference?.let { reference ->
            require(reference.targetField in publication.operation.world.resolverRegistry) {
                "Root-field-reference target has no resolver"
            }
        }
    }

    /** Publishes into the cell already activated by task preparation. */
    fun publishFieldError(cause: Exception) {
        val publication = fieldResolverTask.publication
        publication.publicationCell.value.complete(ErrorEngineResult.of(EngineErrorData.of(cause)))
    }

    suspend fun publishResult() {
        val publication = fieldResolverTask.publication
        val key = publication.selection.groundKey()
        val constructionDemand = publication.constructionDemand
        val possibleRootTypes =
            (publication.publicationExpectedType.baseTypeDef as? ViaductSchema.CompositeTypeDef)
                ?.possibleObjectTypes
                .orEmpty()
        val invocationDemand =
            publication.invocationDemand
                ?: publication.operation.complete(constructionDemand, possibleRootTypes)
        var fieldValue: ResolverOutputData? = publication.reference ?: when (val arguments = key.arguments) {
            Arguments.Error -> {
                check(publication.publicationCell.value.complete(ErrorEngineResult.of(EngineErrorData.of()))) {
                    "Cell value was completed twice"
                }
                return
            }
            is Arguments.Resolved -> runFieldResolver(arguments, invocationDemand)
        }
        var authoritativeNodeIdentity: NodeReferenceIdentity? = null
        while (fieldValue is RootFieldReferenceData) {
            val reference = fieldValue
            authoritativeNodeIdentity = authoritativeNodeIdentity ?: reference.nodeReferenceIdentityOrNull()
            require(reference.conformsToResolverOutputSchemaType(publication.publicationExpectedType)) {
                "Root-field reference does not conform to ${publication.publicationExpectedType}"
            }
            fieldValue = invokeRootFieldResolver(reference, invocationDemand)
        }
        fieldValue = fieldValue.withAuthoritativeNodeId(authoritativeNodeIdentity, invocationDemand)
        val passiveValue = publication.operation.passiveValues.resolvePassiveValues(
            value = fieldValue,
            root = publication.oerOccurrence.root,
            expectedType = publication.publicationExpectedType,
            path = publication.publicationPath,
            constructionDemand = constructionDemand,
            invocationDemand = invocationDemand,
            parent = publication.oerOccurrence,
        )
        check(publication.publicationCell.value.complete(passiveValue)) { "Cell value was completed twice" }
    }

    private suspend fun runFieldResolver(
        arguments: Arguments.Resolved,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val publication = fieldResolverTask.publication
        val resolver = publication.operation.world.resolverRegistry.resolver(publication.selection.key.field)
        val fragments = resolver.instantiateFragmentsAt(publication.oerOccurrence.root, publication.publicationPath)
        val reader = publication.oerOccurrence.root.fieldResolverCycleTask(publication.publicationPath)
        val queryValue =
            materializeQueryFragment(
                resolver,
                fragments.queryFragment,
                reader,
            )
        val queryMaterializationSelections =
            resolver.instantiateQueryMaterializationSelections(
                fragments.queryFragment.resolverOccurrenceId,
            )
        val objectMaterializationSelections =
            resolver.instantiateObjectMaterializationSelections(
                fragments.objectFragment.resolverOccurrenceId,
            )
        val input = publication.oerOccurrence.target.materializeResolverInput(
            operation = publication.operation,
            cycleChecker = publication.operation.cycleChecker,
            selections = objectMaterializationSelections,
            reader = reader,
        )
        publication.operation.resolverObserver.onResolverInvocation(
            ResolverInvocationObservation(
                occurrencePath = publication.publicationPath,
                field = publication.selection.key.field,
                input = input,
                inputSelections = objectMaterializationSelections,
                queryValue = queryValue,
                queryInputSelections = queryMaterializationSelections,
                arguments = arguments,
                suppliedDemand = invocationDemand.takeIf { publication.operation.world.selectiveResolvers },
                resolverOccurrenceId = fragments.objectFragment.resolverOccurrenceId,
            ),
        )
        return resolver(
            input = input,
            queryValue = queryValue,
            arguments = arguments,
            selections = invocationDemand,
            selectiveResolvers = publication.operation.world.selectiveResolvers,
            executionContext = ResolutionExecutionContext.Unsupported,
        )
    }

    private suspend fun materializeQueryFragment(
        resolver: FieldValueResolver,
        queryFragment: ResolverFragment,
        reader: CycleTask,
    ): EngineObjectData.Sync {
        val publication = fieldResolverTask.publication
        val queryOER = publication.queryOER
            ?: return engineObjectDataOf(publication.operation.world.schema.requireQueryTypeDef())
        check(queryFragment.constructionSelections.isEmpty() || queryOER.isDemanded()) {
            "Nonempty resolver Query fragment has no demanded shared Query OER"
        }
        val materializationSelections =
            resolver.instantiateQueryMaterializationSelections(
                queryFragment.resolverOccurrenceId,
            )
        return queryOER.occurrence.target.materializeResolverInput(
            operation = publication.operation,
            cycleChecker = publication.operation.cycleChecker,
            selections = materializationSelections,
            reader = reader,
        )
    }

    private suspend fun invokeRootFieldResolver(
        reference: RootFieldReferenceData,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val publication = fieldResolverTask.publication
        val invocation = reference.prepareRootFieldReferenceInvocation(publication.operation)
        val queryProducer =
            fieldResolverTask.launchIndependentQueryFragmentProducer(
                invocation.resolver,
                invocation.fragments.queryFragment,
                invocation.invocationRoot.fieldResolverCycleTask(invocation.invocationPath),
            )
        val queryValue = when (val value = queryProducer.await()) {
            is EngineObjectOrErrorData.Success -> value.value
            is EngineObjectOrErrorData.Error -> return value.error
        }
        val input = invocation.emptyObjectInput()
        val queryMaterializationSelections =
            invocation.resolver.instantiateQueryMaterializationSelections(
                invocation.fragments.queryFragment.resolverOccurrenceId,
            )
        publication.operation.resolverObserver.onResolverInvocation(
            ResolverInvocationObservation(
                occurrencePath = invocation.invocationPath,
                field = invocation.invocationKey.field,
                input = input,
                inputSelections = materializeSelectionForestOf(),
                queryValue = queryValue,
                queryInputSelections = queryMaterializationSelections,
                arguments = reference.arguments,
                suppliedDemand = invocationDemand.takeIf { publication.operation.world.selectiveResolvers },
                resolverOccurrenceId = invocation.fragments.objectFragment.resolverOccurrenceId,
            ),
        )
        val output =
            invocation.resolver(
                input = input,
                queryValue = queryValue,
                arguments = reference.arguments,
                selections = invocationDemand,
                selectiveResolvers = publication.operation.world.selectiveResolvers,
                executionContext = ResolutionExecutionContext.Unsupported,
            )
        publication.operation.resolverObserver.onRootFieldReferenceInvocation(
            RootFieldReferenceInvocationObservation(
                publicationRoot = publication.oerOccurrence.root,
                publicationPath = publication.publicationPath,
                reference = reference,
                invocationRoot = invocation.invocationRoot,
                invocationPath = invocation.invocationPath,
                invocationKey = invocation.invocationKey,
                suppliedDemand = invocationDemand,
            ),
        )
        return output
    }
}
