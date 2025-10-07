@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.context.factory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.select.SelectionSet

@ExperimentalCoroutinesApi
class SelectionsLoaderFactoryTest {
    @Test
    fun forQuery(): Unit =
        runBlocking {
            MockArgs().let { args ->
                val loader = SelectionsLoaderFactory.forQuery.mk(args.getSelectionsLoaderArgs())
                assertDoesNotThrow {
                    loader.load(
                        MockExecutionContext(args.internalContext),
                        SelectionSet.empty(Query.Reflection)
                    )
                }
            }
        }

    @Test
    fun forMutation(): Unit =
        runBlocking {
            MockArgs().let { args ->
                val loader = SelectionsLoaderFactory.forMutation.mk(args.getSelectionsLoaderArgs())
                assertDoesNotThrow {
                    loader.load(
                        MockExecutionContext(args.internalContext),
                        SelectionSet.empty(Mutation.Reflection)
                    )
                }
            }
        }
}
