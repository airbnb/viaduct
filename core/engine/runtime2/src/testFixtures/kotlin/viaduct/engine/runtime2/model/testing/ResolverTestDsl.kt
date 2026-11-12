package viaduct.engine.runtime2.model.testing

import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.IntValue
import graphql.language.Node
import graphql.language.ObjectField
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value as GraphQLValue
import graphql.language.VariableReference
import graphql.parser.Parser
import viaduct.engine.runtime2.bootstrap.compileFromField
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.VariableDeclaration
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.spec.flattenForMaterialization
import viaduct.engine.runtime2.schema.GJSelectionParser
import viaduct.engine.runtime2.schema.ParsedSpecFragment
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

/**
 * Compiles schema-embedded resolver fixtures into the existing test-world composition API.
 */
internal class ResolverTestDsl private constructor(
    val schemaSDL: String,
    private val fieldDefinitions: List<DslFieldResolver>,
    private val nodeDefinitions: List<DslNodeResolver>,
) {
    fun nodeResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Object, NodeResolverFunction> = Compiler(schema, fieldDefinitions, nodeDefinitions).nodeResolvers()

    fun fieldResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> = Compiler(schema, fieldDefinitions, nodeDefinitions).fieldResolvers()

    fun variableProviders(schema: ViaductAndGJSchema): Map<Arguments.Variable, VariableDeclaration> = Compiler(schema, fieldDefinitions, nodeDefinitions).variableProviders()

    companion object {
        fun parse(source: String): ResolverTestDsl {
            val document = Parser.parse(source)
            val fields = mutableListOf<DslFieldResolver>()
            val nodes = mutableListOf<DslNodeResolver>()

            document.definitions.forEach { definition ->
                when (definition) {
                    is ObjectTypeExtensionDefinition ->
                        collectObjectDefinition(
                            definition.name,
                            definition.directives,
                            definition.fieldDefinitions,
                            fields,
                            nodes,
                        )
                    is ObjectTypeDefinition ->
                        collectObjectDefinition(
                            definition.name,
                            definition.directives,
                            definition.fieldDefinitions,
                            fields,
                            nodes,
                        )
                }
            }

            require(fields.map { it.typeName to it.fieldName }.distinct().size == fields.size) {
                "A field may carry only one @$RESOLVER_DIRECTIVE directive"
            }
            require(nodes.map { it.typeName }.distinct().size == nodes.size) {
                "An object may carry only one @$NODE_RESOLVER_DIRECTIVE directive"
            }

            val stripped =
                document.transform { builder ->
                    builder.definitions(document.definitions.map(::stripDslDirectives))
                }
            return ResolverTestDsl(
                schemaSDL = BUILT_IN_SCHEMA + "\n" + AstPrinter.printAst(stripped),
                fieldDefinitions = fields,
                nodeDefinitions = nodes,
            )
        }

        private fun collectObjectDefinition(
            typeName: String,
            directives: List<Directive>,
            fieldDefinitions: List<FieldDefinition>,
            fields: MutableList<DslFieldResolver>,
            nodes: MutableList<DslNodeResolver>,
        ) {
            directives
                .filter { it.name == NODE_RESOLVER_DIRECTIVE }
                .forEach { directive ->
                    requireOnlyArguments(directive, setOf(IDS_ARGUMENT, SCRIPT_ARGUMENT))
                    val script = directive.requiredArgument(SCRIPT_ARGUMENT)
                    require(script is StringValue) { "@$NODE_RESOLVER_DIRECTIVE.script must be a string" }
                    val ids = directive.requiredArgument(IDS_ARGUMENT)
                    require(ids is ArrayValue && ids.values.isNotEmpty()) { "@$NODE_RESOLVER_DIRECTIVE.ids must be a nonempty list" }
                    nodes += DslNodeResolver(typeName, ids.values.map(::parseId), script.requiredValue())
                }

            fieldDefinitions.forEach { field ->
                field.directives
                    .filter { it.name == RESOLVER_DIRECTIVE }
                    .forEach { directive ->
                        requireOnlyArguments(
                            directive,
                            setOf(
                                OF_ARGUMENT,
                                QF_ARGUMENT,
                                PATH_VARS_ARGUMENT,
                                VARS_ARGUMENT,
                                SCRIPT_ARGUMENT,
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
                                variablesProvider = variablesProvider,
                                script = script.requiredValue(),
                            )
                    }
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

        private fun List<Directive>.withoutDslDirectives(): List<Directive> = filterNot { it.name == RESOLVER_DIRECTIVE || it.name == NODE_RESOLVER_DIRECTIVE }

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

private class Compiler(
    private val schemas: ViaductAndGJSchema,
    private val fieldDefinitions: List<DslFieldResolver>,
    private val nodeDefinitions: List<DslNodeResolver>,
) {
    private val schema = schemas.loweredSchema
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val nodeEntries: List<CompiledNodeResolver> =
        nodeDefinitions.flatMap { definition ->
            val type = schema.requireType(definition.typeName) as? ViaductSchema.Object
                ?: throw IllegalArgumentException(
                    "@$NODE_RESOLVER_DIRECTIVE requires an object type: ${definition.typeName}",
                )
            require(type in nodeType().possibleObjectTypes) {
                "${definition.typeName} does not implement Node"
            }
            val function = createJEXLNodeResolverFunction(schemas, type, definition.script)
            definition.ids.map { id -> CompiledNodeResolver(type, id, function) }
        }
    private val nodesById: Map<String, CompiledNodeResolver> =
        nodeEntries.associateBy(CompiledNodeResolver::id).also { byId ->
            require(byId.size == nodeEntries.size) {
                "@$NODE_RESOLVER_DIRECTIVE ids must be globally unique"
            }
        }

    fun nodeResolvers(): Map<ViaductSchema.Object, NodeResolverFunction> =
        nodeEntries
            .groupBy(CompiledNodeResolver::type)
            .mapValues { (_, entries) ->
                val byId = entries.associateBy(CompiledNodeResolver::id)
                NodeResolverFunction(NodeResolverFunction.Mode.NONSELECTIVE) { id, selections, context ->
                    byId[id]?.let { entry ->
                        entry.function.invoke(id, selections, context)
                    }
                }
            }

    fun fieldResolvers(): Map<ViaductSchema.Field, FieldResolverDefinition> {
        val compiled = mutableMapOf<ViaductSchema.Field, FieldResolverDefinition>()
        fieldDefinitions.forEach { definition ->
            val field = sourceSchema.field(definition.typeName, definition.fieldName)
            require(field is ViaductSchema.ObjectField) {
                "@$RESOLVER_DIRECTIVE requires a concrete object field: " +
                    "${definition.typeName}.${definition.fieldName}"
            }
            val objectFragment = fragment(field, definition.of)
            val queryFragment = fragment(field, definition.qf, "Query")
            val resolver = fieldResolverOf(
                objectFragment,
                queryFragment,
                createJEXLNonselectiveFieldResolverFunction(
                    schemas = schemas,
                    field = field,
                    script = definition.script,
                    hasObjectFragment = definition.of.isNotBlank(),
                    hasQueryFragment = definition.qf.isNotBlank(),
                ),
            )
            compiled[field] =
                when {
                    definition.variablesProvider != null -> resolver.withVariablesProvider(
                        definition.variablesProvider.names,
                        createJEXLVariablesProviderFunction(field, definition.variablesProvider.names, definition.variablesProvider.script),
                    )
                    else -> resolver
                }
        }

        val queryNode = sourceSchema.field("Query", "node")
        require(queryNode is ViaductSchema.ObjectField)
        compiled[queryNode] =
            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                val id = arguments.fieldValues.getValue(ID_FIELD)
                when (id) {
                    null -> null
                    is String ->
                        nodesById[id]?.let { entry ->
                            schema.objectOf(entry.type.name) {
                                ID_FIELD setTo id
                            }
                        }
                    else -> throw IllegalArgumentException("Query.node id is not an ID")
                }
            }
        return compiled
    }

    fun variableProviders(): Map<Arguments.Variable, VariableDeclaration> =
        buildMap {
            fieldDefinitions.forEach { definition ->
                val field = sourceSchema.field(definition.typeName, definition.fieldName)
                require(field is ViaductSchema.ObjectField)
                val argumentNames = field.args.mapTo(linkedSetOf(), ViaductSchema.FieldArg::name)
                val pathVariables = definition.pathVariables.associateBy(DslPathVariable::name)
                val providerVariableNames = definition.variablesProvider?.names.orEmpty()
                require(pathVariables.keys.intersect(argumentNames).isEmpty()) {
                    "${definition.typeName}.${definition.fieldName} $PATH_VARS_ARGUMENT may not " +
                        "redefine field arguments"
                }
                require(
                    providerVariableNames.intersect(argumentNames + pathVariables.keys).isEmpty(),
                ) {
                    "${definition.typeName}.${definition.fieldName} $VARS_ARGUMENT may " +
                        "not redefine field arguments or $PATH_VARS_ARGUMENT variables"
                }
                val usedVariables = variablesIn(definition, field)
                val unusedPathVariables = pathVariables.keys - usedVariables
                require(unusedPathVariables.isEmpty()) {
                    "Unused $PATH_VARS_ARGUMENT variables on ${definition.typeName}." +
                        "${definition.fieldName}: ${unusedPathVariables.sorted().joinToString()}"
                }
                val unusedProviderVariables = providerVariableNames - usedVariables
                require(unusedProviderVariables.isEmpty()) {
                    "Unused $VARS_ARGUMENT variables on ${definition.typeName}." +
                        "${definition.fieldName}: ${unusedProviderVariables.sorted().joinToString()}"
                }
                usedVariables.forEach { name ->
                    val variable = Arguments.Variable.of(field, name)
                    when {
                        name in argumentNames -> put(variable, schema.fromArgument(field, name))
                        name in pathVariables ->
                            put(
                                variable,
                                fragmentSource(field, definition.of).let { fragment ->
                                    compileFromField(
                                        schema = schemas,
                                        parsed = parseFragment(field, fragment),
                                        responsePath = pathVariables.getValue(name).path,
                                        providerFragment = ProviderFragment.OBJECT,
                                    )
                                },
                            )
                        name in providerVariableNames -> Unit
                        else ->
                            throw IllegalArgumentException(
                                "Variable \$$name on ${definition.typeName}." +
                                    "${definition.fieldName} is neither an argument, a " +
                                    "$PATH_VARS_ARGUMENT definition, nor a " +
                                    "$VARS_ARGUMENT definition",
                            )
                    }
                }
            }
        }

    private fun fragment(
        field: ViaductSchema.ObjectField,
        source: String,
        typeName: String = field.containingDef.name,
    ): Fragment =
        if (source.isBlank()) {
            schema.emptyFragmentOf(typeName)
        } else {
            val parsed = parseFragment(field, fragmentSource(field, source, typeName))
            Fragment.of(
                nominalType = parsed.nominalType,
                materializeSelections = flattenForMaterialization(schema, parsed.nominalType, parsed.selections),
            )
        }

    private fun parseFragment(
        field: ViaductSchema.ObjectField,
        fragment: String,
    ): ParsedSpecFragment {
        return GJSelectionParser(
            sourceSchema = schemas.graphQLSchema,
            schema = schema,
            variableValues = emptyMap(),
            variableTarget = ResolverTarget.FieldValueResolverTarget(field),
        ).specSelectionsFrom(fragment)
    }

    private fun variablesIn(
        definition: DslFieldResolver,
        field: ViaductSchema.ObjectField,
    ): Set<String> {
        val variables = linkedSetOf<String>()
        listOf(definition.of to field.containingDef.name, definition.qf to "Query").forEach { (source, typeName) ->
            if (source.isBlank()) return@forEach
            val fragment = fragmentSource(field, source, typeName)
            Parser.parse(fragment).visitRecursively { node ->
                if (node is VariableReference) variables += node.name
            }
        }
        return variables
    }

    private fun fragmentSource(
        field: ViaductSchema.ObjectField,
        source: String,
        typeName: String = field.containingDef.name,
    ): String = "fragment ResolverTestDsl on $typeName { $source }"

    private fun nodeType(): ViaductSchema.Interface =
        schema.requireType("Node") as? ViaductSchema.Interface
            ?: throw IllegalArgumentException("The resolver-test DSL requires interface Node")
}

private data class DslFieldResolver(
    val typeName: String,
    val fieldName: String,
    val of: String,
    val qf: String,
    val pathVariables: List<DslPathVariable>,
    val variablesProvider: DslVariablesProvider?,
    val script: String,
)

private data class DslVariablesProvider(
    val names: Set<String>,
    val script: String,
)

private data class DslPathVariable(
    val name: String,
    val path: List<String>,
)

private data class DslNodeResolver(
    val typeName: String,
    val ids: List<String>,
    val script: String,
)

private data class CompiledNodeResolver(
    val type: ViaductSchema.Object,
    val id: String,
    val function: NodeResolverFunction,
)

private fun StringValue.requiredValue(): String = requireNotNull(value) { "GraphQL string literal has no value" }

private fun ObjectValue.uniqueFields(context: String): Map<String, GraphQLValue<*>> {
    require(objectFields.map(ObjectField::getName).distinct().size == objectFields.size) {
        "$context contains duplicate fields"
    }
    return objectFields.associate { requireNotNull(it.name) to it.value }
}

private fun Node<*>.visitRecursively(visitor: (Node<*>) -> Unit) {
    visitor(this)
    children.forEach { child -> child.visitRecursively(visitor) }
}

private const val RESOLVER_DIRECTIVE = "resolver"
private const val NODE_RESOLVER_DIRECTIVE = "nodeResolver"
private const val SCRIPT_ARGUMENT = "script"
private const val OF_ARGUMENT = "of"
private const val QF_ARGUMENT = "qf"
private const val PATH_VARS_ARGUMENT = "pathVars"
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
