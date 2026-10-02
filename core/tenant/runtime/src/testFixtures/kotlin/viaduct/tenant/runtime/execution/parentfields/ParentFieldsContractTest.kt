package viaduct.tenant.runtime.execution.parentfields

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertJson

@TestSchema(
    """
        extend type Query {
          "Return one Parent per supplied name, in order."
          parents(names: [String]): [Parent] @resolver
        }
        type Parent {
          name: String
          "Return a new Child without setting its parent."
          firstChild: Child @resolver
          "Return a new Child without setting its parent."
          secondChild: Child @resolver
        }
        type Child {
          parent: Parent @parent
          "Read parent { name } through required selections and return the name."
          parentName: String @resolver
        }
    """
)
abstract class ParentFieldsContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `child resolvers read the parent of each child field`() {
        val result = execute(
            """
            {
              parents(names: ["Alice", "Bob"]) {
                firstChild { parentName }
                secondChild { parentName }
              }
            }
            """.trimIndent()
        )

        result.assertJson(
            """
            {data: {parents: [
              {firstChild: {parentName: "Alice"}, secondChild: {parentName: "Alice"}},
              {firstChild: {parentName: "Bob"}, secondChild: {parentName: "Bob"}}
            ]}}
            """.trimIndent()
        )
    }
}
