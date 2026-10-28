package viaduct.engine.runtime2.benchmark

import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.fromSDL
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD
import viaduct.graphql.schema.ViaductSchema

/** A single, always-active overhead fixture; source counts inform shapes, not traffic weights. */
class ChinaActiveBenchmarkCheckers(corpus: ResolverBenchmarkCorpus, private val comparisonMode: Boolean = false) {
    private val original = ViaductAndGJSchema.fromSDL(corpus.schemaSDL).loweredSchema
    private val objects = original.types.values.filterIsInstance<ViaductSchema.Object>()
        .filter { it.name.matches(Regex("Object\\d+")) }.sortedBy { it.name.removePrefix("Object").toInt() }
    private val identifiers = objects.associate { type ->
        type.name to type.fields.filter {
            it.name != LOWERED_TYPENAME_FIELD && it.args.isEmpty() && !it.type.isList && it.type.baseTypeDef.name in setOf("ID", "String", "Int") &&
                FieldCoordinate(type.name, it.name) !in corpus.registry.fieldResolverCoordinates
        }.minBy { field ->
            when {
                field.name == "id" -> 0
                field.type.baseTypeDef.name == "ID" -> 1
                field.type.baseTypeDef.name == "String" -> 2
                else -> 3
            }
        }.name
    }

    // Select a third of ordinary fields for custom China rules. The rest retain conservative
    // fragmentless default-block checks; helper/hash fields are outside the policy surface.
    val fieldPolicies: Map<FieldCoordinate, List<String>> = buildMap<FieldCoordinate, List<String>> {
        var ordinal = 0
        objects.forEach { type ->
            type.fields.filter { !it.name.startsWith("__") && it.name != LOWERED_TYPENAME_FIELD && it.type.baseTypeDef.name != "Hash" }.forEach { field ->
                val china = if (field.name == identifiers.getValue(type.name)) {
                    "defaultBlock"
                } else if (ordinal % 3 == 0) {
                    if ((ordinal / 3 * 37) % 100 < 6) "chinaNull" else "chinaQuery"
                } else {
                    "defaultBlock"
                }
                val privacyCount = when ((ordinal * 37) % 100) {
                    0 -> 2
                    in 1..41 -> 1
                    else -> 0
                }
                put(FieldCoordinate(type.name, field.name), listOf(china) + List(privacyCount) { "privacy${((ordinal + it) * 53 + 17) % 100}" })
                ordinal++
            }
        }
        original.requireQueryTypeDef().fields.filter { !it.name.startsWith("__") && it.name != LOWERED_TYPENAME_FIELD }.forEachIndexed { index, field ->
            put(FieldCoordinate("Query", field.name), if (index % 7 == 0) emptyList() else listOf("queryPrivacy"))
        }
    }.filterKeys { coordinate -> !comparisonMode || coordinate.fieldName != identifiers[coordinate.typeName] }

    val typePolicies: Map<String, List<String>> = objects.mapIndexed { index, type ->
        type.name to when (index % 13) {
            in 0..2 -> emptyList()
            in 3..6 -> listOf("scalarPrivacy")
            in 7..10 -> listOf("nullPrivacy")
            11 -> listOf("nestedPrivacy")
            else -> listOf("scalarPrivacy", "nullPrivacy")
        }
    }.toMap()

    private val privacyFields: Map<Pair<String, Int>, String> = buildMap {
        if (comparisonMode) {
            val policies = fieldPolicies.mapKeys { it.key.toString() } + typePolicies
            policies.forEach { (owner, slots) ->
                slots.forEachIndexed { index, policy ->
                    if (usesQueryPrivacy(policy)) put(owner to index, "benchmarkPrivacy$size")
                }
            }
        }
    }
    private val privacyFieldNames = if (comparisonMode) privacyFields.values.toList() else listOf("benchmarkPrivacy")

    val schemaSDL: String = corpus.schemaSDL + "\n" + buildString {
        appendLine("extend type Query {")
        objects.forEach { type ->
            val idType = type.field(identifiers.getValue(type.name))!!.type.baseTypeDef.name
            appendLine("  benchmarkChina${type.name}(id: $idType): BenchmarkChina!")
        }
        privacyFieldNames.forEach { appendLine("  $it: BenchmarkPrivacy!") }
        appendLine("}")
        appendLine("type BenchmarkPrivacy { allowed: Boolean! }")
        appendLine(
            "type BenchmarkChina { country: String! allowed: Boolean! dataScope: BenchmarkDataScope! location: BenchmarkLocation! host: BenchmarkUser! guest: BenchmarkUser! users: [BenchmarkUser!]! }"
        )
        appendLine("type BenchmarkDataScope { synthetic: Boolean! }")
        appendLine("type BenchmarkLocation { defaultAddress: BenchmarkAddress! }")
        appendLine("type BenchmarkAddress { country: String! }")
        appendLine("type BenchmarkUser { country: String! allowed: Boolean! dataScope: BenchmarkDataScope! }")
    }

    fun fieldResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> {
        val canonical = schema.loweredSchema
        val dataScope = canonical.objectOf("BenchmarkDataScope") { "synthetic" setTo false }
        val user = canonical.objectOf("BenchmarkUser") {
            "country" setTo "CN"
            "allowed" setTo true
            "dataScope" setTo dataScope
        }
        val address = canonical.objectOf("BenchmarkAddress") { "country" setTo "CN" }
        val location = canonical.objectOf("BenchmarkLocation") { "defaultAddress" setTo address }
        val china = canonical.objectOf("BenchmarkChina") {
            "country" setTo "CN"
            "allowed" setTo true
            "dataScope" setTo dataScope
            "location" setTo location
            "host" setTo user
            "guest" setTo user
            "users" setTo listOf(user, user)
        }
        val privacy = canonical.objectOf("BenchmarkPrivacy") { "allowed" setTo true }
        val empty = canonical.emptyFragmentOf("Query")
        return objects.associate { type ->
            canonical.requireObjectField("Query", "benchmarkChina${type.name}") to
                fieldResolverOf(empty) { _, _ -> china }
        } + privacyFieldNames.associate { name -> canonical.requireObjectField("Query", name) to fieldResolverOf(empty) { _, _ -> privacy } }
    }

    fun fieldCheckers(schema: ViaductAndGJSchema): Map<ViaductSchema.ObjectField, FieldCheckerResolver> =
        fieldPolicies.map { (coordinate, policies) ->
            val field = schema.loweredSchema.requireObjectField(coordinate.typeName, coordinate.fieldName)
            val target = ResolverTarget.FieldCheckerTarget(field)
            field to FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), templates(schema, target, coordinate.typeName, coordinate.toString(), policies)) { _, _, _ ->
                CheckerResult.Success
            }
        }.toMap()

    fun typeCheckers(schema: ViaductAndGJSchema): Map<ViaductSchema.Object, TypeCheckerResolver> =
        typePolicies.map { (typeName, policies) ->
            val type = schema.loweredSchema.requireType(typeName) as ViaductSchema.Object
            val target = ResolverTarget.TypeCheckerTarget(type)
            type to TypeCheckerResolver.of(type, schema.loweredSchema.requireQueryTypeDef(), templates(schema, target, typeName, typeName, policies)) { _, _ ->
                CheckerResult.Success
            }
        }.toMap()

    private fun templates(
        schema: ViaductAndGJSchema,
        target: ResolverTarget,
        typeName: String,
        privacyOwner: String,
        policies: List<String>,
    ): Map<String, ResolverFragmentTemplates> {
        if (policies == listOf("defaultBlock")) return emptyMap()
        return policies.mapIndexed { index, policy ->
            val pair = when {
                policy == "chinaQuery" -> chinaTemplate(schema, target, typeName)
                usesQueryPrivacy(policy) -> {
                    val field = if (comparisonMode) privacyFields.getValue(privacyOwner to index) else "benchmarkPrivacy"
                    pair(schema, target, query = "$field { allowed }")
                }
                policy == "scalarPrivacy" -> pair(schema, target, objectSelection = identifiers.getValue(typeName), typeName = typeName)
                policy.startsWith("privacy") -> {
                    val percentile = policy.removePrefix("privacy").toInt()
                    // Context-only policies gain a null slot inside a composite, as in production.
                    when (percentile) {
                        in 15..85 -> pair(schema, target, objectSelection = identifiers.getValue(typeName), typeName = typeName)
                        else -> pair(schema, target)
                    }
                }
                else -> pair(schema, target)
            }
            "${index}_$policy" to pair
        }.toMap()
    }

    private fun usesQueryPrivacy(policy: String): Boolean = policy == "queryPrivacy" || policy == "nestedPrivacy" || policy.removePrefix("privacy").toIntOrNull()?.let { it in 86..94 } == true

    private fun pair(
        schema: ViaductAndGJSchema,
        target: ResolverTarget,
        objectSelection: String? = null,
        typeName: String = "Query",
        query: String? = null,
    ): ResolverFragmentTemplates =
        ResolverFragmentTemplates(
            objectFragmentTemplate = objectSelection?.let {
                schema.fragmentFrom("fragment Input on $typeName { $it }", variableTarget = target).materializeSelections
            } ?: materializeSelectionForestOf(),
            queryFragmentTemplate = query?.let {
                schema.fragmentFrom("fragment Input on Query { $it }", variableTarget = target).materializeSelections
            } ?: materializeSelectionForestOf(),
        )

    private fun chinaTemplate(
        schema: ViaductAndGJSchema,
        target: ResolverTarget,
        typeName: String
    ): ResolverFragmentTemplates {
        val identifier = identifiers.getValue(typeName)
        val skip = "shouldSkipForChinaAccess"
        val objectTemplate = schema.fragmentFrom(
            "fragment Input on $typeName { policyId: $identifier @skip(if: ${'$'}$skip) }",
            variableTarget = target,
        ).materializeSelections
        val index = typeName.removePrefix("Object").toInt()
        val selection = when (index % 4) {
            0 -> "country allowed dataScope { synthetic }"
            1 -> "location { defaultAddress { country } } allowed"
            2 -> "location { defaultAddress { country } } host { country allowed dataScope { synthetic } } guest { country allowed dataScope { synthetic } }"
            else -> "users { country allowed dataScope { synthetic } }"
        }
        val queryTemplate = schema.fragmentFrom(
            "fragment Input on Query { benchmarkChina$typeName(id: ${'$'}id) @skip(if: ${'$'}$skip) { $selection } __typename }",
            variableTarget = target,
        ).materializeSelections
        return ResolverFragmentTemplates(
            objectFragmentTemplate = objectTemplate,
            queryFragmentTemplate = queryTemplate,
            variables = mapOf(
                Arguments.Variable.of(target, "id") to VariableDefinition.FromField.of(
                    ProviderFragment.OBJECT,
                    listOf(objectTemplate.single().key),
                    listOf("policyId"),
                ),
                Arguments.Variable.of(target, skip) to VariableDefinition.FromProvider,
            ),
            variablesProvider = { mapOf(skip to false) },
        )
    }
}
