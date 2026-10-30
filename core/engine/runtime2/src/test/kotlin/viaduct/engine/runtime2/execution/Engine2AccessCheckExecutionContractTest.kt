package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.runtime.execution.AccessCheckExecutionContract

@ExperimentalCoroutinesApi
class Engine2AccessCheckExecutionContractTest : AccessCheckExecutionContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "ErrorData: Preserve resolver-error precedence when the field checker also fails")
    override fun `async field throws, checker throws`() {
        super<AccessCheckExecutionContract>.`async field throws, checker throws`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "AsyncEOD: Asynchronous object ingress fails before leaf completion")
    override fun `sync field throws, checker throws`() {
        super<AccessCheckExecutionContract>.`sync field throws, checker throws`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "ErrorData: Preserve resolver-error precedence for checked required selections")
    override fun `objectValueFragment field - field throws, checker throws`() {
        super<AccessCheckExecutionContract>.`objectValueFragment field - field throws, checker throws`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerVarsRSS: Checker variables resolvers with their own required selections are unsupported")
    override fun `checker with rss - access checks skipped`() {
        super<AccessCheckExecutionContract>.`checker with rss - access checks skipped`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "MutationChk: Mutation namespace field checkers are unsupported")
    override fun `mutation field with checker`() {
        super<AccessCheckExecutionContract>.`mutation field with checker`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerErrorData: Checker errors do not reach the configured error builder")
    override fun `field access check errors are handled`() {
        super<AccessCheckExecutionContract>.`field access check errors are handled`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerErrorData: Preserve list item results and custom error metadata on checker failure")
    override fun `list item access check errors are handled`() {
        super<AccessCheckExecutionContract>.`list item access check errors are handled`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerVarsRSS: Checker variables resolvers with their own required selections are unsupported")
    override fun `type checker RSS variable resolver can fetch from current object`() {
        super<AccessCheckExecutionContract>.`type checker RSS variable resolver can fetch from current object`()
    }
}
