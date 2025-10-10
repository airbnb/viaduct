package viaduct.engine

import graphql.TypeResolutionEnvironment
import graphql.language.Field
import graphql.language.InterfaceTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.InterfaceWiringEnvironment
import graphql.schema.idl.UnionWiringEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime.execution.DefaultCoroutineInterop

class ViaductWiringFactoryTest {
    @Test
    fun `test is enabled`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        assertTrue(subject.providesTypeResolver(mockk<UnionWiringEnvironment>()))
        assertTrue(subject.providesTypeResolver(mockk<InterfaceWiringEnvironment>()))
        assertNotNull(ViaductWiringFactory.buildRuntimeWiring(DefaultCoroutineInterop))
    }

    @Test
    fun `test default data fetcher fetches properties`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val defaultDataFetcher = subject.getDefaultDataFetcher(mockk<FieldWiringEnvironment>())

        val dataFetchingEnvironment = mockk<DataFetchingEnvironment> {
            every { field } returns mockk<Field> {
                every { name } returns "foo"
            }
            every { fieldType } returns mockk<GraphQLOutputType>()
            every { getSource<Any>() } returns mapOf("foo" to "bar")
        }
        assertEquals("bar", defaultDataFetcher.get(dataFetchingEnvironment))
    }

    @Test
    fun `type resolvers for interfaces return ResolvedEngineObjectData object types`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val expectedObjectType = mockk<GraphQLObjectType>()
        val interfaceResolver = subject.getTypeResolver(mockk<InterfaceWiringEnvironment>())
        val actualObjectType = interfaceResolver.getType(
            mockk<TypeResolutionEnvironment> {
                every { getObject<Any>() } returns mockk<ResolvedEngineObjectData> {
                    every { graphQLObjectType } returns expectedObjectType
                }
            }
        )

        assertEquals(expectedObjectType, actualObjectType)
    }

    @Test
    fun `type resolvers for unions return ResolvedEngineObjectData object types`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val expectedObjectType = mockk<GraphQLObjectType>()
        val interfaceResolver = subject.getTypeResolver(mockk<UnionWiringEnvironment>())
        val actualObjectType = interfaceResolver.getType(
            mockk<TypeResolutionEnvironment> {
                every { getObject<Any>() } returns mockk<ResolvedEngineObjectData> {
                    every { graphQLObjectType } returns expectedObjectType
                }
            }
        )

        assertEquals(expectedObjectType, actualObjectType)
    }

    @Test
    fun `type resolvers that do not return EngineObjectData throw`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val interfaceResolver = subject.getTypeResolver(
            mockk<InterfaceWiringEnvironment> {
                every { interfaceTypeDefinition } returns mockk<InterfaceTypeDefinition> {
                    every { name } returns "InterfaceName"
                }
            }
        )

        assertThrows<IllegalStateException> {
            interfaceResolver.getType(
                mockk<TypeResolutionEnvironment> {
                    every { getObject<Any>() } returns mockk<Any>()
                }
            )
        }

        val unionResolver = subject.getTypeResolver(
            mockk<UnionWiringEnvironment> {
                every { unionTypeDefinition } returns mockk<UnionTypeDefinition> {
                    every { name } returns "UnionName"
                }
            }
        )

        assertThrows<IllegalStateException> {
            unionResolver.getType(
                mockk<TypeResolutionEnvironment> {
                    every { getObject<Any>() } returns mockk<Any>()
                }
            )
        }
    }
}
