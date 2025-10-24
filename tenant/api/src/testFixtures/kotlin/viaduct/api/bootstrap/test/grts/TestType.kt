package viaduct.api.bootstrap.test.grts

import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.reflect.Type
import viaduct.engine.api.EngineObjectData

// GRT for TestTenantModule tests.
class TestType(context: InternalContext, engineObjectData: EngineObjectData) : ObjectBase(context, engineObjectData) {
    object Reflection : Type<TestType> {
        override val name: String = "TestType"
        override val kcls = TestType::class
    }
}
