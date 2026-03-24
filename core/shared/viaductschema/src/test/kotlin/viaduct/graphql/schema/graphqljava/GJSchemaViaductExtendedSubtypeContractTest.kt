package viaduct.graphql.schema.graphqljava

import kotlin.reflect.KClass
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.test.ViaductSchemaSubtypeContract

class GJSchemaViaductExtendedSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass(): KClass<*> = SchemaWithData::class
}
