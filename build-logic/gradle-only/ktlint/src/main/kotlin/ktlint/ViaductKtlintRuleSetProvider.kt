package ktlint

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.RuleSetId

class ViaductKtlintRuleSetProvider : RuleSetProviderV3(
    id = RuleSetId("viaduct"),
) {
    override fun getRuleProviders(): Set<RuleProvider> =
        setOf(
            RuleProvider { CoroutinesDependencyUsageRule() },
            RuleProvider { NoPrintlnInGradleRule() },
            RuleProvider { NoStringDependenciesInGradleRule() },
        )
}
