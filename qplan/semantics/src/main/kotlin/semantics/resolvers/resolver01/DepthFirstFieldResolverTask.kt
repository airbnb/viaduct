package semantics.resolvers.resolver01

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.ErrorEngineResult
import model.MaterializeSelectionForest
import model.NodeReferenceIdentity
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.groundKey
import model.invariants.conformsToResolverOutputSchemaType
import model.materializeSelectionForestOf
import model.nodeReferenceIdentityOrNull
import model.registry.FieldResolver
import model.registry.ResolverFragment
import model.registry.ResolutionExecutionContext
import model.requireQueryTypeDef
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolvers.emptyObjectInput
import semantics.resolvers.prepareInvocation
import semantics.shared.ResolverInvocationObservation
import semantics.shared.CycleCheckState
import semantics.shared.CycleTask
import semantics.shared.fieldResolverCycleTask
import semantics.shared.SharedFieldResolverTask
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.resolvers.materializeResolverInput
import semantics.shared.withAuthoritativeNodeId
import viaduct.engine.api.EngineObjectData

/**
 * Invokes and publishes one field for Resolver01-03 and Resolver06-08, retaining the grounded
 * publication context supplied to its dispatcher.
 */
internal class DepthFirstFieldResolverTask private constructor(
    override val publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
    override val queryOERDepth: Int,
) : SharedFieldResolverTask<GroundedFieldPublicationOccurrence<DepthFirstOperationContext>>, DepthFirstTask {
    // List-element references sit deeper than the field whose output contains them.
    override val path get() = publication.publicationPath.dropLast(1)

    companion object {
        /** Claims the publication synchronously before either execution or reactor enqueue. */
        fun create(
            publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
            queryOERDepth: Int,
        ): DepthFirstFieldResolverTask {
            require(queryOERDepth >= 0) { "Query-OER depth must be nonnegative" }
            require(publication.selection.key.field.containingDef == publication.oerOccurrence.target.type) {
                "Resolver selection does not belong to its target object"
            }
            // The reactor may freeze the OER before this task runs.
            publication.publicationCell.createValuePromise()
            publication.publicationCell.setActivated(true)
            return DepthFirstFieldResolverTask(publication, queryOERDepth)
        }
    }

    /** Invokes one field, follows reference tails, and publishes its passively resolved output. */
    fun run(): Unit = with(publication) {
        val key = selection.groundKey()
        val invocationDemand = this.invocationDemand ?: operation.complete(selection.subselections)
        var value: ResolverOutputData? = reference ?: when (val arguments = key.arguments) {
            Arguments.Error -> {
                check(publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of()))) {
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
                        queryOER.isDemanded(),
                ) {
                    "Nonempty resolver Query fragment has no demanded shared Query OER"
                }
                val queryValue =
                    queryOER.occurrence.target.materializeInput(
                        queryMaterializationSelections,
                        oerOccurrence.root.fieldResolverCycleTask(publicationPath),
                    )

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
        var nodeIdentity: NodeReferenceIdentity? = null
        while (value is RootFieldReferenceData) {
            val reference = value
            require(reference.conformsToResolverOutputSchemaType(publicationExpectedType)) {
                "Root-field reference does not conform to ${publicationExpectedType}"
            }
            nodeIdentity = nodeIdentity ?: reference.nodeReferenceIdentityOrNull()
            value = resolveRootFieldReference(
                reference, oerOccurrence.root, publicationPath, invocationDemand,
            )
        }
        val result = operation.passiveValues(queryOERDepth).resolvePassiveValues(
            value = value.withAuthoritativeNodeId(nodeIdentity, invocationDemand),
            root = oerOccurrence.root,
            expectedType = publicationExpectedType,
            path = publicationPath,
            constructionDemand = constructionDemand,
            invocationDemand = invocationDemand,
            parent = oerOccurrence,
        )
        check(publicationCell.getValue().complete(result)) { "Cell value was completed twice" }
    }

    /** Invokes one independently rooted reference target using this resolver's Query-fragment policy. */
    private fun resolveRootFieldReference(
        reference: RootFieldReferenceData,
        publicationRoot: ObjectEngineResult,
        publicationPath: List<PathComponent>,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val operation = publication.operation
        val invocation = reference.prepareInvocation(operation)
        val queryValue =
            resolveIndependentQueryFragment(
                resolver = invocation.resolver,
                queryFragment = invocation.fragments.queryFragment,
                coordinate = invocation.path,
            )
        val input = invocation.emptyObjectInput()
        val output =
            runBlocking {
                // Reference targets have the same interruptible coroutine-entry boundary.
                operation.resolverObserver.onResolverInvocation(
                    ResolverInvocationObservation(
                        occurrencePath = invocation.path,
                        field = invocation.key.field,
                        input = input,
                        inputSelections = materializeSelectionForestOf(),
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
                invocationRoot = invocation.root,
                invocationPath = invocation.path,
                invocationKey = invocation.key,
                suppliedDemand = invocationDemand,
            ),
        )
        return output
    }

    /** Resolves a fresh Query root for an independently rooted reference-target invocation. */
    private fun resolveIndependentQueryFragment(
        resolver: FieldResolver,
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
    ): EngineObjectData.Sync = runBlocking {
        materializeResolverInput(
            operation = publication.operation,
            cycleChecker = CycleCheckState.createNOP(),
            selections = selections,
            reader = reader,
        )
    }
}
