package viaduct.tenant.runtime.context.factory

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.api.mocks.MockResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.tenant.runtime.context.FieldExecutionContextImpl
import viaduct.tenant.runtime.context.ResolverExecutionContextImpl
import viaduct.tenant.runtime.internal.NodeReferenceFactoryImpl

@ExperimentalCoroutinesApi
class MutationFieldExecutionContextFactoryTest {
    private val args = MockArgs()
    private val ec = MockResolverExecutionContext(args.internalContext)
    private val resolverId = "Mutation.mutate"
    private val mutationSelectionsLoaderFactory = Factory { args: SelectionsLoaderArgs ->
        args.selectionsLoaderFactory.forMutation(resolverId)
    }
    private val fieldExecutionContext = FieldExecutionContextImpl(
        ResolverExecutionContextImpl(
            args.internalContext,
            args.selectionsLoaderFactory.forQuery(resolverId),
            MockArgs.selectionSetFactory,
            NodeReferenceFactoryImpl(mockk())
        ),
        Mutation.Builder(ec).build(),
        Query.Builder(ec).build(),
        Mutation_Mutate_Arguments.Builder(ec).x(42).build(),
        SelectionSet.NoSelections,
    )
    private val fieldFactory = FieldExecutionContextFactory { fieldExecutionContext }

    @Test
    fun create() {
        MutationFieldExecutionContextMetaFactory
            .create(fieldFactory, mutationSelectionsLoaderFactory)
            .make(args.getFieldArgs())
    }
}
