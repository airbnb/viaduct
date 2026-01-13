@file:Suppress("ForbiddenImport")

package viaduct.graphql.schema.binary

import io.kotest.property.Arb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.checkInvariants
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.graphql.schema.graphqljava.GJSchema

class ArbTest {
    @Test
    fun `arbitrary schemas can be roundtripped`(): Unit =
        runBlocking {
            Arb.graphQLSchema().checkInvariants { schema, checker ->
                checkRoundTrip(GJSchema.fromSchema(schema), checker)
            }
        }
}
