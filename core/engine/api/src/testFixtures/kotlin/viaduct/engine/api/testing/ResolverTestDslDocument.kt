package viaduct.engine.api.testing

import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.IntValue
import graphql.language.ObjectField
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value as GraphQLValue
import graphql.parser.Parser

class ResolverTestDslDocument private constructor(
    val schemaSDL: String,
    val fieldDefinitions: List<DslFieldResolver>,
    val nodeDefinitions: List<DslNodeResolver>,
    val checkerDefinitions: List<DslChecker>,
) {
    companion object {
        fun parse(
            source: String,
            includeBuiltInSchema: Boolean = true,
        ): ResolverTestDslDocument {
            val document = Parser.parse(source)
            val fields = mutableListOf<DslFieldResolver>()
            val nodes = mutableListOf<DslNodeResolver>()
            val checkers = mutableListOf<DslChecker>()

            document.definitions.forEach { definition ->
                when (definition) {
                    is ObjectTypeExtensionDefinition ->
                        collectObjectDefinition(
                            definition.name,
                            definition.directives,
                            definition.fieldDefinitions,
                            fields,
                            nodes,
                            checkers,
                        )
                    is ObjectTypeDefinition ->
                        collectObjectDefinition(
                            definition.name,
                            definition.directives,
                            definition.fieldDefinitions,
                            fields,
                            nodes,
                            checkers,
                        )
                }
            }

            require(fields.map { it.typeName to it.fieldName }.distinct().size == fields.size) {
                "A field may carry only one @$RESOLVER_DIRECTIVE directive"
            }
            require(nodes.map { it.typeName }.distinct().size == nodes.size) {
                "An object may carry only one @$NODE_RESOLVER_DIRECTIVE directive"
            }
            require(checkers.map { it.typeName to it.fieldName }.distinct().size == checkers.size) {
                "A field or object may carry only one @$CHECKER_DIRECTIVE directive"
            }

            val stripped =
                document.transform { builder ->
                    builder.definitions(document.definitions.map(::stripDslDirectives))
                }
            return ResolverTestDslDocument(
                schemaSDL = (if (includeBuiltInSchema) BUILT_IN_SCHEMA + "\n" else "") + AstPrinter.printAst(stripped),
                fieldDefinitions = fields,
                nodeDefinitions = nodes,
                checkerDefinitions = checkers,
            )
        }

        private fun collectObjectDefinition(
            typeName: String,
            directives: List<Directive>,
            fieldDefinitions: List<FieldDefinition>,
            fields: MutableList<DslFieldResolver>,
            nodes: MutableList<DslNodeResolver>,
            checkers: MutableList<DslChecker>,
        ) {
            collectCheckers(typeName, null, directives, checkers)
            directives
                .filter { it.name == NODE_RESOLVER_DIRECTIVE }
                .forEach { directive ->
                    requireOnlyArguments(directive, setOf(IDS_ARGUMENT, SCRIPT_ARGUMENT, IS_SELECTIVE_ARGUMENT))
                    val script = directive.requiredArgument(SCRIPT_ARGUMENT)
                    require(script is StringValue) { "@$NODE_RESOLVER_DIRECTIVE.script must be a string" }
                    val ids = directive.requiredArgument(IDS_ARGUMENT)
                    require(ids is ArrayValue && ids.values.isNotEmpty()) { "@$NODE_RESOLVER_DIRECTIVE.ids must be a nonempty list" }
                    nodes += DslNodeResolver(typeName, ids.values.map(::parseId), script.requiredValue(), directive.isSelective())
                }

            fieldDefinitions.forEach { field ->
                collectCheckers(typeName, field.name, field.directives, checkers)
                field.directives
                    .filter { it.name == RESOLVER_DIRECTIVE }
                    .forEach { directive ->
                        requireOnlyArguments(
                            directive,
                            setOf(
                                OF_ARGUMENT,
                                QF_ARGUMENT,
                                PATH_VARS_ARGUMENT,
                                VARIABLES_ARGUMENT,
                                VARS_ARGUMENT,
                                SCRIPT_ARGUMENT,
                                IS_SELECTIVE_ARGUMENT,
                            ),
                        )
                        val script = directive.requiredArgument(SCRIPT_ARGUMENT)
                        require(script is StringValue) { "@$RESOLVER_DIRECTIVE.$SCRIPT_ARGUMENT must be a string" }
                        val of =
                            directive.argument(OF_ARGUMENT)?.let { value ->
                                require(value is StringValue) {
                                    "@$RESOLVER_DIRECTIVE.$OF_ARGUMENT must be a string"
                                }
                                value.requiredValue()
                            }.orEmpty()
                        val qf =
                            directive.argument(QF_ARGUMENT)?.let { value ->
                                require(value is StringValue) {
                                    "@$RESOLVER_DIRECTIVE.$QF_ARGUMENT must be a string"
                                }
                                value.requiredValue()
                            }.orEmpty()
                        val pathVariables =
                            directive.argument(PATH_VARS_ARGUMENT)
                                ?.let(::parsePathVariables)
                                .orEmpty()
                        val variablesProvider = directive.argument(VARS_ARGUMENT)?.let(::parseVariablesProvider)
                        fields +=
                            DslFieldResolver(
                                typeName = typeName,
                                fieldName = field.name,
                                of = of,
                                qf = qf,
                                pathVariables = pathVariables,
                                variables = directive.argument(VARIABLES_ARGUMENT)?.let(::parseVariables).orEmpty(),
                                variablesProvider = variablesProvider,
                                script = script.requiredValue(),
                                isSelective = directive.isSelective(),
                            )
                    }
            }
        }

        private fun Directive.isSelective(): Boolean =
            argument(IS_SELECTIVE_ARGUMENT)?.let { value ->
                require(value is BooleanValue) { "@$name.$IS_SELECTIVE_ARGUMENT must be a boolean" }
                value.isValue
            } ?: true

        private fun collectCheckers(
            typeName: String,
            fieldName: String?,
            directives: List<Directive>,
            checkers: MutableList<DslChecker>,
        ) {
            directives.filter { it.name == CHECKER_DIRECTIVE }.forEach { directive ->
                requireOnlyArguments(directive, setOf(INPUTS_ARGUMENT, SCRIPT_ARGUMENT))
                val script = directive.requiredArgument(SCRIPT_ARGUMENT)
                require(script is StringValue) { "@$CHECKER_DIRECTIVE.script must be a string" }
                val inputs = directive.argument(INPUTS_ARGUMENT)?.let { value ->
                    val entries = when (value) {
                        is ArrayValue -> value.values
                        is ObjectValue -> listOf(value)
                        else -> throw IllegalArgumentException("@$CHECKER_DIRECTIVE.inputs must be a list")
                    }
                    entries.map { entry ->
                        require(entry is ObjectValue) { "Checker inputs must be objects" }
                        val fields = entry.uniqueFields("checker input")
                        require(NAME_FIELD in fields && fields.keys.all { it in setOf(NAME_FIELD, OF_ARGUMENT, QF_ARGUMENT, VARIABLES_ARGUMENT, VARS_ARGUMENT) }) {
                            "Checker inputs require name and optional of, qf, variables, vars"
                        }
                        val name = fields.getValue(NAME_FIELD)
                        require(name is StringValue && GRAPHQL_NAME.matches(name.requiredValue())) { "Checker input name must be a GraphQL name" }

                        fun selections(key: String): String =
                            fields[key]?.let {
                                require(it is StringValue) { "Checker input $key must be a string" }
                                it.requiredValue()
                            }.orEmpty()
                        DslCheckerInput(
                            name.requiredValue(),
                            selections(OF_ARGUMENT),
                            selections(QF_ARGUMENT),
                            fields[VARIABLES_ARGUMENT]?.let(::parseVariables).orEmpty(),
                            fields[VARS_ARGUMENT]?.let(::parseVariablesProvider),
                        )
                    }
                }.orEmpty()
                require(inputs.map { it.name }.distinct().size == inputs.size) { "Checker input names must be unique" }
                checkers += DslChecker(typeName, fieldName, inputs, script.requiredValue())
            }
        }

        private fun parsePathVariables(value: GraphQLValue<*>): List<DslPathVariable> {
            val values =
                when (value) {
                    is ArrayValue -> value.values
                    is ObjectValue -> listOf(value)
                    else ->
                        throw IllegalArgumentException(
                            "@$RESOLVER_DIRECTIVE.$PATH_VARS_ARGUMENT must be a list",
                        )
                }
            return values.map { entry ->
                require(entry is ObjectValue) {
                    "$PATH_VARS_ARGUMENT entries must be objects"
                }
                val fields = entry.uniqueFields("$PATH_VARS_ARGUMENT entry")
                require(fields.keys == setOf(NAME_FIELD, PATH_FIELD)) {
                    "$PATH_VARS_ARGUMENT entries require exactly name and path"
                }
                val name = fields.getValue(NAME_FIELD)
                require(name is StringValue && GRAPHQL_NAME.matches(name.requiredValue())) {
                    "$PATH_VARS_ARGUMENT.name must be a GraphQL name"
                }
                val path = fields.getValue(PATH_FIELD)
                require(path is ArrayValue && path.values.isNotEmpty()) {
                    "$PATH_VARS_ARGUMENT.path must be a nonempty list"
                }
                DslPathVariable(
                    name = name.requiredValue(),
                    path =
                        path.values.map { component ->
                            require(
                                component is StringValue &&
                                    GRAPHQL_NAME.matches(component.requiredValue()),
                            ) {
                                "$PATH_VARS_ARGUMENT.path components must be GraphQL names"
                            }
                            component.requiredValue()
                        },
                )
            }.also { definitions ->
                require(definitions.map { it.name }.distinct().size == definitions.size) {
                    "$PATH_VARS_ARGUMENT variable names must be unique"
                }
            }
        }

        private fun parseVariablesProvider(value: GraphQLValue<*>): DslVariablesProvider {
            require(value is ObjectValue) { "@$RESOLVER_DIRECTIVE.$VARS_ARGUMENT must be an object" }
            val fields = value.uniqueFields(VARS_ARGUMENT)
            require(fields.keys == setOf("names", SCRIPT_ARGUMENT)) { "$VARS_ARGUMENT requires exactly names and script" }
            val names = fields.getValue("names")
            require(names is ArrayValue && names.values.isNotEmpty()) { "$VARS_ARGUMENT.names must be a nonempty list" }
            val parsedNames = names.values.map { name ->
                require(name is StringValue && GRAPHQL_NAME.matches(name.requiredValue())) { "$VARS_ARGUMENT.names must contain GraphQL names" }
                name.requiredValue()
            }
            require(parsedNames.distinct().size == parsedNames.size) { "$VARS_ARGUMENT.names must be unique" }
            val script = fields.getValue(SCRIPT_ARGUMENT)
            require(script is StringValue) { "$VARS_ARGUMENT.script must be a string" }
            return DslVariablesProvider(parsedNames.toSet(), script.requiredValue())
        }

        private fun parseVariables(value: GraphQLValue<*>): List<DslVariable> {
            val values = when (value) {
                is ArrayValue -> value.values
                is ObjectValue -> listOf(value)
                else -> throw IllegalArgumentException("@$RESOLVER_DIRECTIVE.$VARIABLES_ARGUMENT must be a list")
            }
            return values.map { entry ->
                require(entry is ObjectValue) { "$VARIABLES_ARGUMENT entries must be objects" }
                val fields = entry.uniqueFields("$VARIABLES_ARGUMENT entry")
                val source = DslVariableSource.entries.singleOrNull { it.fieldName in fields }
                require(source != null && fields.keys == setOf(NAME_FIELD, source.fieldName)) {
                    "$VARIABLES_ARGUMENT entries require name and exactly one of fromArgument, fromObjectField, or fromQueryField"
                }
                val name = fields.getValue(NAME_FIELD)
                require(name is StringValue && GRAPHQL_NAME.matches(name.requiredValue())) {
                    "$VARIABLES_ARGUMENT.name must be a GraphQL name"
                }
                val path = fields.getValue(source.fieldName)
                require(path is ArrayValue && path.values.isNotEmpty()) {
                    "$VARIABLES_ARGUMENT.${source.fieldName} must be a nonempty path list"
                }
                DslVariable(
                    name = name.requiredValue(),
                    source = source,
                    path = path.values.map { component ->
                        require(component is StringValue && GRAPHQL_NAME.matches(component.requiredValue())) {
                            "$VARIABLES_ARGUMENT.${source.fieldName} path components must be GraphQL names"
                        }
                        component.requiredValue()
                    },
                )
            }.also { definitions ->
                require(definitions.map { it.name }.distinct().size == definitions.size) {
                    "$VARIABLES_ARGUMENT variable names must be unique"
                }
            }
        }

        private fun stripDslDirectives(definition: Definition<*>): Definition<*> =
            when (definition) {
                is ObjectTypeExtensionDefinition ->
                    definition.transformExtension { builder ->
                        builder
                            .directives(definition.directives.withoutDslDirectives())
                            .fieldDefinitions(
                                definition.fieldDefinitions.map { it.withoutDslDirectives() },
                            )
                    }
                is ObjectTypeDefinition ->
                    definition.transform { builder ->
                        builder
                            .directives(definition.directives.withoutDslDirectives())
                            .fieldDefinitions(
                                definition.fieldDefinitions.map { it.withoutDslDirectives() },
                            )
                    }
                else -> definition
            }

        private fun FieldDefinition.withoutDslDirectives(): FieldDefinition = transform { builder -> builder.directives(directives.withoutDslDirectives()) }

        private fun List<Directive>.withoutDslDirectives(): List<Directive> = filterNot { it.name in setOf(RESOLVER_DIRECTIVE, NODE_RESOLVER_DIRECTIVE, CHECKER_DIRECTIVE) }

        private fun requireOnlyArguments(
            directive: Directive,
            allowed: Set<String>,
        ) {
            require(directive.arguments.map { it.name }.distinct().size == directive.arguments.size) {
                "@${directive.name} contains duplicate arguments"
            }
            val unexpected = directive.arguments.map { it.name }.filterNot(allowed::contains)
            require(unexpected.isEmpty()) {
                "Unexpected @${
                    directive.name
                } arguments: ${unexpected.sorted().joinToString()}"
            }
        }

        private fun Directive.requiredArgument(name: String): GraphQLValue<*> =
            argument(name)
                ?: throw IllegalArgumentException(
                    "@${this.name} requires $name",
                )

        private fun Directive.argument(name: String): GraphQLValue<*>? = arguments.singleOrNull { it.name == name }?.value

        private fun parseId(value: GraphQLValue<*>): String =
            when (value) {
                is StringValue -> value.requiredValue()
                is IntValue -> value.value.toString()
                else -> throw IllegalArgumentException("@$NODE_RESOLVER_DIRECTIVE.ids must contain ID literals")
            }
    }
}

data class DslFieldResolver(
    val typeName: String,
    val fieldName: String,
    val of: String,
    val qf: String,
    val pathVariables: List<DslPathVariable>,
    val variables: List<DslVariable>,
    val variablesProvider: DslVariablesProvider?,
    val script: String,
    val isSelective: Boolean,
)

data class DslChecker(
    val typeName: String,
    val fieldName: String?,
    val inputs: List<DslCheckerInput>,
    val script: String,
)

data class DslCheckerInput(
    val name: String,
    val of: String,
    val qf: String,
    val variables: List<DslVariable>,
    val variablesProvider: DslVariablesProvider?,
)

data class DslVariablesProvider(
    val names: Set<String>,
    val script: String,
)

data class DslPathVariable(
    val name: String,
    val path: List<String>,
)

data class DslVariable(
    val name: String,
    val source: DslVariableSource,
    val path: List<String>,
)

enum class DslVariableSource(val fieldName: String) {
    ARGUMENT("fromArgument"),
    OBJECT("fromObjectField"),
    QUERY("fromQueryField"),
}

data class DslNodeResolver(
    val typeName: String,
    val ids: List<String>,
    val script: String,
    val isSelective: Boolean,
)

private fun StringValue.requiredValue(): String = requireNotNull(value) { "GraphQL string literal has no value" }

private fun ObjectValue.uniqueFields(context: String): Map<String, GraphQLValue<*>> {
    require(objectFields.map(ObjectField::getName).distinct().size == objectFields.size) {
        "$context contains duplicate fields"
    }
    return objectFields.associate { requireNotNull(it.name) to it.value }
}

private const val RESOLVER_DIRECTIVE = "resolver"
private const val NODE_RESOLVER_DIRECTIVE = "nodeResolver"
private const val CHECKER_DIRECTIVE = "checker"
private const val INPUTS_ARGUMENT = "inputs"
private const val SCRIPT_ARGUMENT = "script"
private const val IS_SELECTIVE_ARGUMENT = "isSelective"
private const val OF_ARGUMENT = "of"
private const val QF_ARGUMENT = "qf"
private const val PATH_VARS_ARGUMENT = "pathVars"
private const val VARIABLES_ARGUMENT = "variables"
private const val VARS_ARGUMENT = "vars"
private const val NAME_FIELD = "name"
private const val PATH_FIELD = "path"
private const val ID_FIELD = "id"
private const val IDS_ARGUMENT = "ids"
private val GRAPHQL_NAME = Regex("[_A-Za-z][_0-9A-Za-z]*")

private val BUILT_IN_SCHEMA =
    """
    directive @parent on FIELD_DEFINITION
    interface Node { id: ID! }
    type Query { node(id: ID!): Node }
    """.trimIndent()
