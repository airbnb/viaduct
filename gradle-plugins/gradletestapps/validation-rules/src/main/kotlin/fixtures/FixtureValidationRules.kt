@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package fixtures

import viaduct.gradle.spi.ValidationRuleProvider
import viaduct.gradle.spi.ViaductRuleSet
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

class SharedValidationRules : ValidationRuleProvider {
    private var created = false

    override fun createRuleSet(schema: ViaductSchema): ViaductRuleSet {
        check(!created) { "Provider state leaked between validation runs" }
        created = true
        return object : ViaductRuleSet {
            override fun createContext(): ValidationContext? = null

            override fun getRules() =
                listOf(object : ValidationRule("dev.example.fixture.shared") {
                    private var visited = false

                    override fun visitSchema(ctx: ValidationContext) {
                        check(!visited && ctx.schema === schema)
                        visited = true
                        check("Query" in schema.types)
                        ctx.reportError("FIXTURE_WARNING", ValidationRuleSupport.message(), SchemaLocation(emptyList()), SchemaValidationError.Severity.WARNING)
                    }
                })
        }
    }
}

class PrivateValidationRules : ValidationRuleProvider {
    override fun createRuleSet(schema: ViaductSchema) =
        object : ViaductRuleSet {
            override fun createContext() = FixtureContext(schema)

            override fun getRules() =
                listOf(object : ValidationRule("dev.example.fixture.private") {
                    override fun visitTypeDef(
                        ctx: ValidationContext,
                        typeDef: ViaductSchema.TypeDef
                    ) {
                        check(ctx is FixtureContext && ctx.schema === schema)
                        check(ctx.types.add(typeDef.name)) { "A type was visited more than once" }
                    }
                })
        }

    private class FixtureContext(schema: ViaductSchema) : ValidationContext(schema) {
        val types = mutableSetOf<String>()
    }
}
