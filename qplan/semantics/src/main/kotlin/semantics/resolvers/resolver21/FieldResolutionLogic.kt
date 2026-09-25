package semantics.resolvers.resolver21

import model.Arguments
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.ErrorEngineResult
import model.NodeReferenceIdentity
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.groundKey
import model.invariants.conformsToResolverOutputSchemaType
import model.materializeSelectionForestOf
import model.nodeReferenceIdentityOrNull
import model.registry.FieldResolver
import model.registry.ResolverFragment
import model.registry.ResolutionExecutionContext
import semantics.resolvers.emptyObjectInput
import semantics.resolvers.prepareInvocation
import semantics.shared.ResolverInvocationObservation
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.CycleTask
import semantics.shared.fieldResolverCycleTask
import semantics.resolvers.materializeResolverInput
import semantics.shared.withAuthoritativeNodeId
import viaduct.engine.api.EngineObjectData

/** Invokes and publishes one already-installed field resolver or root-field reference. */
internal class FieldResolutionLogic(
    private val fieldResolverTask: CoroutineFieldResolverTask,
) {
    /** Publishes into the cell already activated by task preparation. */
    fun publishFieldError(cause: Exception) {
        val publication = fieldResolverTask.publication
        publication.publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of(cause)))
    }

    suspend fun publishResult() {
        val publication = fieldResolverTask.publication
        val key = publication.selection.groundKey()
        val constructionDemand = publication.constructionDemand
        val invocationDemand = publication.invocationDemand ?: publication.operation.complete(constructionDemand)
        var fieldValue: ResolverOutputData? = publication.reference ?: when (val arguments = key.arguments) {
            Arguments.Error -> {
                check(publication.publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of()))) {
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
        check(publication.publicationCell.getValue().complete(passiveValue)) { "Cell value was completed twice" }
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
        resolver: FieldResolver,
        queryFragment: ResolverFragment,
        reader: CycleTask,
    ): EngineObjectData.Sync {
        val publication = fieldResolverTask.publication
        check(queryFragment.constructionSelections.isEmpty() || publication.queryOER.isDemanded()) {
            "Nonempty resolver Query fragment has no demanded shared Query OER"
        }
        val materializationSelections =
            resolver.instantiateQueryMaterializationSelections(
                queryFragment.resolverOccurrenceId,
            )
        return publication.queryOER.occurrence.target.materializeResolverInput(
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
        val invocation = reference.prepareInvocation(publication.operation)
        val queryProducer =
            fieldResolverTask.launchQueryFragmentProducer(
                invocation.resolver,
                invocation.fragments.queryFragment,
                invocation.root.fieldResolverCycleTask(invocation.path),
            )
        val queryValue = when (val value = queryProducer.await()) {
            is EngineObjectOrErrorData.Success -> value.value
            is EngineObjectOrErrorData.Error -> return value.error
        }
        val input = invocation.emptyObjectInput()
        publication.operation.resolverObserver.onResolverInvocation(
            ResolverInvocationObservation(
                occurrencePath = invocation.path,
                field = invocation.key.field,
                input = input,
                inputSelections = materializeSelectionForestOf(),
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
                invocationRoot = invocation.root,
                invocationPath = invocation.path,
                invocationKey = invocation.key,
                suppliedDemand = invocationDemand,
            ),
        )
        return output
    }
}
