package viaduct.gradle.federation

import viaduct.gradle.spi.ValidationRuleProvider
import viaduct.gradle.spi.ViaductRuleSet
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule
import viaduct.x.federation.validation.FoundationFieldSelectionRule

class FoundationValidationRuleProvider : ValidationRuleProvider {
    override fun createRuleSet(schema: ViaductSchema): ViaductRuleSet =
        object : ViaductRuleSet {
            override fun createContext(): ValidationContext? = null

            override fun getRules(): List<ValidationRule> = listOf(FoundationFieldSelectionRule())
        }
}
