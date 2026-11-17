package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.runtime2.model.testing.TestWorld

/** Contract for variables produced by exact paths in the defining Query fragment. */
interface FromQueryFieldResolverContract : ResolverContract {
    @TestFactory
    fun `Query field bindings support every fragment consumer`() =
        listOf(
            Triple("Query fragment", false, true),
            Triple("object fragment", true, false),
            Triple("both fragments", true, true),
        ).map { (name, consumeInObjectFragment, consumeInQueryFragment) ->
            dynamicTest(name) {
                assertFromQueryFieldConsumers(
                    consumeInObjectFragment = consumeInObjectFragment,
                    consumeInQueryFragment = consumeInQueryFragment,
                )
            }
        }

    private fun assertFromQueryFieldConsumers(
        consumeInObjectFragment: Boolean,
        consumeInQueryFragment: Boolean,
    ) {
        val objectFragmentSource =
            if (consumeInObjectFragment) "objectSide: consume(value: ${'$'}provided)" else ""
        val queryConsumer =
            if (consumeInQueryFragment) " querySide: consume(value: ${'$'}provided)" else ""
        val resultScript = listOfNotNull(
            "objectValue.objectSide".takeIf { consumeInObjectFragment },
            "queryValue.querySide".takeIf { consumeInQueryFragment },
        ).joinToString(" + ")
        val testWorld = TestWorld.fromDSL(
            """
            extend type Query {
              provider: Int! @resolver(script: "7")
              consume(value: Int!): Int! @resolver(script: "value")
              consumer: Int! @resolver(
                of: "$objectFragmentSource"
                qf: "providedSource: provider$queryConsumer"
                variables: {name: "provided", fromQueryField: ["providedSource"]}
                script: "$resultScript"
              )
            }
            """.trimIndent(),
        )
        val world = testWorld.assumptions
        val consumerKey = world.schema.contractKey("Query", "consumer")

        val resolved = resolveAndValidate(testWorld, "query { consumer }", record = false)

        assertEquals(
            if (consumeInObjectFragment && consumeInQueryFragment) 14 else 7,
            resolved.getCell(consumerKey).get(),
        )
    }
}
