package viaduct.tenant.runtime.execution.policycheck

import graphql.schema.GraphQLAppliedDirective
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.tenant.runtime.execution.policycheck.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class PolicyCheckFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        #START_SCHEMA
        directive @policyCheck(canAccess: Boolean) on FIELD_DEFINITION | OBJECT

        extend type Query @scope(to: ["SCOPE1"]) {
          canAccessField: String @resolver @policyCheck(canAccess: true)
          canNotAccessField: String @resolver @policyCheck(canAccess: false)
          canNotAccessType: CanNotAccessPerson @resolver @policyCheck(canAccess: false)
        }

        type CanAccessPerson implements Node @resolver @scope(to: ["SCOPE1"]) @policyCheck(canAccess: true) {
          id: ID!
          name: String!
          ssn: String!
        }

        type CanNotAccessPerson implements Node @resolver @scope(to: ["SCOPE1"]) @policyCheck(canAccess: false) {
          id: ID!
          name: String!
          ssn: String!
        }
        #END_SCHEMA
    """.trimIndent()

    @Resolver
    class Query_CanAccessFieldResolver : QueryResolvers.CanAccessField() {
        override suspend fun resolve(ctx: Context): String {
            return "can see field"
        }
    }

    @Resolver
    class Query_CanNotAccessFieldResolver : QueryResolvers.CanNotAccessField() {
        override suspend fun resolve(ctx: Context) = "cannot see field"
    }

    @Resolver
    class Query_CanNotAccessTypeResolver : QueryResolvers.CanNotAccessType() {
        override suspend fun resolve(ctx: Context): CanNotAccessPerson {
            return CanNotAccessPerson.Builder(ctx)
                .id(ctx.globalIDFor(CanNotAccessPerson.Reflection, "person2"))
                .name("jane")
                .ssn("social security number")
                .build()
        }
    }

    @Resolver
    class CanAccessPersonNodeResolver : NodeResolvers.CanAccessPerson() {
        override suspend fun resolve(ctx: Context): CanAccessPerson {
            return CanAccessPerson.Builder(ctx)
                .id(ctx.id)
                .name("john")
                .ssn("social security number")
                .build()
        }
    }

    @Resolver
    class CanNotAccessPersonNodeResolver : NodeResolvers.CanNotAccessPerson() {
        override suspend fun resolve(ctx: Context): CanNotAccessPerson {
            return CanNotAccessPerson.Builder(ctx)
                .id(ctx.id)
                .name("jane")
                .ssn("social security number")
                .build()
        }
    }

    // Copy of TestAppCheckerExecutorFactoryImpl for policy check functionality
    private class PolicyCheckExecutorFactory(
        private val schema: ViaductSchema,
    ) : CheckerExecutorFactory {
        private val graphQLSchema = schema.schema

        override fun checkerExecutorForField(
            schema: ViaductSchema,
            typeName: String,
            fieldName: String
        ): CheckerExecutor? {
            val graphqlField = graphQLSchema.getObjectType(typeName)?.getFieldDefinition(fieldName)
                ?: throw IllegalStateException("Cannot find field $fieldName in type $typeName")

            if (!graphqlField.hasAppliedDirective("policyCheck")) {
                return null
            }

            return getCheckerExecutor(
                graphqlField.getAppliedDirective("policyCheck")
            )
        }

        override fun checkerExecutorForType(
            schema: ViaductSchema,
            typeName: String
        ): CheckerExecutor? {
            println("DEBUG: checkerExecutorForType called for type: $typeName")
            val graphqlType = graphQLSchema.getObjectType(typeName)
                ?: throw IllegalStateException("Cannot find type $typeName")

            if (!graphqlType.hasAppliedDirective("policyCheck")) {
                println("DEBUG: No policyCheck directive found for type $typeName")
                return null
            }

            println("DEBUG: Found policyCheck directive for type $typeName, returning executor")
            return getCheckerExecutor(
                graphqlType.getAppliedDirective("policyCheck")
            )
        }

        private fun getCheckerExecutor(policyCheckDirective: GraphQLAppliedDirective): CheckerExecutor {
            val canSee = policyCheckDirective.getArgument("canAccess").getValue() as? Boolean
            return PolicyCheckExecutor(canSee)
        }
    }

    // Copy of TestAppCheckerExecutorImpl for policy check functionality
    private class PolicyCheckExecutor(
        private val canSee: Boolean?,
        override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()
    ) : CheckerExecutor {
        override suspend fun execute(
            arguments: Map<String, Any?>,
            objectDataMap: Map<String, EngineObjectData>,
            context: EngineExecutionContext,
            checkerType: CheckerExecutor.CheckerType
        ): CheckerResult {
            println("DEBUG: PolicyCheckExecutor.execute() called with canSee=$canSee")
            try {
                testExecute(canSee)
            } catch (e: Exception) {
                println("DEBUG: PolicyCheckExecutor throwing exception: ${e.message}")
                return PolicyCheckErrorResult(e)
            }
            println("DEBUG: PolicyCheckExecutor returning Success")
            return CheckerResult.Success
        }

        private fun testExecute(canAccess: Boolean?) {
            when (canAccess) {
                null -> throw RuntimeException("canAccess")
                false -> throw RuntimeException(
                    "This field is not accessible",
                )
                true -> { /* Continue execution - permission granted */ }
            }
        }
    }

    // Copy of TestCheckerErrorResult for policy check functionality
    private class PolicyCheckErrorResult(override val error: Exception) : CheckerResult.Error {
        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = fieldResult
    }

    @BeforeEach
    fun setupPolicyCheck() {
        // Create MockFlagManager that disables executeAccessChecksInModstrat to enable Node-level policy checks
        val mockFlagManager = MockFlagManager() // Empty set = no flags enabled = EXECUTE_ACCESS_CHECKS is disabled

        // Configure the viaduct builder with policy check support
        withViaductBuilder {
            withFlagManager(mockFlagManager)
            builder.withCheckerExecutorFactoryCreator { schema: ViaductSchema -> PolicyCheckExecutorFactory(schema) }
        }
    }

    @Test
    fun `field returns if policy check passes`() {
        execute(
            query = "query { canAccessField }"
        ).assertEquals {
            "data" to { "canAccessField" to "can see field" }
        }
    }

    @Test
    fun `field does not return if policy check fails`() {
        val result = execute(
            query = "query { canNotAccessField }"
        )
        assertEquals(mapOf("canNotAccessField" to null), result.toSpecification()["data"])
        assertEquals(1, result.errors.size)
        val error = result.errors[0]
        assertTrue(error.message.contains("This field is not accessible"))
        assertEquals(listOf("canNotAccessField"), error.path)
    }

    @Test
    fun `type returns if policy check passes on referenced node`() {
        val internalId = "person1"
        val globalId = createGlobalIdString(CanAccessPerson.Reflection, internalId)

        execute(
            query = "query { node(id: \"${globalId}\") { ... on CanAccessPerson { id name ssn } } }"
        ).assertEquals {
            "data" to {
                "node" to {
                    "id" to globalId
                    "name" to "john"
                    "ssn" to "social security number"
                }
            }
        }
    }

    @Test
    fun `throws if type is not accessible on referenced node`() {
        val internalId = "person1"
        val globalId = createGlobalIdString(CanNotAccessPerson.Reflection, internalId)

        val result = execute(
            query = "query { node(id: \"${globalId}\") { ... on CanNotAccessPerson { id name ssn } } }"
        )
        assertEquals(mapOf("node" to null), result.getData())
        assertEquals(1, result.errors.size)
        result.errors.first().let { err ->
            assertEquals(listOf("node"), err.path)
            assertTrue(err.message.contains("This field is not accessible"))
        }
    }

    @Test
    fun `type should return null if policy check fails`() {
        val result = execute(
            query = "query { canNotAccessType { name ssn } }"
        )
        assertEquals(mapOf("canNotAccessType" to null), result.getData())
        assertEquals(1, result.errors.size)
        result.errors.first().let { err ->
            assertEquals(listOf("canNotAccessType"), err.path)
            assertTrue(err.message.contains("This field is not accessible"))
        }
    }
}
