package viaduct.engine.runtime.tenantloading

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeBatchResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcherImpl

class MissingResolverValidatorTest {

    @Test
    fun `passes when all resolver fields have dispatchers`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                greeting: String @resolver
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(
                ("Query" to "greeting") to FieldResolverDispatcherImpl(
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.greeting")
                )
            ),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        assertDoesNotThrow {
            validator.validate(MissingResolverValidationCtx(registry))
        }
    }

    @Test
    fun `throws when field resolver is missing`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                greeting: String @resolver
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        val exception = assertThrows<MissingResolversException> {
            validator.validate(MissingResolverValidationCtx(registry))
        }
        assertEquals(listOf("Query.greeting"), exception.missingFieldResolvers)
        assertTrue(exception.missingNodeResolvers.isEmpty())
    }

    @Test
    fun `throws when node resolver is missing`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                user: User
            }
            type User @resolver {
                id: ID!
                name: String
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        val exception = assertThrows<MissingResolversException> {
            validator.validate(MissingResolverValidationCtx(registry))
        }
        assertEquals(listOf("User"), exception.missingNodeResolvers)
        assertTrue(exception.missingFieldResolvers.isEmpty())
    }

    @Test
    fun `reports both missing field and node resolvers`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                greeting: String @resolver
                user: User
            }
            type User @resolver {
                id: ID!
                name: String
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        val exception = assertThrows<MissingResolversException> {
            validator.validate(MissingResolverValidationCtx(registry))
        }
        assertEquals(listOf("Query.greeting"), exception.missingFieldResolvers)
        assertEquals(listOf("User"), exception.missingNodeResolvers)
    }

    @Test
    fun `skips introspection types`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                greeting: String @resolver
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(
                ("Query" to "greeting") to FieldResolverDispatcherImpl(
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.greeting")
                )
            ),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        assertDoesNotThrow {
            validator.validate(MissingResolverValidationCtx(registry))
        }
    }

    @Test
    fun `passes when node resolver is registered`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                user: User
            }
            type User @resolver {
                id: ID!
                name: String
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = mapOf(
                "User" to NodeResolverDispatcherImpl(MockNodeBatchResolverExecutor("User"))
            ),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        assertDoesNotThrow {
            validator.validate(MissingResolverValidationCtx(registry))
        }
    }

    @Test
    fun `fields without resolver directive are not checked`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                greeting: String @resolver
                plainField: Int
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(
                ("Query" to "greeting") to FieldResolverDispatcherImpl(
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.greeting")
                )
            ),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        assertDoesNotThrow {
            validator.validate(MissingResolverValidationCtx(registry))
        }
    }

    @Test
    fun `exception message includes sorted field and type names`() {
        val schema = MockSchema.mk(
            """
            extend type Query {
                zebra: String @resolver
                alpha: String @resolver
                user: User
            }
            type User @resolver {
                id: ID!
            }
            """
        )
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = emptyMap(),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        val validator = MissingResolverValidator(schema)

        val exception = assertThrows<MissingResolversException> {
            validator.validate(MissingResolverValidationCtx(registry))
        }
        assertTrue(exception.message!!.contains("Query.alpha, Query.zebra"))
        assertTrue(exception.message!!.contains("User"))
    }
}
