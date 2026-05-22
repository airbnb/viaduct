package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isNotSameInstanceAs
import strikt.assertions.isSameInstanceAs
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.ViaductSchema

class CollectCacheTest {
    private val emptyVars = CoercedVariables.emptyVariables()

    @Test
    fun `collect returns cached result for same parentType and selectionSet`() {
        val schema = "type Query { x: Int, y: String }".asSchema
        val plan = buildPlan("{ x y }", ViaductSchema(schema))

        val cache = CollectCache()

        val result1 = cache.collect(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val result2 = cache.collect(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)

        expectThat(result1.selections).hasSize(2)
        expectThat(result2).isSameInstanceAs(result1)
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

        val queryResult = cache.collect(schema, queryPlan.selectionSet, emptyVars, schema.queryType, queryPlan.fragments)
        val mutationResult = cache.collect(schema, mutationPlan.selectionSet, emptyVars, schema.mutationType!!, mutationPlan.fragments)

        expectThat(queryResult.selections).hasSize(1)
        expectThat(mutationResult.selections).hasSize(1)
        expectThat((queryResult.selections[0] as QueryPlan.CollectedField).responseKey).isEqualTo("x")
        expectThat((mutationResult.selections[0] as QueryPlan.CollectedField).responseKey).isEqualTo("y")
    }

    @Test
    fun `collect uses identity-based cache key`() {
        val schema = "type Query { x: Int }".asSchema
        val plan = buildPlan("{ x }", ViaductSchema(schema))

        val cache = CollectCache()

        val result1 = cache.collect(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val result2 = cache.collect(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val result3 = cache.collect(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)

        expectThat(result1).isSameInstanceAs(result2)
        expectThat(result2).isSameInstanceAs(result3)
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

        val queryResult = cache.collect(schema, plan.selectionSet, emptyVars, schema.queryType, plan.fragments)
        val fooResult = cache.collect(schema, fooSelectionSet, emptyVars, fooType, plan.fragments)

        expectThat(queryResult.selections).hasSize(1)
        expectThat(fooResult.selections).hasSize(2)

        val fooResultAgain = cache.collect(schema, fooSelectionSet, emptyVars, fooType, plan.fragments)
        expectThat(fooResultAgain).isSameInstanceAs(fooResult)
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
            cache.collect(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to true, "id" to "1")), userType, plan.fragments)
        val sameDirectiveDifferentArgumentResult =
            cache.collect(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to true, "id" to "2")), userType, plan.fragments)
        val skipUserResult =
            cache.collect(schema, userSelectionSet, CoercedVariables.of(mapOf("includeUser" to false, "id" to "1")), userType, plan.fragments)

        expectThat(includeUserResult.selections.map { (it as QueryPlan.CollectedField).responseKey }).isEqualTo(listOf("id", "name"))
        expectThat(sameDirectiveDifferentArgumentResult).isSameInstanceAs(includeUserResult)
        expectThat(skipUserResult).isNotSameInstanceAs(includeUserResult)
        expectThat(skipUserResult.selections).hasSize(0)
    }

    @Test
    fun `collect keys on directive variables but ignores argument-only variables`() {
        val schema = "type Query { x(id: ID): Int, y: Int }".asSchema
        val plan = buildPlan("{ x(id: ${'$'}id) @include(if: ${'$'}directive), y @skip(if: ${'$'}directive) }", ViaductSchema(schema))
        val cache = CollectCache()

        val firstResult =
            cache.collect(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to true, "id" to "1")), schema.queryType, plan.fragments)
        val sameDirectiveDifferentArgumentResult =
            cache.collect(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to true, "id" to "2")), schema.queryType, plan.fragments)
        val differentDirectiveResult =
            cache.collect(schema, plan.selectionSet, CoercedVariables.of(mapOf("directive" to false, "id" to "1")), schema.queryType, plan.fragments)

        expectThat((firstResult.selections.single() as QueryPlan.CollectedField).responseKey).isEqualTo("x")
        expectThat(sameDirectiveDifferentArgumentResult).isSameInstanceAs(firstResult)
        expectThat(differentDirectiveResult).isNotSameInstanceAs(firstResult)
        expectThat((differentDirectiveResult.selections.single() as QueryPlan.CollectedField).responseKey).isEqualTo("y")
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
            cache.collect(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to false, "field" to true)), schema.queryType, plan.fragments)
        val excludeFragmentFieldResult =
            cache.collect(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to false, "field" to false)), schema.queryType, plan.fragments)
        val skipFragmentResult =
            cache.collect(schema, plan.selectionSet, CoercedVariables.of(mapOf("spread" to true, "field" to true)), schema.queryType, plan.fragments)

        expectThat(includeFragmentResult.selections.map { (it as QueryPlan.CollectedField).responseKey }).isEqualTo(listOf("y", "x"))
        expectThat(excludeFragmentFieldResult).isNotSameInstanceAs(includeFragmentResult)
        expectThat(excludeFragmentFieldResult.selections.map { (it as QueryPlan.CollectedField).responseKey }).isEqualTo(listOf("y"))
        expectThat(skipFragmentResult).isNotSameInstanceAs(includeFragmentResult)
        expectThat(skipFragmentResult.selections.map { (it as QueryPlan.CollectedField).responseKey }).isEqualTo(listOf("y"))
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
            cache.collect(schema, nodeSelectionSet, CoercedVariables.of(mapOf("includeName" to true)), listingType, plan.fragments)
        val skipNameResult =
            cache.collect(schema, nodeSelectionSet, CoercedVariables.of(mapOf("includeName" to false)), listingType, plan.fragments)

        expectThat((includeNameResult.selections.single() as QueryPlan.CollectedField).responseKey).isEqualTo("id")
        expectThat(skipNameResult).isSameInstanceAs(includeNameResult)
    }
}
