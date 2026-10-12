package viaduct.engine.runtime2.benchmark

import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.bootstrap.fromObjectField
import viaduct.engine.runtime2.bootstrap.fromQueryField
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDeclaration
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

internal object ResolverBenchmarkFeaturePartition {
    private val types = linkedMapOf(
        "CoverageRoot" to ("branch: CoverageBranch computed: Int" to "seed: Int"),
        "CoverageBranch" to ("middle: CoverageMiddle bridge(seed: Int = 7): Int" to "parent: CoverageAncestor @parent seed: Int"),
        "CoverageMiddle" to ("leaf: CoverageLeaf provider: Int" to "parent: CoverageBranch @parent seed: Int"),
        "CoverageLeaf" to ("result: Int second: Int" to "parent: CoverageMiddle @parent seed: Int"),
        "CoverageValue" to ("computed(value: Int): Int other: Int" to "seed: Int passive: CoverageValue"),
        "CoverageOther" to ("computed(value: Int): Int other: Int" to "seed: Int passive: CoverageValue"),
        "CoverageNamespace" to ("next: CoverageNamespace target(a: Int = 7, b: Int = 8, c: Int = 9, d: Int = 10): CoverageAbstract" to "seed: Int"),
        "CoverageReferences" to ("object: CoverageAbstract computed: Int" to "scalar: Int kind: CoverageKind union: CoverageUnion concrete: CoverageValue numbers: [Int]"),
        "CoverageChecks" to ("computed(input: CoverageInput): Int other(value: Int): Int" to "seed: Int denied: Int passive: CoverageValue"),
        "CoverageDenied" to ("computed(value: Int): Int other(value: Int): Int" to "seed: Int passive: CoverageValue"),
    )

    val schemaSDL: String = buildString {
        appendLine("directive @parent on FIELD_DEFINITION")
        appendLine("interface CoverageAncestor { seed: Int }")
        appendLine("interface CoverageAbstract { seed: Int passive: CoverageValue computed(value: Int): Int }")
        appendLine("union CoverageUnion = CoverageValue | CoverageOther")
        appendLine("enum CoverageKind { VALUE }")
        appendLine("input CoverageInput { child: CoverageInput value: Int = 7 values: [Int] }")
        appendLine("extend type Query {")
        appendLine("coverageParent: CoverageRoot coverageSupplied: CoverageRoot coverageObject: CoverageAbstract coverageOther: CoverageAbstract coverageUse: CoverageAbstract")
        appendLine("coverageVariables(input: CoverageInput, seed: Int = 7, enabled: Boolean! = true, disabled: Boolean! = false): Int")
        appendLine("coverageNullVariables: Int coverageErrorVariables: Int coverageNull: CoverageValue coverageError: CoverageValue")
        appendLine("coverageEcho(value: Int): Int coverageList(values: [Int]): Int coverageProvider: Int coverageInputEcho(input: CoverageInput): Int")
        appendLine(
            "coverageNamespace: CoverageNamespace coverageReferences: CoverageReferences coverageFallbackReferences: CoverageReferences coverageScalar: Int coverageTail: Int coverageNumber: Int coverageKind: CoverageKind"
        )
        appendLine("coverageChecks: CoverageChecks coverageErrorChecks: CoverageChecks coverageDenied: CoverageDenied coverageConcrete(value: Int): CoverageValue coverageUnion: CoverageUnion")
        repeat(5) { appendLine("coverageChain$it(value: Int = 7): Int coverageChainEcho$it(value: Int): Int") }
        appendLine("}")
        types.forEach { (name, fields) ->
            val implements = when (name) {
                "CoverageRoot" -> "implements CoverageAncestor"
                "CoverageValue", "CoverageOther" -> "implements CoverageAbstract"
                else -> ""
            }
            val passiveCount = Regex("\\w+: ").findAll(fields.second).count()
            appendLine("type $name $implements { ${fields.first} ${fields.second}")
            repeat(14 - passiveCount) { appendLine("padding$it: Int") }
            appendLine("}")
        }
    }

    val selections: List<String> = listOf(
        "coverageParent { branch { middle { leaf { result second } } } }",
        "coverageSupplied { branch { middle { leaf { result second } } } }",
        "coverageVariables(input: {child: {value: 7, values: [1, 2]}})",
        "coverageVariables(input: {child: null})",
        "coverageVariables(input: null)",
        "coverageVariables(input: {child: {value: 7}}, enabled: false, disabled: true)",
        "coverageNullVariables",
        "coverageErrorVariables",
        "coverageChain4",
        "coverageReferences { scalar kind object { seed computed(value: 7) } union { ... on CoverageValue { seed computed(value: 7) } } concrete { computed(value: 7) seed } numbers computed }",
        "coverageOther { seed computed(value: 7) } coverageFallbackReferences { object { seed computed(value: 7) } computed }",
        "coverageChecks { computed(input: {child: {value: 7}}) denied seed other }",
        "coverageDenied { computed(value: 7) seed }",
        "coverageChecks { computed(input: null) other } coverageErrorChecks { other seed }",
        "coverageParent @include(if: true) { branch @skip(if: false) { middle { leaf { result } } } }",
        "coverageParent @include(if: false) { branch { middle { leaf { result } } } } coverageNumber",
    )

    fun decorateQueries(sources: List<String>): List<String> {
        require(sources.size in 100..250) { "The overhead corpus must contain 100–250 queries" }
        return sources.mapIndexed { index, source ->
            if (index < selections.size * 3) {
                val closing = source.lastIndexOf('}')
                source.substring(0, closing) + "\n  " + selections[index % selections.size] + "\n}"
            } else {
                source
            }
        }
    }

    fun fieldResolvers(schemas: ViaductAndGJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> = compile(schemas).first

    fun variableProviders(schemas: ViaductAndGJSchema): Map<Arguments.Variable, VariableDeclaration> = compile(schemas).second

    private fun compile(schemas: ViaductAndGJSchema): Pair<Map<ViaductSchema.Field, FieldResolverDefinition>, Map<Arguments.Variable, VariableDeclaration>> {
        val schema = schemas.loweredSchema
        val resolvers = linkedMapOf<ViaductSchema.Field, FieldResolverDefinition>()
        val variables = linkedMapOf<Arguments.Variable, VariableDeclaration>()

        fun fragment(
            type: String,
            selection: String,
            field: ViaductSchema.ObjectField
        ) = if (selection.isEmpty()) schema.emptyFragmentOf(type) else schemas.fragmentFrom("fragment Input on $type { $selection }", variableField = field)

        fun add(
            type: String,
            name: String,
            output: Any?,
            objectInput: String = "",
            queryInput: String = "",
            argumentPaths: Map<String, List<String>> = emptyMap(),
            objectPaths: Map<String, List<String>> = emptyMap(),
            queryPaths: Map<String, List<String>> = emptyMap(),
            provided: Map<String, Any?> = emptyMap()
        ) {
            val field = schema.requireObjectField(type, name)
            val objectFragment = fragment(type, objectInput, field)
            val queryFragment = fragment("Query", queryInput, field)
            var resolver = fieldResolverOf(objectFragment, queryFragment) { _, _, _ -> output }
            if (provided.isNotEmpty()) resolver = resolver.withVariablesProvider(provided.keys) { provided }
            resolvers[field] = resolver
            argumentPaths.forEach { (variable, path) -> variables[Arguments.Variable.of(field, variable)] = schema.fromArgument(field, path) }
            objectPaths.forEach { (variable, path) -> variables[Arguments.Variable.of(field, variable)] = schemas.fromObjectField("fragment Input on $type { $objectInput }", path, field) }
            queryPaths.forEach { (variable, path) -> variables[Arguments.Variable.of(field, variable)] = schemas.fromQueryField("fragment Input on Query { $queryInput }", path, field) }
        }

        fun value(
            type: String,
            fields: Map<String, Any?> = emptyMap()
        ) = schema.objectOf(type) {
            "seed" setTo (fields["seed"] ?: 7)
            repeat(10) { "padding$it" setTo it }
            fields.filterKeys { it != "seed" }.forEach { (field, data) -> field setTo data }
        }
        val leaf = value("CoverageLeaf", mapOf("parent" to value("CoverageMiddle", mapOf("seed" to 999))))
        val middle = value("CoverageMiddle", mapOf("parent" to value("CoverageBranch", mapOf("seed" to 999))))
        val suppliedMiddle = value("CoverageMiddle", mapOf("leaf" to leaf))
        val branch = value("CoverageBranch", mapOf("parent" to value("CoverageRoot", mapOf("seed" to 999))))
        val suppliedBranch = value("CoverageBranch", mapOf("middle" to suppliedMiddle))
        val root = value("CoverageRoot")
        val suppliedRoot = value("CoverageRoot", mapOf("branch" to suppliedBranch))
        val passive = value("CoverageValue")
        val objectValue = value("CoverageValue", mapOf("passive" to passive))
        val otherValue = value("CoverageOther", mapOf("passive" to passive))
        add("Query", "coverageParent", root)
        add("Query", "coverageSupplied", suppliedRoot)
        add("CoverageRoot", "branch", branch, "seed")
        add("CoverageRoot", "computed", 7)
        add("CoverageBranch", "middle", middle, "parent { seed }")
        add(
            "CoverageBranch",
            "bridge",
            7,
            "parent { seed } seed",
            "coverageProvider argument: coverageEcho(value: ${'$'}seed) object: coverageEcho(value: ${'$'}objectValue) query: coverageEcho(value: ${'$'}queryValue) provider: coverageEcho(value: ${'$'}provided)",
            argumentPaths = mapOf("seed" to listOf("seed")),
            objectPaths = mapOf("objectValue" to listOf("seed")),
            queryPaths = mapOf("queryValue" to listOf("coverageProvider")),
            provided = mapOf("provided" to 7),
        )
        add("CoverageMiddle", "leaf", leaf, "parent { parent { seed } }")
        add("CoverageMiddle", "provider", 7, "parent { parent { seed } }")
        add("CoverageLeaf", "result", 7, "parent { parent { parent { seed } bridge(seed: 7) } }")
        add("CoverageLeaf", "second", 7, "parent { provider }")
        add("Query", "coverageObject", objectValue)
        add("Query", "coverageOther", otherValue)
        add("Query", "coverageUse", objectValue)
        listOf("CoverageValue", "CoverageOther", "CoverageDenied").forEach { type ->
            add(type, "computed", 7)
            add(type, "other", 7)
        }
        add("Query", "coverageProvider", 7)
        add("Query", "coverageEcho", 7)
        add("Query", "coverageList", 7)
        add("Query", "coverageInputEcho", 7)
        val objectInput = "coverageObject { seed } coverageUse { passive { computed(value: ${'$'}objectValue) } } coverageInputEcho(input: {child: {value: ${'$'}seed, values: [${'$'}seed, ${'$'}objectValue]}}) left: coverageEcho(value: 7) right: coverageEcho(value: ${'$'}seed) repeated: coverageEcho(value: ${'$'}seed) coverageList(values: ${'$'}seed) coverageEcho(value: ${'$'}nested)"
        val queryInput = "coverageOther { seed } queryEcho: coverageEcho(value: ${'$'}queryValue) providerEcho: coverageEcho(value: ${'$'}provided) included: coverageUse @include(if: ${'$'}enabled) { computed(value: ${'$'}provided) } skipped: coverageObject @skip(if: ${'$'}disabled) { computed(value: ${'$'}provided) }"
        add(
            "Query",
            "coverageVariables",
            7,
            objectInput,
            queryInput,
            argumentPaths = mapOf("seed" to listOf("seed"), "nested" to listOf("input", "child", "value"), "enabled" to listOf("enabled"), "disabled" to listOf("disabled")),
            objectPaths = mapOf("objectValue" to listOf("coverageObject", "seed")),
            queryPaths = mapOf("queryValue" to listOf("coverageOther", "seed")),
            provided = mapOf("provided" to 7),
        )
        add("Query", "coverageNull", null)
        add("Query", "coverageError", EngineErrorData.of())
        listOf("Null", "Error").forEach { kind ->
            val input = "coverage$kind { seed } coverageEcho(value: ${'$'}value)"
            add("Query", "coverage${kind}Variables", 7, input, objectPaths = mapOf("value" to listOf("coverage$kind", "seed")))
        }
        add("Query", "coverageChain0", 7)
        for (depth in 1..4) {
            add("Query", "coverageChainEcho$depth", 7)
            val input = "coverageChain${depth - 1}(value: ${'$'}inputValue) coverageChainEcho$depth(value: ${'$'}sourceValue)"
            add(
                "Query",
                "coverageChain$depth",
                7,
                input,
                argumentPaths = mapOf("inputValue" to listOf("value")),
                objectPaths = mapOf("sourceValue" to listOf("coverageChain${depth - 1}")),
            )
        }
        val namespace = value("CoverageNamespace")
        add("Query", "coverageNamespace", namespace)
        add("CoverageNamespace", "next", namespace)
        add("CoverageNamespace", "target", objectValue, queryInput = "coverageEcho(value: ${'$'}a)", argumentPaths = mapOf("a" to listOf("a")))

        fun reference(
            vararg path: String,
            arguments: Map<String, Any?> = emptyMap()
        ): RootFieldReferenceData {
            var type = "Query"
            val fields = path.map { name -> schema.requireObjectField(type, name).also { type = it.type.baseTypeDef.name } }
            return RootFieldReferenceData.of(fields, arguments)
        }
        add("Query", "coverageNumber", 7)
        add("Query", "coverageTail", reference("coverageNumber"))
        add("Query", "coverageScalar", reference("coverageTail"))
        add("Query", "coverageKind", "VALUE")
        val objectReference = reference("coverageNamespace", "next", "next", "target", arguments = mapOf("a" to 7, "b" to 8, "c" to 9, "d" to 10))
        val references = schema.objectOf("CoverageReferences") {
            "scalar" setTo reference("coverageScalar")
            "kind" setTo reference("coverageKind")
            "object" setTo objectReference
            "union" setTo reference("coverageUnion")
            "concrete" setTo reference("coverageConcrete", arguments = mapOf("value" to 7))
            "numbers" setTo listOf(reference("coverageScalar"), reference("coverageNumber"))
        }
        add("Query", "coverageUnion", otherValue, queryInput = "coverageProvider coverageEcho(value: ${'$'}queryValue)", queryPaths = mapOf("queryValue" to listOf("coverageProvider")))
        add("Query", "coverageConcrete", objectValue, queryInput = "coverageEcho(value: ${'$'}value)", argumentPaths = mapOf("value" to listOf("value")))
        add("Query", "coverageReferences", references)
        add("Query", "coverageFallbackReferences", schema.objectOf("CoverageReferences") { "scalar" setTo 7 })
        add("CoverageReferences", "object", otherValue, "scalar")
        add("CoverageReferences", "computed", 7)
        add("Query", "coverageChecks", value("CoverageChecks", mapOf("denied" to 7, "passive" to passive)))
        add("Query", "coverageErrorChecks", value("CoverageChecks", mapOf("seed" to EngineErrorData.of(), "passive" to value("CoverageValue", mapOf("seed" to EngineErrorData.of())))))
        add("Query", "coverageDenied", value("CoverageDenied", mapOf("passive" to passive)))
        add("CoverageChecks", "computed", 7, "seed", "coverageEcho(value: ${'$'}nested)", argumentPaths = mapOf("nested" to listOf("input", "child", "value")))
        add("CoverageChecks", "other", 7)
        return resolvers to variables
    }

    private fun templates(
        schemas: ViaductAndGJSchema,
        target: ResolverTarget,
        type: String
    ): Map<String, ResolverFragmentTemplates> {
        val objectFragment = schemas.fragmentFrom(
            "fragment Input on $type { seed passive { seed } other(value: ${'$'}objectValue) @include(if: ${'$'}enabled) }",
            variableTarget = target
        ).materializeSelections
        val argumentField = (target as? ResolverTarget.FieldCheckerTarget)?.field?.takeIf { it.name == "computed" }
        val argumentSelection = if (argumentField != null) "argumentEcho: coverageEcho(value: ${'$'}argumentValue)" else ""
        val queryFragment = schemas.fragmentFrom(
            "fragment Input on Query { $argumentSelection coverageProvider coverageEcho(value: ${'$'}objectValue) coverageList(values: ${'$'}queryValue) coverageNumber @include(if: ${'$'}enabled) }",
            variableTarget = target
        ).materializeSelections
        val providedVariables = mapOf("enabled" to true)
        val pair = ResolverFragmentTemplates(
            objectFragmentTemplate = objectFragment,
            queryFragmentTemplate = queryFragment,
            variables = (
                if (argumentField == null) {
                    emptyMap()
                } else {
                    mapOf(
                        Arguments.Variable.of(target, "argumentValue") to VariableDefinition.FromArgument.of(
                            argumentField.args.single(),
                            listOf(
                                (argumentField.args.single().type.baseTypeDef as ViaductSchema.Input).field("child")!!,
                                (argumentField.args.single().type.baseTypeDef as ViaductSchema.Input).field("value")!!
                            ),
                        ),
                    )
                }
            ) + mapOf(
                Arguments.Variable.of(target, "objectValue") to VariableDefinition.FromField.of(
                    ProviderFragment.OBJECT,
                    listOf(
                        objectFragment.filter {
                            it.responseKey == "passive"
                        }.single().key,
                        objectFragment.filter { it.responseKey == "passive" }.single().subselections.single().key
                    ),
                    listOf("passive", "seed")
                ),
                Arguments.Variable.of(target, "queryValue") to VariableDefinition.FromField.of(
                    ProviderFragment.QUERY,
                    listOf(
                        queryFragment.filter {
                            it.responseKey == "coverageProvider"
                        }.single().key
                    ),
                    listOf("coverageProvider")
                ),
                Arguments.Variable.of(target, "enabled") to VariableDefinition.FromProvider,
            ),
            variablesProvider = { providedVariables },
        )
        return mapOf("policy" to pair, "empty" to ResolverFragmentTemplates(materializeSelectionForestOf(), materializeSelectionForestOf())) +
            if (argumentField == null) emptyMap() else mapOf("duplicate" to pair)
    }

    fun fieldCheckers(schemas: ViaductAndGJSchema): Map<ViaductSchema.ObjectField, FieldCheckerResolver> {
        val schema = schemas.loweredSchema
        val denied = schema.requireObjectField("CoverageChecks", "denied")
        val computed = schema.requireObjectField("CoverageChecks", "computed")
        val leaf = schema.requireObjectField("CoverageLeaf", "result")
        return mapOf(
            denied to FieldCheckerResolver.of(denied, schema.requireQueryTypeDef(), templates(schemas, ResolverTarget.FieldCheckerTarget(denied), "CoverageChecks")) { _, _, _ -> DENIAL },
            leaf to FieldCheckerResolver.of(
                leaf,
                schema.requireQueryTypeDef(),
                mapOf(
                    "ancestor" to ResolverFragmentTemplates(
                        schemas.fragmentFrom(
                            "fragment Input on CoverageLeaf { parent { parent { parent { seed } } } }",
                            variableTarget = ResolverTarget.FieldCheckerTarget(leaf)
                        ).materializeSelections,
                        materializeSelectionForestOf(),
                    ),
                )
            ) { _, _, _ -> CheckerResult.Success },
            computed to FieldCheckerResolver.of(
                computed,
                schema.requireQueryTypeDef(),
                templates(schemas, ResolverTarget.FieldCheckerTarget(computed), "CoverageChecks")
            ) { _, _, _ -> CheckerResult.Success },
        )
    }

    fun typeCheckers(schemas: ViaductAndGJSchema): Map<ViaductSchema.Object, TypeCheckerResolver> =
        listOf("CoverageChecks", "CoverageDenied").associate { name ->
            val type = schemas.loweredSchema.requireType(name) as ViaductSchema.Object
            val outcome = if (name == "CoverageDenied") DENIAL else CheckerResult.Success
            type to TypeCheckerResolver.of(type, schemas.loweredSchema.requireQueryTypeDef(), templates(schemas, ResolverTarget.TypeCheckerTarget(type), name)) { _, _ -> outcome }
        }

    private val DENIAL = object : CheckerResult.Error {
        override val error: Exception = IllegalAccessException("Prepared benchmark denial")

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this

        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true
    }
}
