package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.MutationFieldCheckerFailureContract

class Engine2MutationFieldCheckerFailureContractTest : MutationFieldCheckerFailureContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "MutationChecker: Runtime2 rejects mutation namespace field checkers")
    override fun mutationReturningListWithFailedFieldCheckerHandlesResultType() {
        super<MutationFieldCheckerFailureContract>.mutationReturningListWithFailedFieldCheckerHandlesResultType()
    }
}
