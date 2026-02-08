package detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Provides the custom Viaduct Detekt rules to the Detekt framework.
 */
class ViaductRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "viaduct-detekt-rules"

    override fun instance(config: Config) =
        RuleSet(
            ruleSetId,
            listOf(
                NoPrintlnInGradleRule(config),
                NoStringDependenciesInGradleRule(config),
                ApiStabilityAnnotationRequiredRule(config)
            )
        )
}
