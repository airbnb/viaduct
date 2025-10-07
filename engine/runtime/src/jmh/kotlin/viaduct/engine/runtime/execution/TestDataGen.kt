package viaduct.engine.runtime.execution

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.Directives
import graphql.language.Directive
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.VariableDefinition
import graphql.language.VariableReference
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.string
import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.ConfigKey
import viaduct.arbitrary.common.Unvalidated
import viaduct.arbitrary.graphql.DescriptionLength
import viaduct.arbitrary.graphql.DirectiveWeight
import viaduct.arbitrary.graphql.FieldSelectionWeight
import viaduct.arbitrary.graphql.FragmentDefinitionWeight
import viaduct.arbitrary.graphql.FragmentSpreadWeight
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.InlineFragmentWeight
import viaduct.arbitrary.graphql.InputObjectTypeSize
import viaduct.arbitrary.graphql.ListValueSize
import viaduct.arbitrary.graphql.MaxSelectionSetDepth
import viaduct.arbitrary.graphql.OperationCount
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.TypeType
import viaduct.arbitrary.graphql.TypeTypeWeights
import viaduct.arbitrary.graphql.asDocument
import viaduct.arbitrary.graphql.asIntRange
import viaduct.arbitrary.graphql.asSchema
import viaduct.arbitrary.graphql.filterNotNull
import viaduct.arbitrary.graphql.graphQLExecutionInput
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.graphql.utils.allChildren

data class TestData(val sdl: String, val query: String, val variables: Map<String, Any?> = emptyMap()) {
    companion object {
        /**
         * Example:
         * TestData.loadFromResources("many-fragments-1")
         */
        fun loadFromResources(
            simpleName: String,
            prefix: String = "/schemas_and_queries/"
        ): TestData =
            TestData(
                sdl = load("${prefix}$simpleName-schema.graphqls"),
                query = load("${prefix}$simpleName-query.graphql"),
                variables = maybeLoad("${prefix}$simpleName-variables.json")?.let { text ->
                    @Suppress("UNCHECKED_CAST")
                    ObjectMapper().readValue(text, Map::class.java) as Map<String, Any?>
                } ?: emptyMap()
            )

        fun load(fqPath: String): String = requireNotNull(maybeLoad(fqPath)) { "Resource not found: $fqPath" }

        fun maybeLoad(fqPath: String): String? =
            TestData::class.java.getResourceAsStream(fqPath)
                ?.bufferedReader()
                ?.use { it.readText() }
    }
}

object TestDataGen {
    @JvmStatic
    @OptIn(ExperimentalTime::class)
    fun main(args: Array<String>) {
        // Tune these values to shape the generated data
        val cfg = Config.default +
            (DescriptionLength to 0.asIntRange()) +
            (GenInterfaceStubsIfNeeded to true) +
            (OperationCount to 1.asIntRange()) +
            (ListValueSize to 1.asIntRange()) +
            (InputObjectTypeSize to 3.asIntRange()) +
            (SchemaSize to 1_000) +
            (
                TypeTypeWeights to mapOf(
                    // generate a schema that is relatively dense in object and union types
                    TypeType.Object to 5.0,
                    TypeType.Union to 3.0,
                    TypeType.Input to 1.0,
                    TypeType.Enum to 1.0,
                    TypeType.Interface to 1.0,
                    TypeType.Directive to 0.0
                )
            ) +
            (MaxSelectionSetDepth to 30) +
            (FieldSelectionWeight to CompoundingWeight(.7, 20)) +
            (InlineFragmentWeight to CompoundingWeight(.3, 10)) +
            (FragmentSpreadWeight to CompoundingWeight(.7, 10)) +
            (FragmentDefinitionWeight to .5) +
            (DirectiveWeight to CompoundingWeight(.05, 3)) +
            (MinQueryLength to 400_000) +
            (MinVariablesCount to 40)

        val rs = args.firstOrNull()
            ?.let { RandomSource.seeded(it.toLong()) }
            ?: RandomSource.default()

        val (testData, genDuration) = measureTimedValue {
            // genTestData will print a "." for each generation. To make this nice, don't include a newline in this first print
            print("Generating...")
            genTestData(cfg, rs)
        }
        println(" (took ${genDuration.inWholeSeconds}s)")

        val files = mkFiles(rs)
        files.write(testData)

        val (stats, statsDuration) = measureTimedValue {
            TestDataStats(testData)
        }

        println(
            """
            |
            |Wrote generated files:
            |  sdl:         ${files.sdl.absolutePath}
            |  query:       ${files.query.absolutePath}
            |  variables:   ${files.variables.absolutePath}
            |
            |Stats (took ${statsDuration.inWholeMilliseconds}ms):
            |${stats.format(indent = "  ")}
            |
            |Next steps:
            | - inspect these files to make sure they meet your needs, then
            | - rename them to something human-friendly
            | - move them to where you'd like to use them
            """.trimMargin()
        )
    }

    private fun mkFiles(rs: RandomSource): Files =
        Arb.string(8, Codepoint.alphanumeric())
            .map { baseName ->
                val sdl = mkFile("$baseName-schema.graphqls") ?: return@map null
                val query = mkFile("$baseName-query.graphql") ?: return@map null
                val variables = mkFile("$baseName-variables.json") ?: return@map null

                Files(sdl, query, variables)
            }
            .filterNotNull()
            .next(rs)

    private fun mkFile(path: String): File? = File(path).takeUnless { it.exists() }

    private data class Files(val sdl: File, val query: File, val variables: File) {
        fun write(data: TestData) {
            sdl.writeText(data.sdl)
            query.writeText(data.query)
            variables.writeText(ObjectMapper().writeValueAsString(data.variables))
        }
    }

    private fun genTestData(
        cfg: Config,
        rs: RandomSource
    ): TestData {
        val schema = Arb.graphQLSchema(cfg).next(rs)
        val inp = Arb.graphQLExecutionInput(schema, cfg)
            .filter {
                // as a simple progress bar, print a dot on every generation attempt
                print(".")
                it.query.length >= cfg[MinQueryLength] && it.variables.size >= cfg[MinVariablesCount]
            }.next(rs)
        val sdl = SchemaPrinter().print(schema)
        return TestData(sdl, inp.query, inp.variables)
    }
}

object MinQueryLength : ConfigKey<Int>(0, Unvalidated)

object MinVariablesCount : ConfigKey<Int>(0, Unvalidated)

data class TestDataStats private constructor(
    val schemaStats: Map<String, Int>,
    val queryStats: Map<String, Int>,
) {
    fun format(indent: String): String =
        buildString {
            append(formatMap("schema", schemaStats, indent))
            append(formatMap("query", queryStats, indent))
        }

    private fun formatMap(
        label: String,
        map: Map<String, Int>,
        indent: String
    ): String =
        buildString {
            append(label.prependIndent(indent) + ":\n")
            map.forEach { (k, v) ->
                append(k.prependIndent(indent + indent) + ": $v\n")
            }
        }

    companion object {
        /** derive a [TestDataStats] from a [TestData] */
        operator fun invoke(data: TestData): TestDataStats {
            val schema = data.sdl.asSchema
            val doc = data.query.asDocument

            return TestDataStats(
                schemaStats = mkSchemaStats(data, schema),
                queryStats = mkQueryStats(data, doc)
            )
        }

        private fun mkSchemaStats(
            data: TestData,
            schema: GraphQLSchema
        ): Map<String, Int> {
            var objectTypes = 0
            var objectFields = 0
            var unionTypes = 0
            var listTypedFields = 0
            var nonNullFields = 0
            var interfaceTypes = 0
            var interfaceFields = 0
            var enumTypes = 0
            var concretizations = 0

            schema.typeMap.forEach { _, type ->
                when (type) {
                    is GraphQLObjectType -> {
                        objectTypes++
                        if (type.interfaces.isNotEmpty()) concretizations++
                        objectFields += type.fields.size

                        type.fields.forEach { f ->
                            var ftype = f.type
                            while (GraphQLTypeUtil.isWrapped(ftype)) {
                                if (ftype is GraphQLList) {
                                    listTypedFields++
                                }
                                if (ftype is GraphQLNonNull) {
                                    nonNullFields++
                                }
                                ftype = GraphQLTypeUtil.unwrapOneAs(ftype)
                            }
                        }
                    }
                    is GraphQLUnionType -> {
                        unionTypes++
                        concretizations += type.types.size
                    }
                    is GraphQLEnumType -> enumTypes++
                    is GraphQLInterfaceType -> {
                        interfaceTypes++
                        interfaceFields += type.fields.size
                    }
                    else -> {}
                }
            }

            return mapOf(
                "sdl length" to data.sdl.length,
                "type definitions" to schema.typeMap.size,
                "object definitions" to objectTypes,
                "object fields" to objectFields,
                "list-typed object fields" to listTypedFields,
                "non-nullable object fields" to nonNullFields,
                "union definitions" to unionTypes,
                "interface definitions" to interfaceTypes,
                "interface fields" to interfaceFields,
                "enum definitions" to enumTypes,
                "type concretizations" to concretizations,
            )
        }

        private fun mkQueryStats(
            data: TestData,
            document: Document
        ): Map<String, Int> {
            var fieldSelections = 0
            var aliasedSelections = 0
            var argumentedSelections = 0
            var fragmentSpreads = 0
            var inlineFragments = 0
            var fragmentDefinitions = 0
            var variableReferences = 0
            var variableDefinitions = 0
            var skipIncludes = 0

            document.allChildren.forEach {
                when (val node = it) {
                    is Field -> {
                        fieldSelections++
                        if (node.alias != null) {
                            aliasedSelections++
                        }
                        if (node.arguments.isNotEmpty()) {
                            argumentedSelections++
                        }
                    }
                    is FragmentDefinition -> fragmentDefinitions++
                    is FragmentSpread -> fragmentSpreads++
                    is InlineFragment -> inlineFragments++
                    is VariableReference -> variableReferences++
                    is VariableDefinition -> variableDefinitions++
                    is Directive -> {
                        if (node.name == Directives.SkipDirective.name || node.name == Directives.IncludeDirective.name) {
                            skipIncludes++
                        }
                    }
                    else -> {}
                }
            }

            return mapOf(
                "query length" to data.query.length,
                "query lines" to data.query.lines().size,
                "field selections" to fieldSelections,
                "fragment definitions" to fragmentDefinitions,
                "fragment spreads" to fragmentSpreads,
                "inline fragments" to inlineFragments,
                "variable definitions" to variableDefinitions,
                "variable references" to variableReferences,
                "skips and includes" to skipIncludes,
            )
        }
    }
}
