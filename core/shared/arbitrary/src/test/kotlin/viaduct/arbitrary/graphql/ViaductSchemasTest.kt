@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.graphql.schema.checkViaductSchemaInvariants

class ViaductSchemasTest : KotestPropertyBase(iterations = 100) {
    @Test
    fun `generates valid ViaductSchemas`(): Unit =
        runBlocking {
            Arb.viaductExtendedSchema().checkInvariants { schema, check ->
                checkViaductSchemaInvariants(schema, check)
            }
        }

    @Test
    fun `TypeExpr methods do not throw for non-list types`(): Unit =
        runBlocking {
            Arb
                .typeExpr()
                .filter { !it.isList }
                .checkInvariants { type, check ->
                    check.doesNotThrow("unexpected err") {
                        type.nullableAtDepth(0)
                        type.isList
                        type.listDepth
                    }
                }
        }
}
