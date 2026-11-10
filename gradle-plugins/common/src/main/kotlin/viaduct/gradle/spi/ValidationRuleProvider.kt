package viaduct.gradle.spi

import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

@ExperimentalApi
interface ValidationRuleProvider {
    fun createRuleSet(schema: ViaductSchema): ViaductRuleSet
}

@ExperimentalApi
interface ViaductRuleSet {
    fun createContext(): ValidationContext?

    fun getRules(): List<ValidationRule>
}
