@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver01

import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.NodeReferenceIdentity
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.PathComponent
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
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.CycleTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.RootFieldReferenceInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedFieldResolverTask
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.withAuthoritativeNodeId
import viaduct.engine.runtime2.resolvers.GroundedFieldPublicationOccurrence
import viaduct.engine.runtime2.resolvers.emptyObjectInput
import viaduct.engine.runtime2.resolvers.materializeResolverInput
import viaduct.engine.runtime2.resolvers.prepareRootFieldReferenceInvocation

/**
 * Invokes and publishes one field for Resolver01-03 and Resolver06-08, retaining the grounded
 * publication context supplied to its dispatcher.
 */
internal class DepthFirstFieldResolverTask private constructor(
    override val publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
    override val queryOERDepth: Int,
    private val activateOnDispatch: Boolean,
) : SharedFieldResolverTask<GroundedFieldPublicationOccurrence<DepthFirstOperationContext>>, DepthFirstTask {
    // List-element references sit deeper than the field whose output contains them.
    override val path get() = publication.publicationPath.dropLast(1)

    companion object {
        fun prepareMutation(
            operation: DepthFirstOperationContext,
            occurrence: OEROccurrence,
            selection: ObjectSelection,
        ): DepthFirstFieldResolverTask =
            prepare(
                GroundedFieldPublicationOccurrence(operation, occurrence, selection, occurrence.target.getCell(selection.key)),
                queryOERDepth = 0,
                activateOnDispatch = true,
            )

        /** Claims the publication synchronously before either execution or reactor enqueue. */
        fun prepare(
            publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
            queryOERDepth: Int,
            activateOnDispatch: Boolean = false,
        ): DepthFirstFieldResolverTask {
            require(queryOERDepth >= 0) { "Query-OER depth must be nonnegative" }
            require(publication.selection.key.field.containingDef == publication.oerOccurrence.target.type) {
                "Resolver selection does not belong to its target object"
            }
            // The reactor may freeze the OER before this task runs.
            publication.publicationCell.value.claim()
            if (!activateOnDispatch) activatePublication(publication)
            return DepthFirstFieldResolverTask(publication, queryOERDepth, activateOnDispatch)
        }

        private fun activatePublication(publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>) {
            publication.publicationCell.setActivated(true)
            if (!publication.publicationCell.fieldCheckerResult.isCompleted) {
                check(publication.publicationCell.fieldCheckerResult.complete(null)) {
                    "Field-checker result was completed twice"
                }
            }
        }
    }

    /** Invokes one field, follows reference tails, and publishes its passively resolved output. */
    fun run(): Unit =
        with(publication) {
            if (activateOnDispatch) activatePublication(publication)
            val key = selection.groundKey()
            val invocationDemand = this.invocationDemand ?: operation.complete(selection.subselections)
            var fieldValue: ResolverOutputData? = reference ?: when (val arguments = key.arguments) {
                Arguments.Error -> {
                    check(publicationCell.value.complete(ErrorEngineResult.of(EngineErrorData.of()))) {
                        "Cell value was completed twice"
                    }
                    return@with
                }
                is Arguments.Resolved -> {
                    val resolver = operation.world.resolverRegistry.resolver(key.field)
                    val fragments = resolver.instantiateFragmentsAt(oerOccurrence.root, publicationPath)

                    val queryMaterializationSelections =
                        resolver.instantiateQueryMaterializationSelections(
                            fragments.queryFragment.resolverOccurrenceId,
                        )
                    check(
                        fragments.queryFragment.constructionSelections.isEmpty() ||
                            queryOER?.isDemanded() == true,
                    ) {
                        "Nonempty resolver Query fragment has no demanded shared Query OER"
                    }
                    val queryValue =
                        if (queryOER == null) {
                            engineObjectDataOf(operation.world.schema.requireQueryTypeDef())
                        } else {
                            queryOER.occurrence.target.materializeInput(
                                queryMaterializationSelections,
                                oerOccurrence.root.fieldResolverCycleTask(publicationPath),
                            )
                        }

                    val objectMaterializationSelections =
                        resolver.instantiateObjectMaterializationSelections(
                            fragments.objectFragment.resolverOccurrenceId,
                        )
                    val input = // Sibling dependency order and depth-first dispatch make this input ready.
                        oerOccurrence.target.materializeInput(
                            objectMaterializationSelections,
                            oerOccurrence.root.fieldResolverCycleTask(publicationPath),
                        )
                    runBlocking {
                        // Coroutine entry is interruptible; record only after crossing that boundary.
                        operation.resolverObserver.onResolverInvocation(
                            ResolverInvocationObservation(
                                occurrencePath = publicationPath,
                                field = key.field,
                                input = input,
                                inputSelections = objectMaterializationSelections,
                                queryValue = queryValue,
                                queryInputSelections = queryMaterializationSelections,
                                arguments = arguments,
                                suppliedDemand = invocationDemand.takeIf { operation.world.selectiveResolvers },
                                resolverOccurrenceId = fragments.objectFragment.resolverOccurrenceId,
                            ),
                        )
                        resolver(
                            input = input,
                            queryValue = queryValue,
                            arguments = arguments,
                            selections = invocationDemand,
                            selectiveResolvers = operation.world.selectiveResolvers,
                            executionContext = ResolutionExecutionContext.Unsupported,
                        )
                    }
                }
            }
            var authoritativeNodeIdentity: NodeReferenceIdentity? = null
            while (fieldValue is RootFieldReferenceData) {
                val reference = fieldValue
                require(reference.conformsToResolverOutputSchemaType(publicationExpectedType)) {
                    "Root-field reference does not conform to $publicationExpectedType"
                }
                authoritativeNodeIdentity = authoritativeNodeIdentity ?: reference.nodeReferenceIdentityOrNull()
                fieldValue = invokeRootFieldResolver(
                    reference,
                    oerOccurrence.root,
                    publicationPath,
                    invocationDemand,
                )
            }
            val passiveValue = operation.passiveValues(queryOERDepth).resolvePassiveValues(
                value = fieldValue.withAuthoritativeNodeId(authoritativeNodeIdentity, invocationDemand),
                root = oerOccurrence.root,
                expectedType = publicationExpectedType,
                path = publicationPath,
                constructionDemand = constructionDemand,
                invocationDemand = invocationDemand,
                parent = oerOccurrence,
            )
            check(publicationCell.value.complete(passiveValue)) { "Cell value was completed twice" }
        }

    /** Invokes one independently rooted reference target using this resolver's Query-fragment policy. */
    private fun invokeRootFieldResolver(
        reference: RootFieldReferenceData,
        publicationRoot: ObjectEngineResult,
        publicationPath: List<PathComponent>,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val operation = publication.operation
        val invocation = reference.prepareRootFieldReferenceInvocation(operation)
        val queryValue =
            produceAndMaterializeIndependentQueryFragment(
                resolver = invocation.resolver,
                queryFragment = invocation.fragments.queryFragment,
                coordinate = invocation.invocationPath,
            )
        val input = invocation.emptyObjectInput()
        val queryMaterializationSelections =
            invocation.resolver.instantiateQueryMaterializationSelections(
                invocation.fragments.queryFragment.resolverOccurrenceId,
            )
        val output =
            runBlocking {
                // Reference targets have the same interruptible coroutine-entry boundary.
                operation.resolverObserver.onResolverInvocation(
                    ResolverInvocationObservation(
                        occurrencePath = invocation.invocationPath,
                        field = invocation.invocationKey.field,
                        input = input,
                        inputSelections = materializeSelectionForestOf(),
                        queryValue = queryValue,
                        queryInputSelections = queryMaterializationSelections,
                        arguments = reference.arguments,
                        suppliedDemand = invocationDemand.takeIf { operation.world.selectiveResolvers },
                        resolverOccurrenceId = invocation.fragments.objectFragment.resolverOccurrenceId,
                    ),
                )
                invocation.resolver(
                    input = input,
                    queryValue = queryValue,
                    arguments = reference.arguments,
                    selections = invocationDemand,
                    selectiveResolvers = operation.world.selectiveResolvers,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }
        operation.resolverObserver.onRootFieldReferenceInvocation(
            RootFieldReferenceInvocationObservation(
                publicationRoot = publicationRoot,
                publicationPath = publicationPath,
                reference = reference,
                invocationRoot = invocation.invocationRoot,
                invocationPath = invocation.invocationPath,
                invocationKey = invocation.invocationKey,
                suppliedDemand = invocationDemand,
            ),
        )
        return output
    }

    /** Resolves a fresh Query root for an independently rooted reference-target invocation. */
    private fun produceAndMaterializeIndependentQueryFragment(
        resolver: FieldValueResolver,
        queryFragment: ResolverFragment,
        coordinate: List<PathComponent>,
    ): EngineObjectData.Sync {
        val operation = publication.operation
        if (queryFragment.constructionSelections.isEmpty()) {
            return engineObjectDataOf(operation.world.schema.requireQueryTypeDef())
        }
        val queryResult =
            DepthFirstResolve(operation, operation.complete)
                .resolve(queryFragment.constructionSelections, queryFragment.resolverOccurrenceId)
        return queryResult.materializeInput(
            resolver.instantiateQueryMaterializationSelections(
                queryFragment.resolverOccurrenceId,
            ),
            publication.oerOccurrence.root.fieldResolverCycleTask(coordinate),
        )
    }

    /** Materializes one already-prepared depth-first input through the shared suspend API. */
    private fun ObjectEngineResult.materializeInput(
        selections: MaterializeSelectionForest,
        reader: CycleTask,
    ): EngineObjectData.Sync =
        runBlocking {
            materializeResolverInput(
                operation = publication.operation,
                cycleChecker = CycleCheckState.createNOP(),
                selections = selections,
                reader = reader,
            )
        }
}
