package viaduct.engine.runtime.execution

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.EngineExecutionContextImpl

/**
 * Test harness for subquery execution via ExecutionHandle.
 *
 * This test suite validates the ctx.query() and ctx.mutation() APIs that enable
 * resolvers to execute subqueries against the same GraphQL schema without rebuilding
 * GraphQL-Java state.
 *
 * ## Architecture
 *
 * The subquery execution flow uses ExecutionHandle to maintain execution context:
 * 1. Resolver calls ctx.query(resolverId, selections)
 * 2. ExecutionHandle provides access to ExecutionParameters (opaque to tenant code)
 * 3. Engine builds QueryPlan from RawSelectionSet
 * 4. Engine resolves fields with proper access checks and variable scoping
 * 5. Returns EngineObjectData with resolved fields
 *
 * ## Test Coverage
 *
 * - Basic ctx.query() from nested resolvers
 * - Basic ctx.mutation() from nested resolvers
 * - Variable scoping in subqueries
 * - Error handling and propagation
 * - Access check execution during subqueries
 */
class SubqueryExecutionTest {
    @Test
    fun `ctx query executes subquery against Query root`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                rootValue: Int
                container: Container
            }

            type Container {
                derivedFromQuery: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 42)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "derivedFromQuery") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Create RawSelectionSet to fetch from Query root
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "rootValue", emptyMap())

                        // Execute subquery via ctx.query()
                        val queryResult = ctx.query(
                            resolverId = "Container.derivedFromQuery",
                            selectionSet = rss
                        )

                        // Access resolved field and derive value
                        val rootValue = queryResult.fetchAs<Int>("rootValue")
                        rootValue * 2
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { derivedFromQuery } }")
                .assertJson("""{"data": {"container": {"derivedFromQuery": 84}}}""")
        }
    }

    @Test
    fun `ctx query accesses multiple Query fields`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                firstName: String
                lastName: String
                user: User
            }

            type User {
                fullName: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "firstName", "Alice")
            fieldWithValue("Query" to "lastName", "Smith")

            field("Query" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("User"),
                            mapOf()
                        )
                    }
                }
            }

            field("User" to "fullName") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Fetch multiple fields from Query root in one subquery
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "firstName lastName", emptyMap())

                        val queryResult = ctx.query(
                            resolverId = "User.fullName",
                            selectionSet = rss
                        )

                        val first = queryResult.fetchAs<String>("firstName")
                        val last = queryResult.fetchAs<String>("lastName")
                        "$first $last"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ user { fullName } }")
                .assertJson("""{"data": {"user": {"fullName": "Alice Smith"}}}""")
        }
    }

    @Test
    fun `ctx query with field arguments`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                multiply(n: Int!): Int
                calculator: Calculator
            }

            type Calculator {
                double(input: Int!): Int
            }
            """.trimIndent()
        ) {
            field("Query" to "multiply") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("n") * 2
                    }
                }
            }

            field("Query" to "calculator") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Calculator"),
                            mapOf()
                        )
                    }
                }
            }

            field("Calculator" to "double") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        val input = args.getAs<Int>("input")

                        // Execute subquery with field arguments
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "multiply(n: $input)", emptyMap())

                        val queryResult = ctx.query(
                            resolverId = "Calculator.double",
                            selectionSet = rss
                        )

                        queryResult.fetchAs<Int>("multiply")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ calculator { double(input: 21) } }")
                .assertJson("""{"data": {"calculator": {"double": 42}}}""")
        }
    }

    @Test
    fun `ctx query with GraphQL variables`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                multiply(n: Int!): Int
                calculator: Calculator
            }

            type Calculator {
                double(input: Int!): Int
            }
            """.trimIndent()
        ) {
            field("Query" to "multiply") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("n") * 2
                    }
                }
            }

            field("Query" to "calculator") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Calculator"),
                            mapOf()
                        )
                    }
                }
            }

            field("Calculator" to "double") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        val input = args.getAs<Int>("input")

                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "multiply(n: \$myVar)", mapOf("myVar" to input))

                        val queryResult = ctx.query(
                            resolverId = "Calculator.double",
                            selectionSet = rss
                        )

                        queryResult.fetchAs<Int>("multiply")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ calculator { double(input: 21) } }")
                .assertJson("""{"data": {"calculator": {"double": 42}}}""")
        }
    }

    @Test
    fun `ctx mutation with GraphQL variables`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            extend type Mutation {
                addToCounter(amount: Int!): Int
            }

            type Container {
                addAmount(value: Int!): Int
            }
            """.trimIndent()
        ) {
            var counter = 0

            field("Mutation" to "addToCounter") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val amount = args.getAs<Int>("amount")
                        counter += amount
                        counter
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "addAmount") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        val value = args.getAs<Int>("value")

                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Mutation", "addToCounter(amount: \$amt)", mapOf("amt" to value))

                        val mutationResult = ctx.mutation(
                            resolverId = "Container.addAmount",
                            selectionSet = rss
                        )

                        mutationResult.fetchAs<Int>("addToCounter")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { addAmount(value: 10) } }")
                .assertJson("""{"data": {"container": {"addAmount": 10}}}""")

            runQuery("{ container { addAmount(value: 5) } }")
                .assertJson("""{"data": {"container": {"addAmount": 15}}}""")
        }
    }

    @Test
    fun `ctx mutation executes subquery against Mutation root`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            extend type Mutation {
                incrementCounter: Int
            }

            type Container {
                triggerMutation: Int
            }
            """.trimIndent()
        ) {
            var counter = 0

            field("Mutation" to "incrementCounter") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ++counter
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "triggerMutation") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Execute mutation subquery via ctx.mutation()
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Mutation", "incrementCounter", emptyMap())

                        val mutationResult = ctx.mutation(
                            resolverId = "Container.triggerMutation",
                            selectionSet = rss
                        )

                        mutationResult.fetchAs<Int>("incrementCounter")
                    }
                }
            }
        }.runFeatureTest {
            // First query: counter should be 1
            runQuery("{ container { triggerMutation } }")
                .assertJson("""{"data": {"container": {"triggerMutation": 1}}}""")

            // Second query: counter should be 2
            runQuery("{ container { triggerMutation } }")
                .assertJson("""{"data": {"container": {"triggerMutation": 2}}}""")
        }
    }

    @Test
    fun `query resolver can execute mutation subquery at engine level`() {
        // This tests an edge case: a Query field resolver calling ctx.mutation().
        // While the generated tenant API prevents this (ctx.mutation() is only
        // available in mutation resolvers), the engine itself supports this.
        // This behavior is not recommended but is documented in subquery-execution.md.

        MockTenantModuleBootstrapper(
            """
            extend type Query {
                queryFieldThatMutates: Int
            }

            extend type Mutation {
                incrementCounter: Int
            }
            """.trimIndent()
        ) {
            var counter = 0

            field("Mutation" to "incrementCounter") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ++counter
                    }
                }
            }

            field("Query" to "queryFieldThatMutates") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Execute mutation subquery from a query resolver
                        // This is not recommended but the engine supports it
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Mutation", "incrementCounter", emptyMap())

                        val mutationResult = ctx.mutation(
                            resolverId = "Query.queryFieldThatMutates",
                            selectionSet = rss
                        )

                        mutationResult.fetchAs<Int>("incrementCounter")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ queryFieldThatMutates }")
                .assertJson("""{"data": {"queryFieldThatMutates": 1}}""")

            // Verify mutation was actually executed
            runQuery("{ queryFieldThatMutates }")
                .assertJson("""{"data": {"queryFieldThatMutates": 2}}""")
        }
    }

    @Test
    fun `querySelections provides alternative to ctx query for simple cases`() {
        // This test documents that querySelections() is the existing pattern for
        // accessing Query root fields. ctx.query() is an alternative that provides
        // more explicit control and works with ExecutionHandle.

        MockTenantModuleBootstrapper(
            """
            extend type Query {
                rootValue: Int
                container: Container
            }

            type Container {
                viaQuerySelections: Int
                viaCtxQuery: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 100)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            // Pattern 1: Using querySelections (existing approach)
            field("Container" to "viaQuerySelections") {
                resolver {
                    querySelections("rootValue")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<Int>("rootValue")
                    }
                }
            }

            // Pattern 2: Using ctx.query() (new approach via ExecutionHandle)
            field("Container" to "viaCtxQuery") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "rootValue", emptyMap())

                        val result = ctx.query(
                            resolverId = "Container.viaCtxQuery",
                            selectionSet = rss
                        )

                        result.fetchAs<Int>("rootValue")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { viaQuerySelections viaCtxQuery } }")
                .assertJson("""{"data": {"container": {"viaQuerySelections": 100, "viaCtxQuery": 100}}}""")
        }
    }

    @Test
    fun `nested subquery execution`() {
        // Test that subqueries can themselves trigger additional subqueries
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                level1: Level1
                baseValue: Int
            }

            type Level1 {
                level2: Level2
            }

            type Level2 {
                derivedValue: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "baseValue", 10)

            field("Query" to "level1") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Level1"),
                            mapOf()
                        )
                    }
                }
            }

            field("Level1" to "level2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Level2"),
                            mapOf()
                        )
                    }
                }
            }

            field("Level2" to "derivedValue") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Nested resolver executing subquery
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "baseValue", emptyMap())

                        val result = ctx.query(
                            resolverId = "Level2.derivedValue",
                            selectionSet = rss
                        )

                        result.fetchAs<Int>("baseValue") * 3
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ level1 { level2 { derivedValue } } }")
                .assertJson("""{"data": {"level1": {"level2": {"derivedValue": 30}}}}""")
        }
    }

    @Test
    fun `ctx mutation returns error when schema has no mutation type`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            extend type Mutation {
                dummyMutation: Int
            }

            type Container {
                tryMutation: Int
            }
            """.trimIndent()
        ) {
            field("Mutation" to "dummyMutation") {
                resolver {
                    fn { _, _, _, _, _ -> 0 }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "tryMutation") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Mutation", "nonExistentMutation", emptyMap())

                        ctx.mutation(
                            resolverId = "Container.tryMutation",
                            selectionSet = rss
                        )

                        0
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { tryMutation } }")

            assertEquals(1, result.errors.size)
            val error = result.errors.first()
            assertTrue(
                error.message.contains("Failed to build QueryPlan") ||
                    error.message.contains("nonExistentMutation"),
                "Expected error about QueryPlan build failure or missing field, got: ${error.message}"
            )
        }
    }

    @Test
    fun `ctx query returns error with fieldResolutionFailed when resolver throws`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                failingField: String
                container: Container
            }

            type Container {
                callFailingField: String
            }
            """.trimIndent()
        ) {
            field("Query" to "failingField") {
                resolver {
                    fn { _, _, _, _, _ ->
                        throw RuntimeException("Resolver intentionally failed")
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "callFailingField") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "failingField", emptyMap())

                        val result = ctx.query(
                            resolverId = "Container.callFailingField",
                            selectionSet = rss
                        )

                        result.fetchAs<String>("failingField")
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { callFailingField } }")

            assertEquals(1, result.errors.size)
            val error = result.errors.first()
            assertTrue(
                error.message.contains("Failed to resolve fields during subquery execution") ||
                    error.message.contains("Resolver intentionally failed"),
                "Expected error about field resolution failure, got: ${error.message}"
            )
        }
    }

    @Test
    fun `ctx query fails when selection type does not match Query root`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            type Container {
                tryMismatchedSelection: Int
            }

            type User {
                id: ID
                name: String
            }
            """.trimIndent()
        ) {
            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "tryMismatchedSelection") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Create a RawSelectionSet for User type, but try to execute as Query
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("User", "id name", emptyMap())

                        ctx.query(
                            resolverId = "Container.tryMismatchedSelection",
                            selectionSet = rss
                        )

                        0
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ container { tryMismatchedSelection } }")

            assertEquals(1, result.errors.size)
            assertTrue(
                result.errors.first().message.contains("Cannot execute selections with type User on schema root type Query")
            )
        }
    }

    @Test
    fun `ctx mutation executes fields serially`() {
        val events = Collections.synchronizedList(mutableListOf<String>())

        MockTenantModuleBootstrapper(
            """
            extend type Query {
                container: Container
            }

            extend type Mutation {
                field1: Int
                field2: Int
            }

            type Container {
                triggerMutations: MutationResult
            }

            type MutationResult {
                result1: Int
                result2: Int
            }
            """.trimIndent()
        ) {
            field("Mutation" to "field1") {
                resolver {
                    fn { _, _, _, _, _ ->
                        events.add("field1:start")
                        events.add("field1:end")
                        1
                    }
                }
            }

            field("Mutation" to "field2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        events.add("field2:start")
                        events.add("field2:end")
                        2
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "triggerMutations") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Mutation", "field1 field2", emptyMap())

                        val mutationResult = ctx.mutation(
                            resolverId = "Container.triggerMutations",
                            selectionSet = rss
                        )

                        val result1 = mutationResult.fetchAs<Int>("field1")
                        val result2 = mutationResult.fetchAs<Int>("field2")

                        mkEngineObjectData(
                            schema.schema.getObjectType("MutationResult"),
                            mapOf("result1" to result1, "result2" to result2)
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { triggerMutations { result1 result2 } } }")
                .assertJson("""{"data": {"container": {"triggerMutations": {"result1": 1, "result2": 2}}}}""")

            assertEquals(
                listOf("field1:start", "field1:end", "field2:start", "field2:end"),
                events,
                "Mutation fields should execute serially: field1 must complete before field2 starts"
            )
        }
    }

    @Test
    fun `nested subqueries with different variables are isolated`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                multiply(n: Int!): Int
                container: Container
            }

            type Container {
                first: Int
                second: Int
            }
            """.trimIndent()
        ) {
            field("Query" to "multiply") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("n") * 2
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "first") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "multiply(n: \$myVar)", mapOf("myVar" to 10))

                        val result = ctx.query(
                            resolverId = "Container.first",
                            selectionSet = rss
                        )

                        result.fetchAs<Int>("multiply")
                    }
                }
            }

            field("Container" to "second") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "multiply(n: \$myVar)", mapOf("myVar" to 25))

                        val result = ctx.query(
                            resolverId = "Container.second",
                            selectionSet = rss
                        )

                        result.fetchAs<Int>("multiply")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { first second } }")
                .assertJson("""{"data": {"container": {"first": 20, "second": 50}}}""")
        }
    }

    @Test
    fun `subquery variables do not leak to parent query`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                valueFromVar(v: Int!): Int
                container: Container
            }

            type Container {
                useSubqueryVar: Int
            }
            """.trimIndent()
        ) {
            field("Query" to "valueFromVar") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("v")
                    }
                }
            }

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "useSubqueryVar") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "valueFromVar(v: \$subVar)", mapOf("subVar" to 42))

                        val result = ctx.query(
                            resolverId = "Container.useSubqueryVar",
                            selectionSet = rss
                        )

                        result.fetchAs<Int>("valueFromVar")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ container { useSubqueryVar } }")
                .assertJson("""{"data": {"container": {"useSubqueryVar": 42}}}""")
        }
    }

    @Test
    fun `ctx query with fragment spreads and variables`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                user(id: ID!): User
                aggregator: Aggregator
            }

            type User {
                id: ID!
                name: String
                email: String
                age: Int
            }

            type Aggregator {
                fetchUserDetails(userId: ID!): UserSummary
            }

            type UserSummary {
                userId: ID!
                displayName: String
                contactEmail: String
                userAge: Int
            }
            """.trimIndent()
        ) {
            field("Query" to "user") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val id = args.getAs<String>("id")
                        mkEngineObjectData(
                            schema.schema.getObjectType("User"),
                            mapOf(
                                "id" to id,
                                "name" to "User$id",
                                "email" to "user$id@example.com",
                                "age" to (20 + id.toInt())
                            )
                        )
                    }
                }
            }

            field("Query" to "aggregator") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Aggregator"),
                            mapOf()
                        )
                    }
                }
            }

            field("Aggregator" to "fetchUserDetails") {
                resolver {
                    fn { args, _, _, _, ctx ->
                        val userId = args.getAs<String>("userId")

                        // Use fragment spreads with variables in subquery selection
                        // Fragment spreads are inlined by toSelectionSet()
                        val rss = ctx.rawSelectionSetFactory.rawSelectionSet(
                            "Query",
                            """
                            fragment UserBasicInfo on User { id name }
                            fragment UserContactInfo on User { email }
                            fragment UserDemographics on User { age }
                            fragment Main on Query {
                                user(id: ${'$'}uid) {
                                    ...UserBasicInfo
                                    ...UserContactInfo
                                    ...UserDemographics
                                }
                            }
                            """,
                            mapOf("uid" to userId)
                        )

                        val queryResult = ctx.query(
                            resolverId = "Aggregator.fetchUserDetails",
                            selectionSet = rss
                        )

                        // Fetch the nested EngineObjectData and extract fields
                        val user = queryResult.fetchAs<EngineObjectData>("user")
                        mkEngineObjectData(
                            schema.schema.getObjectType("UserSummary"),
                            mapOf(
                                "userId" to user.fetch("id"),
                                "displayName" to user.fetch("name"),
                                "contactEmail" to user.fetch("email"),
                                "userAge" to user.fetch("age")
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ aggregator { fetchUserDetails(userId: \"5\") { userId displayName contactEmail userAge } } }")
                .assertJson(
                    """
                    {
                        "data": {
                            "aggregator": {
                                "fetchUserDetails": {
                                    "userId": "5",
                                    "displayName": "User5",
                                    "contactEmail": "user5@example.com",
                                    "userAge": 25
                                }
                            }
                        }
                    }
                    """
                )
        }
    }

    @Test
    fun `subquery execution emits metric for handle path`() {
        val meterRegistry = SimpleMeterRegistry()
        val engineConfig = EngineConfiguration.featureTestDefault.copy(
            meterRegistry = meterRegistry
        )

        MockTenantModuleBootstrapper(
            """
            extend type Query {
                rootValue: Int
                container: Container
            }

            type Container {
                derivedFromQuery: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 42)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "derivedFromQuery") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.rawSelectionSetFactory
                            .rawSelectionSet("Query", "rootValue", emptyMap())
                        val queryResult = ctx.query(
                            resolverId = "Container.derivedFromQuery",
                            selectionSet = rss
                        )
                        queryResult.fetchAs<Int>("rootValue") * 2
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig) {
            runQuery("{ container { derivedFromQuery } }")
                .assertJson("""{"data": {"container": {"derivedFromQuery": 84}}}""")

            val counter = meterRegistry.find(EngineExecutionContextImpl.SUBQUERY_EXECUTION_METER_NAME)
                .tag("path", "handle")
                .tag("success", "true")
                .counter()
            assertNotNull(counter, "Expected subquery execution counter with path=handle, success=true to be present")
            assertEquals(1.0, counter.count(), "Expected exactly one successful subquery execution via handle path")

            val failureCounter = meterRegistry.find(EngineExecutionContextImpl.SUBQUERY_EXECUTION_METER_NAME)
                .tag("path", "handle")
                .tag("success", "false")
                .counter()
            assertTrue(failureCounter == null || failureCounter.count() == 0.0, "Expected no failed subquery executions")
        }
    }
}
