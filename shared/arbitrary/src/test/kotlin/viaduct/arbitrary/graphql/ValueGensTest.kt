@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.schema.GraphQLInputType
import io.kotest.property.exhaustive.exhaustive
import io.kotest.property.forAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase

@ExperimentalCoroutinesApi
class ValueGensTest : KotestPropertyBase() {
    private val schema = """
        enum E { A, B, C }
        input Inp { x:Int, y:Float, z:Inp }
        type Query { x:Int }
    """.trimIndent().asSchema

    private val inputTypes = schema.typeMap.values
        .mapNotNull { it as? GraphQLInputType }

    private val gens = ValueGens(schema, Config.default, randomSource)

    @Test
    fun `raw`(): Unit =
        runBlocking {
            inputTypes.exhaustive().forAll {
                runCatching { gens.raw(it) }.isSuccess
            }
        }

    @Test
    fun `gj`(): Unit =
        runBlocking {
            inputTypes.exhaustive().forAll {
                runCatching { gens.gj(it) }.isSuccess
            }
        }

    @Test
    fun `kotlin`(): Unit =
        runBlocking {
            inputTypes.exhaustive().forAll {
                runCatching { gens.kotlin(it) }.isSuccess
            }
        }
}
