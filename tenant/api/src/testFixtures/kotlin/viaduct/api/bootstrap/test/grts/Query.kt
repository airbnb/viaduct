package viaduct.api.bootstrap.test.grts

import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.reflect.Type
import viaduct.engine.api.EngineObjectData

// GRT for TestTenantModule tests.
class Query(context: InternalContext, engineObjectData: EngineObjectData) : ObjectBase(context, engineObjectData), viaduct.api.types.Query {
    object Reflection : Type<Query> {
        override val name: String = "Query"
        override val kcls = Query::class
    }
}
