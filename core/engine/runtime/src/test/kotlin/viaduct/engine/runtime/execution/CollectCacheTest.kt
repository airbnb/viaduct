package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
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
}
