package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.CompleteSelectionSetContract

class Engine2CompleteSelectionSetContractTest : CompleteSelectionSetContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "CompleteSelectionSet: Runtime2 does not yet support ctx.completeSelectionSet")
    override fun `object-typed completion with scalar fields`() {
        super<CompleteSelectionSetContract>.`object-typed completion with scalar fields`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CompleteSelectionSet: Runtime2 does not yet support ctx.completeSelectionSet")
    override fun `object-typed completion resolves required selection set variables`() {
        super<CompleteSelectionSetContract>.`object-typed completion resolves required selection set variables`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CompleteSelectionSet: Runtime2 does not yet support ctx.completeSelectionSet")
    override fun `object-typed completion indexes variable RSS before resolving variables`() {
        super<CompleteSelectionSetContract>.`object-typed completion indexes variable RSS before resolving variables`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CompleteSelectionSet: Runtime2 does not yet support ctx.completeSelectionSet")
    override fun `query-typed completion via querySelections`() {
        super<CompleteSelectionSetContract>.`query-typed completion via querySelections`()
    }
}
