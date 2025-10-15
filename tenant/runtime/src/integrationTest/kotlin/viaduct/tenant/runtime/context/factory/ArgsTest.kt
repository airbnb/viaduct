package viaduct.tenant.runtime.context.factory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class ArgsTest {
    @Test
    fun `FieldArgs -- invoke`() {
        val exp = MockArgs()
        FieldArgs(
            internalContext = exp.internalContext,
            arguments = exp.arguments,
            objectValue = exp.getFieldArgs().objectValue,
            queryValue = exp.getFieldArgs().queryValue,
            resolverId = exp.resolverId,
            selectionSetFactory = MockArgs.selectionSetFactory,
            selections = exp.selections,
            selectionsLoaderFactory = exp.selectionsLoaderFactory,
            engineExecutionContext = exp.engineExecutionContext,
        ).let {
            assertSame(exp.internalContext, it.internalContext)
            assertSame(exp.arguments, it.arguments)
            assertSame(exp.getFieldArgs().objectValue, it.objectValue)
            assertSame(exp.getFieldArgs().queryValue, it.queryValue)
            assertSame(exp.resolverId, it.resolverId)
            assertSame(MockArgs.selectionSetFactory, it.selectionSetFactory)
            assertSame(exp.selections, it.selections)
            assertSame(exp.selectionsLoaderFactory, it.selectionsLoaderFactory)
            assertSame(exp.engineExecutionContext, it.engineExecutionContext)
        }
    }
}
