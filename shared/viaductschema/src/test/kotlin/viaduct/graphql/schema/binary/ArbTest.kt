@file:Suppress("ForbiddenImport")

package viaduct.graphql.schema.binary

import io.kotest.property.Arb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

class ArbTest : KotestPropertyBase() {
    @Test
    fun `arbitrary schemas can be roundtripped`(): Unit =
        runBlocking {
            Arb.graphQLSchema().checkInvariants { schema, checker ->
                checkRoundTrip(ViaductSchema.fromGraphQLSchema(schema), checker)
            }
        }
}
