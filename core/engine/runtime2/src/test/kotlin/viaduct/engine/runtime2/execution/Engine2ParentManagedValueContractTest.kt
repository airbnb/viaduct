package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.runtime.execution.ParentManagedValueContract

@ExperimentalCoroutinesApi
class Engine2ParentManagedValueContractTest : ParentManagedValueContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "ParentManagedValue: Runtime2 does not unwrap ParentManagedValue resolver results, so completion fails with a ClassCastException")
    override fun `resolver takes over nested selection`() {
        super<ParentManagedValueContract>.`resolver takes over nested selection`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "ParentManagedValue: Runtime2 does not unwrap ParentManagedValue resolver results, so completion fails with a ClassCastException")
    override fun `policy propagates through deep nesting`() {
        super<ParentManagedValueContract>.`policy propagates through deep nesting`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "ParentManagedValue: Runtime2 does not unwrap ParentManagedValue resolver results, so completion fails with a ClassCastException")
    override fun `policy propagates to list items`() {
        super<ParentManagedValueContract>.`policy propagates to list items`()
    }
}
