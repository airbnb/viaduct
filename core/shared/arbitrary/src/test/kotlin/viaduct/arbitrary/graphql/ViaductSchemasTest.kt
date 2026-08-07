@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase

class ViaductSchemasTest : KotestPropertyBase() {
    @Test
    fun `Arb_viaductSchema`(): Unit =
        runBlocking {
            Arb.viaductSchema().checkAll {
                markSuccess()
            }
        }
}
