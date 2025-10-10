package viaduct.engine

import graphql.execution.MergedField
import graphql.language.Field
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.FieldWiringEnvironment
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.DefaultCoroutineInterop

class ViaductModernWiringTest {
    @Test
    fun `it uses property data fetcher to fetch a field`() {
        val wiring = ViaductWiringFactory(DefaultCoroutineInterop)
        val fieldWiringEnv = mockk<FieldWiringEnvironment>()
        val dataFetcher = wiring.getDefaultDataFetcher(fieldWiringEnv)
        val result = dataFetcher.get(
            DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .mergedField(
                    MergedField.newMergedField().fields(
                        listOf(
                            Field.newField().name("foo").build()
                        )
                    ).build()
                )
                .fieldDefinition(
                    GraphQLFieldDefinition.newFieldDefinition()
                        .name("foo")
                        .type(
                            GraphQLObjectType.newObject().name("FooType")
                        )
                        .build()
                )
                .source(
                    mapOf("foo" to "fooResult")
                )
                .build()
        )

        assertEquals("fooResult", result)
    }

    @Test
    fun `it returns null for a null field`() {
        val wiring = ViaductWiringFactory(DefaultCoroutineInterop)
        val fieldWiringEnv = mockk<FieldWiringEnvironment>()
        val dataFetcher = wiring.getDefaultDataFetcher(fieldWiringEnv)
        val result = dataFetcher.get(
            DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .mergedField(
                    MergedField.newMergedField().fields(
                        listOf(
                            Field.newField().name("foo").build()
                        )
                    ).build()
                )
                .fieldDefinition(
                    GraphQLFieldDefinition.newFieldDefinition()
                        .name("foo")
                        .type(
                            GraphQLObjectType.newObject().name("FooType")
                        )
                        .build()
                )
                .source(
                    mapOf("notFoo" to "fooResult")
                )
                .build()
        )

        assertEquals(null, result)
    }

    @Test
    fun `it builds a RuntimeWiring`() {
        val runtimeWiring = ViaductWiringFactory.buildRuntimeWiring(DefaultCoroutineInterop)
        assertTrue(runtimeWiring.wiringFactory is ViaductWiringFactory)
    }
}
