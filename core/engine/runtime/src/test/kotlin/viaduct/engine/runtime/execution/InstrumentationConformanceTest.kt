@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import io.kotest.property.Arb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase

class InstrumentationConformanceTest : KotestPropertyBase() {
    @Test
    fun `scalar`() {
        Conformer("type Query { x:Int }").checkInstrumentations()
    }

    @Test
    fun `enum`() {
        Conformer(
            """
                enum E { A, B }
                type Query { e:E }
            """.trimIndent()
        ).checkInstrumentations()
    }

    @Test
    fun `list`() {
        Conformer("type Query { x:[Int] }").checkInstrumentations()
    }

    // simple easier-to-debug repro of a list instrumentation issue
    // use this to debug list instrumentations, when this test case and the `list` test case pass, delete this test
    @Test
    fun `list of scalars`() {
        Conformer(
            "type Query { x:[Int] }",
            resolvers = mapOf("Query" to mapOf("x" to DataFetcher { listOf(1, 2) }))
        ) {
            check("{x}")
        }
    }

    @Test
    fun `object`() {
        Conformer(
            """
                type Obj { x:Int }
                type Query { obj:Obj }
            """.trimIndent()
        ).checkInstrumentations()
    }

    @Test
    fun `list of object`() {
        Conformer(
            """
                type Obj { x:Int }
                type Query { objs:[Obj] }
            """.trimIndent(),
            resolvers = mapOf("Query" to mapOf("objs" to DataFetcher { listOf(mapOf("x" to 1), mapOf("x" to 2)) }))
        ) {
            check("{objs { x } }")
        }
    }

    private fun Conformer.checkInstrumentations() = Arb.viaductExecutionInput(schema).checkAll()
}
