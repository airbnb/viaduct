@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.gradle

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.concurrent.CancellationException
import viaduct.gradle.spi.ValidationRuleProvider
import viaduct.graphql.schema.SchemaProvenance
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

internal data class SchemaRuleDiagnostic(
    val error: SchemaValidationError,
    val provider: String,
    val ruleId: String?,
)

internal object IntegratedSchemaValidation {
    private const val SERVICE = "META-INF/services/viaduct.gradle.spi.ValidationRuleProvider"
    const val BUILT_IN_PROVIDER = "com.airbnb.viaduct"

    fun validate(
        schema: ViaductSchema,
        builtInRules: List<ValidationRule>,
        classpath: Collection<File>,
    ): List<SchemaRuleDiagnostic> {
        val loader = object : URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray(), ValidationRuleProvider::class.java.classLoader) {
            override fun getResources(name: String) = if (name == SERVICE) findResources(name) else super.getResources(name)
        }
        return loader.use {
            val thread = Thread.currentThread()
            val previousLoader = thread.contextClassLoader
            thread.contextClassLoader = loader
            try {
                val providers = contract("Service discovery on the registered validation classpath") {
                    ServiceLoader.load(ValidationRuleProvider::class.java, loader).toList()
                }
                validate(schema, builtInRules, providers)
            } finally {
                thread.contextClassLoader = previousLoader
            }
        }
    }

    fun validate(
        schema: ViaductSchema,
        builtInRules: List<ValidationRule>,
        providers: List<ValidationRuleProvider>,
    ): List<SchemaRuleDiagnostic> {
        val owners = mutableMapOf<String, String>()

        fun register(
            rules: List<ValidationRule>,
            provider: String
        ) {
            rules.forEach { rule ->
                check(rule.id.isNotBlank()) { SchemaProvenance.pluginError(listOf(provider), "Contract error: supplied a blank rule ID") }
                val previous = owners.putIfAbsent(rule.id, provider)
                check(previous == null) {
                    SchemaProvenance.pluginError(listOfNotNull(previous, provider), "Contract error: duplicate rule ID '${rule.id}'")
                }
            }
        }
        register(builtInRules, BUILT_IN_PROVIDER)
        val sharedRules = builtInRules.toMutableList()
        val privateTraversals = providers.mapNotNull { provider ->
            val owner = provider.javaClass.name
            val ruleSet = contract("$owner.createRuleSet") { provider.createRuleSet(schema) }
            val rules = contract("$owner.getRules") { ruleSet.getRules().toList() }
            register(rules, owner)
            val context = contract("$owner.createContext") { ruleSet.createContext() }
            if (context == null) {
                sharedRules.addAll(rules)
                null
            } else {
                check(context.schema === schema) { SchemaProvenance.pluginError(listOf(owner), "Contract error: returned a context for a different schema") }
                Triple(context, rules, owner)
            }
        }
        val callbackIdentities = owners.mapValues { (rule, owner) -> "$owner rule '$rule' callback" }

        fun walk(
            context: ValidationContext,
            rules: List<ValidationRule>,
            owner: String
        ): List<SchemaRuleDiagnostic> {
            context.walkSchema(rules) { rule, callback ->
                contract(callbackIdentities.getValue(rule.id), callback)
            }
            return context.errorsByRule.map { (rule, error) ->
                SchemaRuleDiagnostic(error, rule?.let { owners.getValue(it.id) } ?: owner, rule?.id)
            }
        }
        return buildList {
            addAll(walk(ValidationContext(schema), sharedRules, BUILT_IN_PROVIDER))
            privateTraversals.forEach { (context, rules, owner) -> addAll(walk(context, rules, owner)) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    private inline fun <T> contract(
        identity: String,
        action: () -> T
    ): T =
        try {
            action()
        } catch (failure: Throwable) {
            generateSequence(failure) { it.cause }.forEach { cause ->
                if (cause is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw cause
                }
                if (cause is VirtualMachineError || cause is ThreadDeath || cause is CancellationException) throw cause
            }
            throw IllegalStateException(SchemaProvenance.pluginError(listOf(identity), "Contract error"), failure)
        }
}
