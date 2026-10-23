package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.Engine1QueryExecutionContract

class ParentFieldRequiredSelectionsContractTest : ParentFieldRequiredSelectionsContract, Engine1QueryExecutionContract {
    @Test
    fun `each child field can read its own parent's name`() {
        EngineTestModule(
            """
            extend type Query { parents: [Parent] }
            type Parent { name: String, firstChild: Child, secondChild: Child }
            type Child { parent: Parent @parent }
            """.trimIndent(),
        ) {
            fieldWithValue(
                "Query" to "parents",
                listOf(
                    createEngineObjectData(schema.schema.getObjectType("Parent"), mapOf("name" to "Alice")),
                    createEngineObjectData(schema.schema.getObjectType("Parent"), mapOf("name" to "Bob")),
                ),
            )
            // Leave the children empty: the engine must supply their @parent links.
            fieldWithValue("Parent" to "firstChild", createEngineObjectData(schema.schema.getObjectType("Child"), emptyMap()))
            fieldWithValue("Parent" to "secondChild", createEngineObjectData(schema.schema.getObjectType("Child"), emptyMap()))
        }.runFeatureTest {
            runQuery(
                """
                {
                  parents {
                    firstChild { parent { name } }
                    secondChild { parent { name } }
                  }
                }
                """.trimIndent()
            ).assertJson(
                """
                {data: {parents: [
                  {firstChild: {parent: {name: "Alice"}}, secondChild: {parent: {name: "Alice"}}},
                  {firstChild: {parent: {name: "Bob"}}, secondChild: {parent: {name: "Bob"}}}
                ]}}
                """.trimIndent()
            )
        }
    }
}
