package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.ViaductSchema

class CollectCacheTest {
    private val emptyVars = CoercedVariables.emptyVariables()

    @Test
    fun `collect returns cached result for same parentType and selectionSet`() {
        val schema = "type Query { x: Int, y: String }".asSchema
        val plan = buildPlan("{ x y }", ViaductSchema(schema))

        val cache = CollectCache()

        val result1 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val result2 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)

        result1.selections.shouldHaveSize(2)
        assertSame(result1, result2)
    }

    @Test
    fun `collect returns different results for different parentTypes`() {
        val schema = """
            type Query { x: Int }
            type Mutation { y: Int }
        """.trimIndent().asSchema
        val queryPlan = buildPlan("{ x }", ViaductSchema(schema))
        val mutationPlan = buildPlan("mutation { y }", ViaductSchema(schema))

        val cache = CollectCache()

        val queryResult = cache.collectForTest(schema, queryPlan.selectionSet, emptyVars, schema.queryType, queryPlan.fragments)
        val mutationResult = cache.collectForTest(schema, mutationPlan.selectionSet, emptyVars, schema.mutationType!!, mutationPlan.fragments)

        queryResult.selections.shouldHaveSize(1)
        mutationResult.selections.shouldHaveSize(1)
        assertEquals("x", (queryResult.selections[0] as QueryPlan.CollectedField).responseKey)
        assertEquals("y", (mutationResult.selections[0] as QueryPlan.CollectedField).responseKey)
    }

    @Test
    fun `collect uses identity-based cache key`() {
        val schema = "type Query { x: Int }".asSchema
        val plan = buildPlan("{ x }", ViaductSchema(schema))

        val cache = CollectCache()

        val result1 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val result2 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val result3 = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)

        assertSame(result1, result2)
        assertSame(result2, result3)
    }

    @Test
    fun `collect caches nested selection sets independently`() {
        val schema = """
            type Query { foo: Foo }
            type Foo { bar: String, baz: Int }
        """.trimIndent().asSchema
        val plan = buildPlan("{ foo { bar baz } }", ViaductSchema(schema))
        val fooType = schema.getObjectType("Foo")
        val fooField = plan.selectionSet.selections[0] as QueryPlan.Field
        val fooSelectionSet = fooField.selectionSet!!

        val cache = CollectCache()

        val queryResult = cache.collectForTest(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val fooResult = cache.collectForTest(schema, fooSelectionSet, emptyVars, fooType, plan.fragments)

        queryResult.selections.shouldHaveSize(1)
        fooResult.selections.shouldHaveSize(2)

        val fooResultAgain = cache.collectForTest(schema, fooSelectionSet, emptyVars, fooType, plan.fragments)
        assertSame(fooResult, fooResultAgain)
    }

    @Test
    fun `collect keys nested selection sets on enclosing directive variables but ignores enclosing argument variables`() {
        val schema = """
            type Query { user(id: ID): User }
            type User { id: ID, name: String }
        """.trimIndent().asSchema
        val plan = buildPlan(
            """
                query(${'$'}includeUser: Boolean!, ${'$'}id: ID) {
                    user(id: ${'$'}id) @include(if: ${'$'}includeUser) {
                        id
                        name
                    }
                }
            """.trimIndent(),
            ViaductSchema(schema)
        )
        val userField = plan.selectionSet.selections.single() as QueryPlan.Field
        val userSelectionSet = userField.selectionSet!!
        val userType = schema.getObjectType("User")
        val cache = CollectCache()

        val includeUserResult =
            cache.collectForTest(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to true, "id" to "1")), userType, plan.fragments)
        val sameDirectiveDifferentArgumentResult =
            cache.collectForTest(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to true, "id" to "2")), userType, plan.fragments)
        val skipUserResult =
            cache.collectForTest(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to false, "id" to "1")), userType, plan.fragments)

        assertEquals(listOf("id", "name"), includeUserResult.selections.map { (it as QueryPlan.CollectedField).responseKey })
        assertSame(includeUserResult, sameDirectiveDifferentArgumentResult)
        assertNotSame(includeUserResult, skipUserResult)
        skipUserResult.selections.shouldHaveSize(0)
    }

    @Test
    fun `collect keys on directive variables but ignores argument-only variables`() {
        val schema = "type Query { x(id: ID): Int, y: Int }".asSchema
        val plan = buildPlan("{ x(id: ${'$'}id) @include(if: ${'$'}directive), y @skip(if: ${'$'}directive) }", ViaductSchema(schema))
        val cache = CollectCache()

        val firstResult =
            cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to true, "id" to "1")), schema.queryType, plan.fragments)
        val sameDirectiveDifferentArgumentResult =
            cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to true, "id" to "2")), schema.queryType, plan.fragments)
        val differentDirectiveResult =
            cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to false, "id" to "1")), schema.queryType, plan.fragments)

        assertEquals("x", (firstResult.selections.single() as QueryPlan.CollectedField).responseKey)
        assertSame(firstResult, sameDirectiveDifferentArgumentResult)
        assertNotSame(firstResult, differentDirectiveResult)
        assertEquals("y", (differentDirectiveResult.selections.single() as QueryPlan.CollectedField).responseKey)
    }

    @Test
    fun `collect keys on directive variables from fragment spreads and fragment bodies`() {
        val schema = "type Query { x: Int, y: Int }".asSchema
        val plan = buildPlan(
            """
                query(${'$'}spread: Boolean!, ${'$'}field: Boolean!) {
                    y
                    ...F @skip(if: ${'$'}spread)
                }

                fragment F on Query {
                    x @include(if: ${'$'}field)
                }
            """.trimIndent(),
            ViaductSchema(schema)
        )
        val cache = CollectCache()

        val includeFragmentResult =
            cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to false, "field" to true)), schema.queryType, plan.fragments)
        val excludeFragmentFieldResult =
            cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to false, "field" to false)), schema.queryType, plan.fragments)
        val skipFragmentResult =
            cache.collectForTest(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to true, "field" to true)), schema.queryType, plan.fragments)

        assertEquals(listOf("y", "x"), includeFragmentResult.selections.map { (it as QueryPlan.CollectedField).responseKey })
        assertNotSame(includeFragmentResult, excludeFragmentFieldResult)
        assertEquals(listOf("y"), excludeFragmentFieldResult.selections.map { (it as QueryPlan.CollectedField).responseKey })
        assertNotSame(includeFragmentResult, skipFragmentResult)
        assertEquals(listOf("y"), skipFragmentResult.selections.map { (it as QueryPlan.CollectedField).responseKey })
    }

    @Test
    fun `collect ignores directive variables in type-pruned fragments`() {
        val schema = """
            interface Node { id: ID }
            type User implements Node { id: ID, name: String }
            type Listing implements Node { id: ID, title: String }
            type Query { node: Node }
        """.trimIndent().asSchema
        val plan = buildPlan(
            """
                query {
                    node {
                        id
                        ...UserFields
                    }
                }

                fragment UserFields on User {
                    name @include(if: ${'$'}includeName)
                }
            """.trimIndent(),
            ViaductSchema(schema)
        )
        val nodeField = plan.selectionSet.selections.single() as QueryPlan.Field
        val nodeSelectionSet = nodeField.selectionSet!!
        val listingType = schema.getObjectType("Listing")
        val cache = CollectCache()

        val includeNameResult =
            cache.collectForTest(schema, nodeSelectionSet, CoercedVariables.of(mapOf("includeName" to true)), listingType, plan.fragments)
        val skipNameResult =
            cache.collectForTest(schema, nodeSelectionSet, CoercedVariables.of(mapOf("includeName" to false)), listingType, plan.fragments)

        assertEquals("id", (includeNameResult.selections.single() as QueryPlan.CollectedField).responseKey)
        assertSame(includeNameResult, skipNameResult)
    }
}

private fun CollectCache.collectForTest(
    schema: GraphQLSchema,
    selectionSet: QueryPlan.SelectionSet,
    variables: CoercedVariables,
    parentType: GraphQLObjectType,
    fragments: QueryPlan.Fragments,
): QueryPlan.SelectionSet =
    collect(
        schema,
        selectionSet,
        variables,
        parentType,
        fragments,
        fieldRssOriginFilteringKillSwitchEnabled = false,
    )
