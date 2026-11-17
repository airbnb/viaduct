package viaduct.engine.runtime2.execution

import viaduct.engine.runtime.execution.ResolverInstrumentationFeatureContract

class Engine2ResolverInstrumentationFeatureContractTest : ResolverInstrumentationFeatureContract, Engine2QueryExecutionContract {
    @Engine2Disabled(Engine2DisabledKind.TODO, "Instrumentation: Runtime2 does not invoke ViaductResolverInstrumentation for field resolvers")
    override fun `field resolver invokes instrumentation`() {
        super<ResolverInstrumentationFeatureContract>.`field resolver invokes instrumentation`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Instrumentation: Runtime2 does not invoke resolver instrumentation for checker executions")
    override fun `field checker invokes instrumentation`() {
        super<ResolverInstrumentationFeatureContract>.`field checker invokes instrumentation`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Instrumentation: Runtime2 does not invoke resolver instrumentation for checker variable-resolver RSS materialization")
    override fun `field checker variable-resolver RSS materialization invokes instrumentation`() {
        super<ResolverInstrumentationFeatureContract>.`field checker variable-resolver RSS materialization invokes instrumentation`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Instrumentation: Runtime2 does not fire beginFetchSelection for ctx.query() subquery materialization")
    override fun `ctx query subquery materialization invokes fetch selection instrumentation`() {
        super<ResolverInstrumentationFeatureContract>.`ctx query subquery materialization invokes fetch selection instrumentation`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "Instrumentation: Runtime2 does not fire beginFetchSelection for ctx.mutation() subquery materialization")
    override fun `ctx mutation subquery materialization invokes fetch selection instrumentation`() {
        super<ResolverInstrumentationFeatureContract>.`ctx mutation subquery materialization invokes fetch selection instrumentation`()
    }
}
