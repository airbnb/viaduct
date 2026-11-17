package viaduct.engine.runtime2.model.testing

import graphql.language.Node
import graphql.language.VariableReference
import graphql.parser.Parser
import viaduct.engine.api.testing.DslChecker
import viaduct.engine.api.testing.DslFieldResolver
import viaduct.engine.api.testing.DslNodeResolver
import viaduct.engine.api.testing.DslPathVariable
import viaduct.engine.api.testing.DslVariable
import viaduct.engine.api.testing.DslVariableSource
import viaduct.engine.api.testing.ResolverTestDslDocument
import viaduct.engine.runtime2.bootstrap.compileFromField
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.FromArgument
import viaduct.engine.runtime2.model.registry.FromField
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDeclaration
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.registry.selectiveFieldResolverOf
import viaduct.engine.runtime2.model.registry.selectiveNodeResolverOf
import viaduct.engine.runtime2.model.requireOutputType
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.spec.flatten
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.schema.GJSelectionParser
import viaduct.engine.runtime2.schema.ParsedSpecFragment
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

internal class ResolverTestDsl private constructor(
    private val document: ResolverTestDslDocument,
    private val scriptBindings: Map<String, Any?>,
) {
    val schemaSDL: String get() = document.schemaSDL

    private fun compiler(schema: ViaductAndGJSchema) = Compiler(schema, document.fieldDefinitions, document.nodeDefinitions, scriptBindings)

    fun nodeResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Object, NodeResolverFunction> = compiler(schema).nodeResolvers()

    fun fieldResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> = compiler(schema).fieldResolvers()

    fun variableProviders(schema: ViaductAndGJSchema): Map<Arguments.Variable, VariableDeclaration> = compiler(schema).variableProviders()

    fun fieldCheckers(schema: ViaductAndGJSchema): Map<ViaductSchema.ObjectField, FieldCheckerResolver> = compiler(schema).fieldCheckers(document.checkerDefinitions)

    fun typeCheckers(schema: ViaductAndGJSchema): Map<ViaductSchema.Object, TypeCheckerResolver> = compiler(schema).typeCheckers(document.checkerDefinitions)

    companion object {
        fun parse(
            source: String,
            scriptBindings: Map<String, Any?> = emptyMap(),
        ): ResolverTestDsl = ResolverTestDsl(ResolverTestDslDocument.parse(source), scriptBindings)
    }
}

private class Compiler(
    private val schemas: ViaductAndGJSchema,
    private val fieldDefinitions: List<DslFieldResolver>,
    private val nodeDefinitions: List<DslNodeResolver>,
    private val scriptBindings: Map<String, Any?>,
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
            val script = createJEXLNodeResolverFunction(schemas, type, definition.script, scriptBindings)
            val function =
                if (definition.isSelective) {
                    selectiveNodeResolverOf { id, demand, context ->
                        script(id, demand, context).snipToDemand(demand, type.asTypeExpr().requireOutputType())
                    }
                } else {
                    script
                }
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
                NodeResolverFunction(entries.first().function.mode) { id, selections, context ->
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
            val script = createJEXLNonselectiveFieldResolverFunction(
                schemas = schemas,
                field = field,
                script = definition.script,
                hasObjectFragment = definition.of.isNotBlank(),
                hasQueryFragment = definition.qf.isNotBlank(),
                scriptBindings = scriptBindings,
            )
            val resolver =
                if (definition.isSelective) {
                    selectiveFieldResolverOf(objectFragment, queryFragment) { input, query, arguments, demand, additionalContext, context ->
                        script(input, query, arguments, additionalContext, context).snipToDemand(demand, field.outputType)
                    }
                } else {
                    fieldResolverOf(objectFragment, queryFragment, script)
                }
            compiled[field] = definition.variablesProvider?.let { provider ->
                resolver.withVariablesProvider(
                    provider.names,
                    createJEXLVariablesProviderFunction(field, provider.names, provider.script, scriptBindings),
                )
            } ?: resolver
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

    fun fieldCheckers(definitions: List<DslChecker>): Map<ViaductSchema.ObjectField, FieldCheckerResolver> =
        definitions.filter { it.fieldName != null }.associate { definition ->
            val field = sourceSchema.field(definition.typeName, checkNotNull(definition.fieldName))
            require(field is ViaductSchema.ObjectField) { "@$CHECKER_DIRECTIVE requires a concrete object field" }
            val target = ResolverTarget.FieldCheckerTarget(field)
            field to FieldCheckerResolver.of(
                field = field,
                queryType = schema.requireQueryTypeDef(),
                fragmentTemplates = checkerTemplates(definition, target, field),
                function = createJEXLFieldCheckerFunction(schemas, field, definition.script, scriptBindings),
            )
        }

    fun typeCheckers(definitions: List<DslChecker>): Map<ViaductSchema.Object, TypeCheckerResolver> =
        definitions.filter { it.fieldName == null }.associate { definition ->
            val type = schema.requireType(definition.typeName)
            require(type is ViaductSchema.Object) { "@$CHECKER_DIRECTIVE requires a concrete object type" }
            val target = ResolverTarget.TypeCheckerTarget(type)
            type to TypeCheckerResolver.of(
                type = type,
                queryType = schema.requireQueryTypeDef(),
                fragmentTemplates = checkerTemplates(definition, target, null),
                function = createJEXLTypeCheckerFunction(schemas, type, definition.script, scriptBindings),
            )
        }

    private fun checkerTemplates(
        checker: DslChecker,
        target: ResolverTarget,
        field: ViaductSchema.ObjectField?,
    ): Map<String, ResolverFragmentTemplates> =
        checker.inputs.associate { input ->
            val coordinate = "${checker.typeName}${checker.fieldName?.let { "/$it" }.orEmpty()}.checker.inputs.${input.name}"

            fun selections(
                source: String,
                typeName: String
            ) = if (source.isBlank()) {
                selectionForestOf()
            } else {
                val parsed = parseFragment(target, "fragment CheckerInput on $typeName { $source }")
                flatten(schema, parsed.nominalType, parsed.selections)
            }
            val objectSelections = selections(input.of, checker.typeName)
            val querySelections = selections(input.qf, "Query")
            val used = (objectSelections.usedVariables() + querySelections.usedVariables()).mapTo(linkedSetOf()) { it.variableName }
            val declared = input.variables.associateBy(DslVariable::name)
            val provided = input.variablesProvider?.names.orEmpty()
            val arguments = field?.args?.mapTo(linkedSetOf()) { it.name }.orEmpty()
            require(provided.intersect(declared.keys + arguments).isEmpty()) { "$coordinate vars may not redefine arguments or variables" }
            require((declared.keys + provided - used).isEmpty()) { "$coordinate has unused variables" }
            val variables = used.associate { name ->
                val variable = Arguments.Variable.of(target, name)
                val declaration = when {
                    name in declared -> compileVariable(field, target, checker.typeName, input.of, input.qf, declared.getValue(name))
                    name in arguments -> schema.fromArgument(checkNotNull(field), name)
                    name in provided -> null
                    else -> throw IllegalArgumentException("$coordinate has no source for variable \$$name")
                }
                variable to when (declaration) {
                    is FromArgument -> VariableDefinition.FromArgument.of(declaration.argument, declaration.inputPath)
                    is FromField -> VariableDefinition.FromField.of(declaration.providerFragment, declaration.keyPath, declaration.responsePath)
                    null -> VariableDefinition.FromProvider
                }
            }
            input.name to ResolverFragmentTemplates(
                objectFragmentTemplate = objectSelections,
                queryFragmentTemplate = querySelections,
                variables = variables,
                variablesProvider = input.variablesProvider?.let { provider ->
                    createJEXLVariablesProviderFunction("$coordinate.vars", field, provider.names, provider.script, scriptBindings)
                },
            )
        }

    fun variableProviders(): Map<Arguments.Variable, VariableDeclaration> =
        buildMap {
            fieldDefinitions.forEach { definition ->
                val field = sourceSchema.field(definition.typeName, definition.fieldName)
                require(field is ViaductSchema.ObjectField)
                val argumentNames = field.args.mapTo(linkedSetOf(), ViaductSchema.FieldArg::name)
                val pathVariables = definition.pathVariables.associateBy(DslPathVariable::name)
                val explicitVariables = definition.variables.associateBy(DslVariable::name)
                val providerVariableNames = definition.variablesProvider?.names.orEmpty()
                require(pathVariables.keys.intersect(argumentNames).isEmpty()) {
                    "${definition.typeName}.${definition.fieldName} $PATH_VARS_ARGUMENT may not " +
                        "redefine field arguments"
                }
                require(
                    pathVariables.keys.intersect(explicitVariables.keys).isEmpty(),
                ) {
                    "${definition.typeName}.${definition.fieldName} $VARIABLES_ARGUMENT may not redefine $PATH_VARS_ARGUMENT variables"
                }
                require(
                    providerVariableNames.intersect(argumentNames + pathVariables.keys + explicitVariables.keys).isEmpty(),
                ) {
                    "${definition.typeName}.${definition.fieldName} $VARS_ARGUMENT may " +
                        "not redefine field arguments, $PATH_VARS_ARGUMENT or $VARIABLES_ARGUMENT variables"
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
                val unusedExplicitVariables = explicitVariables.keys - usedVariables
                require(unusedExplicitVariables.isEmpty()) {
                    "Unused $VARIABLES_ARGUMENT on ${definition.typeName}.${definition.fieldName}: ${unusedExplicitVariables.sorted().joinToString()}"
                }
                usedVariables.forEach { name ->
                    val variable = Arguments.Variable.of(field, name)
                    when {
                        name in explicitVariables -> put(variable, compileVariable(field, definition, explicitVariables.getValue(name)))
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
                                    "$VARS_ARGUMENT or $VARIABLES_ARGUMENT definition",
                            )
                    }
                }
            }
        }

    private fun compileVariable(
        field: ViaductSchema.ObjectField,
        definition: DslFieldResolver,
        variable: DslVariable,
    ): VariableDeclaration = compileVariable(field, ResolverTarget.FieldValueResolverTarget(field), field.containingDef.name, definition.of, definition.qf, variable)

    private fun compileVariable(
        field: ViaductSchema.ObjectField?,
        target: ResolverTarget,
        typeName: String,
        of: String,
        qf: String,
        variable: DslVariable,
    ): VariableDeclaration =
        when (variable.source) {
            DslVariableSource.ARGUMENT -> {
                require(field != null) { "Type checker variables cannot be defined from an argument" }
                schema.fromArgument(field, variable.path)
            }
            DslVariableSource.OBJECT, DslVariableSource.QUERY -> {
                val isQuery = variable.source == DslVariableSource.QUERY
                val source = if (isQuery) qf else of
                require(source.isNotBlank()) { "${variable.source.fieldName} has no selection in ${if (isQuery) QF_ARGUMENT else OF_ARGUMENT}" }
                val fragment = "fragment ResolverTestDsl on ${if (isQuery) "Query" else typeName} { $source }"
                compileFromField(
                    schema = schemas,
                    parsed = parseFragment(target, fragment),
                    responsePath = variable.path,
                    providerFragment = if (isQuery) ProviderFragment.QUERY else ProviderFragment.OBJECT,
                )
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
                subselections = parsed.fragment.subselections,
            )
        }

    private fun parseFragment(
        field: ViaductSchema.ObjectField,
        fragment: String,
    ): ParsedSpecFragment = parseFragment(ResolverTarget.FieldValueResolverTarget(field), fragment)

    private fun parseFragment(
        target: ResolverTarget,
        fragment: String,
    ): ParsedSpecFragment {
        return GJSelectionParser(
            sourceSchema = schemas.graphQLSchema,
            schema = schema,
            variableValues = emptyMap(),
            variableTarget = target,
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

private data class CompiledNodeResolver(
    val type: ViaductSchema.Object,
    val id: String,
    val function: NodeResolverFunction,
)

private fun Node<*>.visitRecursively(visitor: (Node<*>) -> Unit) {
    visitor(this)
    children.forEach { child -> child.visitRecursively(visitor) }
}

private const val CHECKER_DIRECTIVE = "checker"
private const val ID_FIELD = "id"
private const val NODE_RESOLVER_DIRECTIVE = "nodeResolver"
private const val OF_ARGUMENT = "of"
private const val PATH_VARS_ARGUMENT = "pathVars"
private const val QF_ARGUMENT = "qf"
private const val RESOLVER_DIRECTIVE = "resolver"
private const val VARIABLES_ARGUMENT = "variables"
private const val VARS_ARGUMENT = "vars"
