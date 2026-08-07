@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.graphql.schema.checkViaductSchemaInvariants

class VSchemasTest : KotestPropertyBase(iterations = 100) {
    @Test
    fun `generates valid VSchemas`(): Unit =
        runBlocking {
            Arb.vSchema().checkInvariants { schema, check ->
                checkViaductSchemaInvariants(schema, check)
            }
        }

    @Test
    fun `TypeExpr methods do not throw for non-list types`(): Unit =
        runBlocking {
            Arb
                .vSchemaTypeExpr()
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
