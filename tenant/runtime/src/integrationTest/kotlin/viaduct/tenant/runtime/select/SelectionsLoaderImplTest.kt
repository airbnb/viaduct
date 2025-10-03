@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.select

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Query as QueryType
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RawSelectionsLoader

class SelectionsLoaderImplTest {
    private val context = MockInternalContext(SelectTestFeatureAppTest.schema).executionContext

    @Test
    fun `loads empty selections`(): Unit =
        runBlocking {
            val rawSelectionsLoader = mockk<RawSelectionsLoader> {
                coEvery { load(any()) } returns mockk()
            }
            SelectionsLoaderImpl<QueryType>(rawSelectionsLoader)
                .load(context, SelectionSet.empty(Query.Reflection))
        }

    @Test
    fun `loads empty selections 2`(): Unit =
        runBlocking {
            val rawSelectionsLoader = mockk<RawSelectionsLoader> {
                coEvery { load(any()) } returns mockk()
            }
            SelectionsLoaderImpl<Query>(rawSelectionsLoader)
                .load(context, SelectionSetImpl(Query.Reflection, RawSelectionSet.empty(Query.Reflection.name)))
        }

    @Test
    fun `loads simple selections`(): Unit =
        runBlocking {
            val rawSelectionsLoader = mockk<RawSelectionsLoader> {
                coEvery { load(any()) } returns mockk {
                    every { graphQLObjectType } returns SelectTestFeatureAppTest.schema.schema.getObjectType(Query.Reflection.name)
                    coEvery { fetch(Query.Reflection.Fields.intField.name) } returns 42
                }
            }
            val loaded = SelectionsLoaderImpl<Query>(rawSelectionsLoader)
                .load(
                    context,
                    SelectionSetImpl(
                        Query.Reflection,
                        mockk()
                    )
                )
            assertEquals(42, loaded.getIntField())
        }

    private val rawSSLoaderFactory = mockk<RawSelectionsLoader.Factory> {
        every { forQuery(any()) } returns mockk<RawSelectionsLoader>()
        every { forMutation(any()) } returns mockk<RawSelectionsLoader>()
    }

    @Test
    fun `Factory -- forQuery`() {
        val factory = SelectionsLoaderImpl.Factory(rawSSLoaderFactory)
        assertDoesNotThrow {
            factory.forQuery("myResolverId")
        }
    }

    @Test
    fun `Factory -- forMutation`() {
        val factory = SelectionsLoaderImpl.Factory(rawSSLoaderFactory)
        assertDoesNotThrow {
            factory.forMutation("myResolverId")
        }
    }
}
