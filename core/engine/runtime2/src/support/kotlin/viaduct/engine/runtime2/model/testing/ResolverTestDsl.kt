package viaduct.engine.runtime2.model.testing

import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Definition
import graphql.language.Directive
import graphql.language.EnumValue
import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.Node
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value as GraphQLValue
import graphql.language.VariableReference
import graphql.parser.Parser
import java.lang.Math.addExact
import java.math.BigInteger
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ArgumentResolutionError
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.arg
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.FromField
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolverTarget
import viaduct.engine.runtime2.model.registry.VariableDeclaration
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.registry.nodeResolverOf
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.spec.SpecSelection
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
                    requireOnlyArguments(directive, setOf(RESULT_ARGUMENT))
                    val result = directive.requiredArgument(RESULT_ARGUMENT)
                    val entries =
                        when (result) {
                            is ArrayValue -> result.values
                            is ObjectValue -> listOf(result)
                            else ->
                                throw IllegalArgumentException(
                                    "@$NODE_RESOLVER_DIRECTIVE.$RESULT_ARGUMENT must be a list",
                                )
                        }.map { value -> parseNodeResult(typeName, value) }
                    nodes += DslNodeResolver(typeName, entries)
                }

            fieldDefinitions.forEach { field ->
                field.directives
                    .filter { it.name == RESOLVER_DIRECTIVE }
                    .forEach { directive ->
                        requireOnlyArguments(
                            directive,
                            setOf(
                                OF_ARGUMENT,
                                PATH_VARS_ARGUMENT,
                                PROVIDER_VARS_ARGUMENT,
                                RESULT_ARGUMENT,
                            ),
                        )
                        val result = directive.requiredArgument(RESULT_ARGUMENT)
                        val of =
                            directive.argument(OF_ARGUMENT)?.let { value ->
                                require(value is StringValue) {
                                    "@$RESOLVER_DIRECTIVE.$OF_ARGUMENT must be a string"
                                }
                                value.requiredValue()
                            }.orEmpty()
                        val pathVariables =
                            directive.argument(PATH_VARS_ARGUMENT)
                                ?.let(::parsePathVariables)
                                .orEmpty()
                        val providerVariables =
                            directive.argument(PROVIDER_VARS_ARGUMENT)
                                ?.let(::parseProviderVariables)
                                .orEmpty()
                        fields +=
                            DslFieldResolver(
                                typeName = typeName,
                                fieldName = field.name,
                                of = of,
                                pathVariables = pathVariables,
                                providerVariables = providerVariables,
                                result = result,
                            )
                    }
            }
        }

        private fun parseNodeResult(
            typeName: String,
            value: GraphQLValue<*>,
        ): DslNodeResult {
            require(value is ObjectValue) {
                "@$NODE_RESOLVER_DIRECTIVE entries must be objects"
            }
            val fields = value.uniqueFields("$typeName @$NODE_RESOLVER_DIRECTIVE entry")
            require(fields.keys == setOf(ID_FIELD, RESULT_ARGUMENT)) {
                "@$NODE_RESOLVER_DIRECTIVE entries require exactly id and result"
            }
            return DslNodeResult(
                id = parseId(fields.getValue(ID_FIELD)),
                result = fields.getValue(RESULT_ARGUMENT),
            )
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

        private fun parseProviderVariables(value: GraphQLValue<*>): Map<String, GraphQLValue<*>> {
            require(value is ObjectValue) {
                "@$RESOLVER_DIRECTIVE.$PROVIDER_VARS_ARGUMENT must be an object"
            }
            return value.uniqueFields(PROVIDER_VARS_ARGUMENT).also { variables ->
                require(variables.isNotEmpty()) {
                    "@$RESOLVER_DIRECTIVE.$PROVIDER_VARS_ARGUMENT must not be empty"
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

        private fun List<Directive>.withoutDslDirectives(): List<Directive> = filterNot { it.name == RESOLVER_DIRECTIVE || it.name == NODE_RESOLVER_DIRECTIVE }

        private fun requireOnlyArguments(
            directive: Directive,
            allowed: Set<String>,
        ) {
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
                    "@${this.name} requires $name, including when null",
                )

        private fun Directive.argument(name: String): GraphQLValue<*>? = arguments.singleOrNull { it.name == name }?.value

        private fun parseId(value: GraphQLValue<*>): String =
            when (value) {
                is StringValue -> value.requiredValue()
                is IntValue -> value.value.toString()
                else -> throw IllegalArgumentException("NodeResult.id must be an ID literal")
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
    private val nodeEntries: List<CompiledNodeResult> =
        nodeDefinitions.flatMap { definition ->
            val type = schema.requireType(definition.typeName) as? ViaductSchema.Object
                ?: throw IllegalArgumentException(
                    "@$NODE_RESOLVER_DIRECTIVE requires an object type: ${definition.typeName}",
                )
            require(type in nodeType().possibleObjectTypes) {
                "${definition.typeName} does not implement Node"
            }
            definition.results.map { result ->
                CompiledNodeResult(type, result.id, result.result)
            }
        }
    private val nodesById: Map<String, CompiledNodeResult> =
        nodeEntries.associateBy(CompiledNodeResult::id).also { byId ->
            require(byId.size == nodeEntries.size) {
                "NodeResult ids must be globally unique"
            }
        }
    private val evaluator =
        ResultEvaluator(
            schema = schema,
            nodesById = nodesById,
        )

    fun nodeResolvers(): Map<ViaductSchema.Object, NodeResolverFunction> =
        nodeEntries
            .groupBy(CompiledNodeResult::type)
            .mapValues { (_, entries) ->
                val byId = entries.associateBy(CompiledNodeResult::id)
                nodeResolverOf { id ->
                    byId[id]?.let { entry ->
                        evaluator.evaluateNodeResult(entry)
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
            val fragment = objectFragment(field, definition.of)
            val resolver =
                fieldResolverOf(fragment) { input, arguments ->
                    evaluator.evaluateFieldResult(
                        field = field,
                        result = definition.result,
                        input = input,
                        arguments = arguments,
                    )
                }
            compiled[field] =
                if (definition.providerVariables.isEmpty()) {
                    resolver
                } else {
                    resolver.withVariablesProvider(definition.providerVariables.keys) { arguments ->
                        evaluator.evaluateProviderVariables(
                            field = field,
                            variables = definition.providerVariables,
                            arguments = arguments,
                        )
                    }
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
                val providerVariableNames = definition.providerVariables.keys
                require(pathVariables.keys.intersect(argumentNames).isEmpty()) {
                    "${definition.typeName}.${definition.fieldName} $PATH_VARS_ARGUMENT may not " +
                        "redefine field arguments"
                }
                require(
                    providerVariableNames.intersect(argumentNames + pathVariables.keys).isEmpty(),
                ) {
                    "${definition.typeName}.${definition.fieldName} $PROVIDER_VARS_ARGUMENT may " +
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
                    "Unused $PROVIDER_VARS_ARGUMENT variables on ${definition.typeName}." +
                        "${definition.fieldName}: ${unusedProviderVariables.sorted().joinToString()}"
                }
                usedVariables.forEach { name ->
                    val variable = Arguments.Variable.of(field, name)
                    when {
                        name in argumentNames -> put(variable, schema.fromArgument(field, name))
                        name in pathVariables ->
                            put(
                                variable,
                                preparedObjectFragment(field, definition.of).let { fragment ->
                                    FromField.compile(
                                        schema = schemas,
                                        parsed = parseObjectFragment(field, fragment),
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
                                    "$PROVIDER_VARS_ARGUMENT definition",
                            )
                    }
                }
            }
        }

    private fun objectFragment(
        field: ViaductSchema.ObjectField,
        source: String,
    ): Fragment =
        if (source.isBlank()) {
            schema.emptyFragmentOf(field.containingDef.name)
        } else {
            val parsed = parseObjectFragment(field, preparedObjectFragment(field, source))
            Fragment.of(
                nominalType = parsed.nominalType,
                materializeSelections = flattenForMaterialization(schema, parsed.nominalType, parsed.selections),
            )
        }

    private fun parseObjectFragment(
        field: ViaductSchema.ObjectField,
        fragment: PreparedObjectFragment,
    ): ParsedSpecFragment {
        val parsed =
            GJSelectionParser(
                sourceSchema = schemas.graphQLSchema,
                schema = schema,
                variableValues = emptyMap(),
                variableTarget = ResolverTarget.FieldValueResolverTarget(field),
            ).specSelectionsFrom(fragment.source)
        return if (fragment.errorVariables.isEmpty()) {
            parsed
        } else {
            parsed.copy(selections = parsed.selections.map { it.withArgumentErrors(fragment.errorVariables) })
        }
    }

    private fun variablesIn(
        definition: DslFieldResolver,
        field: ViaductSchema.ObjectField,
    ): Set<String> {
        if (definition.of.isBlank()) return emptySet()
        val fragment = preparedObjectFragment(field, definition.of)
        val variables = linkedSetOf<String>()
        Parser.parse(fragment.source).visitRecursively { node ->
            if (node is VariableReference) variables += node.name
        }
        return variables - fragment.errorVariables
    }

    private fun objectFragmentSource(
        field: ViaductSchema.ObjectField,
        source: String,
    ): String = "fragment ResolverTestDsl on ${field.containingDef.name} { $source }"

    private fun preparedObjectFragment(
        field: ViaductSchema.ObjectField,
        source: String,
    ): PreparedObjectFragment {
        val fragmentSource = objectFragmentSource(field, source)
        val occupiedVariableNames = linkedSetOf<String>()

        Parser.parse(fragmentSource).visitRecursively { node ->
            if (node is VariableReference) occupiedVariableNames += node.name
            if (node is Directive && node.name in setOf("skip", "include")) {
                require(node.arguments.none { (it.value as? StringValue)?.value == ERROR_SENTINEL }) {
                    "Directive variables must be Boolean, not injected argument errors"
                }
            }
        }

        var nextBindingIndex = 0
        val errorVariables = linkedSetOf<String>()
        val preparedSource =
            ERROR_ARGUMENT_LITERAL.replace(fragmentSource) {
                val name =
                    generateSequence {
                        "${ERROR_VARIABLE_PREFIX}${nextBindingIndex++}"
                    }.first { candidate ->
                        candidate !in occupiedVariableNames && candidate !in errorVariables
                    }
                errorVariables += name
                "\$$name"
            }
        return PreparedObjectFragment(preparedSource, errorVariables)
    }

    private fun nodeType(): ViaductSchema.Interface =
        schema.requireType("Node") as? ViaductSchema.Interface
            ?: throw IllegalArgumentException("The resolver-test DSL requires interface Node")
}

private class ResultEvaluator(
    private val schema: ViaductSchema,
    private val nodesById: Map<String, CompiledNodeResult>,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val nodeType = schema.requireType("Node") as ViaductSchema.Interface
    private val errorResult = EngineErrorData.of()

    fun evaluateFieldResult(
        field: ViaductSchema.ObjectField,
        result: GraphQLValue<*>,
        input: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
    ): EngineOutputData? =
        evaluate(
            typeExpr = sourceSchema.typeExpr(field),
            source = result,
            context = EvaluationContext(input, arguments, field),
        )

    fun evaluateProviderVariables(
        field: ViaductSchema.ObjectField,
        variables: Map<String, GraphQLValue<*>>,
        arguments: Arguments.Resolved,
    ): Map<String, EngineInputData?> {
        val context =
            EvaluationContext(
                schema.objectOf(field.containingDef.name),
                arguments,
                field,
            )
        return variables.mapValues { (_, value) -> evaluateProviderValue(value, context) }
    }

    private fun evaluateProviderValue(
        source: GraphQLValue<*>,
        context: EvaluationContext,
    ): EngineInputData? =
        when (source) {
            is NullValue -> null
            is IntValue -> source.value.toIntExact("provider variable")
            is FloatValue -> source.value.toDouble()
            is BooleanValue -> source.isValue
            is EnumValue -> source.name
            is StringValue -> {
                val value = source.requiredValue()
                require(value != ERROR_SENTINEL) {
                    "$ERROR_SENTINEL is reserved as a variables-provider error sentinel"
                }
                if (EXPRESSION.matches(value)) evaluateExpression(value, context) else value
            }
            is ArrayValue -> source.values.map { value -> evaluateProviderValue(value, context) }
            is ObjectValue ->
                source.uniqueFields("$PROVIDER_VARS_ARGUMENT value")
                    .mapValues { (_, value) -> evaluateProviderValue(value, context) }
            else ->
                throw IllegalArgumentException(
                    "Unsupported $PROVIDER_VARS_ARGUMENT value: $source",
                )
        }

    fun evaluateNodeResult(entry: CompiledNodeResult): EngineOutputData? =
        evaluate(
            typeExpr = ViaductSchema.TypeExpr(entry.type),
            source = entry.result,
            context = EvaluationContext(schema.objectOf(entry.type.name), null, null),
            nodeRoot = entry,
        )

    private fun evaluate(
        typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        source: GraphQLValue<*>,
        context: EvaluationContext,
        nodeRoot: CompiledNodeResult? = null,
    ): EngineOutputData? {
        if (source is StringValue && source.value == ERROR_SENTINEL) return errorResult
        if (source is NullValue) {
            require(typeExpr.isNullable) { "null does not conform to $typeExpr" }
            return null
        }
        val elementType = typeExpr.unwrapList()
        if (elementType != null) {
            require(source is ArrayValue) { "Expected a list result for $typeExpr" }
            return source.values.map { value ->
                evaluate(elementType, value, context)
            }
        }
        return when (val type = typeExpr.baseTypeDef) {
            is ViaductSchema.Scalar ->
                when (type.name) {
                    "Int" ->
                        evaluateInt(source, context).also { result ->
                            require(result != null || typeExpr.isNullable) {
                                "null does not conform to $typeExpr"
                            }
                        }
                    "ID" -> parseResultId(source, context)
                    else ->
                        throw IllegalArgumentException(
                            "Resolver-test DSL leaves must be Int, not ${type.name}",
                        )
                }
            is ViaductSchema.Enum ->
                throw IllegalArgumentException(
                    "Resolver-test DSL leaves must be Int, not ${type.name}",
                )
            is ViaductSchema.CompositeTypeDef ->
                evaluateComposite(type, source, context, nodeRoot)
            else -> error("Output field has a non-output type")
        }
    }

    private fun evaluateComposite(
        declaredType: ViaductSchema.CompositeTypeDef,
        source: GraphQLValue<*>,
        context: EvaluationContext,
        nodeRoot: CompiledNodeResult?,
    ): EngineOutputData? {
        require(source is ObjectValue) {
            "Expected an object result for ${declaredType.name}"
        }
        if (nodeRoot != null) {
            require(declaredType == nodeRoot.type)
            return evaluateObject(
                type = nodeRoot.type,
                source = source,
                context = context,
                injectedNodeId = nodeRoot.id,
            )
        }
        if (isNodeType(declaredType)) {
            return evaluateNodeReference(declaredType, source, context)
        }

        val fields = source.uniqueFields("result for ${declaredType.name}")
        val concreteType =
            when (declaredType) {
                is ViaductSchema.Object -> {
                    require(TYPENAME_FIELD !in fields) {
                        "__typename may not be supplied for object type ${declaredType.name}"
                    }
                    declaredType
                }
                else -> {
                    val typename = fields[TYPENAME_FIELD]
                    require(typename is StringValue) {
                        "Result for abstract type ${declaredType.name} requires __typename"
                    }
                    val concrete = schema.requireType(typename.requiredValue()) as? ViaductSchema.Object
                        ?: throw IllegalArgumentException(
                            "${typename.requiredValue()} is not an object type",
                        )
                    require(concrete in declaredType.possibleObjectTypes) {
                        "${concrete.name} is not a possible ${declaredType.name}"
                    }
                    concrete
                }
            }
        return evaluateObject(concreteType, source, context)
    }

    private fun evaluateObject(
        type: ViaductSchema.Object,
        source: ObjectValue,
        context: EvaluationContext,
        injectedNodeId: String? = null,
    ): EngineObjectData.Sync {
        val fields = source.uniqueFields("result for ${type.name}")
        if (injectedNodeId != null) {
            require(ID_FIELD !in fields) {
                "@$NODE_RESOLVER_DIRECTIVE result for ${type.name} may not contain id"
            }
            require(TYPENAME_FIELD !in fields) {
                "@$NODE_RESOLVER_DIRECTIVE result for ${type.name} may not contain __typename"
            }
        }
        return schema.objectOf(type.name) {
            if (injectedNodeId != null) {
                ID_FIELD setTo injectedNodeId
            }
            fields.forEach { (fieldName, fieldValue) ->
                if (fieldName == TYPENAME_FIELD) return@forEach
                val field = sourceSchema.field(type.name, fieldName)
                require(field is ViaductSchema.ObjectField)
                field(fieldName) setTo
                    evaluate(
                        typeExpr = sourceSchema.typeExpr(field),
                        source = fieldValue,
                        context = context,
                    )
            }
        }
    }

    private fun evaluateNodeReference(
        declaredType: ViaductSchema.CompositeTypeDef,
        source: ObjectValue,
        context: EvaluationContext,
    ): EngineObjectData.Sync {
        val fields = source.uniqueFields("Node reference")
        require(fields.keys == setOf(ID_FIELD)) {
            "Node-typed results may contain only id"
        }
        val id = parseResultId(fields.getValue(ID_FIELD), context)
        val entry =
            nodesById[id]
                ?: throw IllegalArgumentException("No @$NODE_RESOLVER_DIRECTIVE result for id $id")
        require(entry.type in declaredType.possibleObjectTypes) {
            "Node id $id has type ${entry.type.name}, not ${declaredType.name}"
        }
        return schema.objectOf(entry.type.name) {
            ID_FIELD setTo id
        }
    }

    private fun evaluateInt(
        source: GraphQLValue<*>,
        context: EvaluationContext,
    ): EngineOutputData? =
        when (source) {
            is IntValue -> source.value.toIntExact("GraphQL Int result")
            is StringValue -> evaluateExpression(source.requiredValue(), context)
            else -> throw IllegalArgumentException("Int results require an integer or expression")
        }

    private fun evaluateExpression(
        source: String,
        context: EvaluationContext,
    ): EngineOutputData? {
        val match = EXPRESSION.matchEntire(source)
            ?: throw IllegalArgumentException("Invalid resolver-test expression: $source")
        val operation = match.groupValues[1]
        val terms =
            match.groupValues[2]
                .takeIf(String::isNotBlank)
                ?.split(',')
                ?.map(String::trim)
                .orEmpty()

        if (operation == "value") {
            require(terms.size == 1) { "value(...) requires exactly one value" }
            val values = expressionValues(terms.single(), context, preserveNulls = true)
            require(values.size == 1) {
                "value(...) requires exactly one reachable value, found ${values.size}"
            }
            return values.single().also { value ->
                require(value == null || value is EngineErrorData || value is Int) {
                    "value(...) result is not an Int"
                }
            }
        }

        var sum = if (operation == "sumplus1") 1 else 0
        terms.forEach { term ->
            val values = expressionValues(term, context, preserveNulls = false)
            values.forEach { value ->
                when (value) {
                    null -> Unit
                    is EngineErrorData -> return value
                    is Int -> sum = addExact(sum, value)
                    else ->
                        throw IllegalArgumentException(
                            "Resolver-test expression value is not an Int: $term",
                        )
                }
            }
        }
        return sum
    }

    private fun expressionValues(
        term: String,
        context: EvaluationContext,
        preserveNulls: Boolean,
    ): List<EngineOutputData?> =
        if (term.startsWith("$")) {
            val value = argumentValue(term.removePrefix("$"), context)
            when (value) {
                null -> listOf(null)
                is Int -> listOf(value)
                else ->
                    throw IllegalArgumentException(
                        "Resolver-test expression value is not an output value: $term",
                    )
            }
        } else {
            require(PATH.matches(term)) { "Invalid resolver-test value: $term" }
            fieldPathValues(context.input, term.split('.'), preserveNulls)
        }

    private fun argumentValue(
        name: String,
        context: EvaluationContext,
    ): EngineInputData? {
        require(GRAPHQL_NAME.matches(name)) { "Invalid argument reference: \$$name" }
        val arguments =
            context.arguments
                ?: throw IllegalArgumentException(
                    "Node resolver results cannot reference arguments",
                )
        require(name in arguments.fieldValues) { "No resolver argument named $name" }
        return arguments.fieldValues.getValue(name)
    }

    private fun fieldPathValues(
        input: EngineObjectData.Sync,
        path: List<String>,
        preserveNulls: Boolean = false,
    ): List<EngineOutputData?> {
        fun visit(
            value: EngineOutputData?,
            index: Int,
        ): List<EngineOutputData?> =
            when {
                value == null -> if (preserveNulls) listOf(null) else emptyList()
                value is EngineErrorData -> listOf(value)
                value is List<*> -> value.flatMap { visit(it, index) }
                index == path.size -> listOf(value)
                value is EngineObjectData.Sync -> {
                    val responseKey = path[index]
                    require(value.isPresent(responseKey)) {
                        "Path ${path.joinToString(".")} does not identify one value at " +
                            "${value.type.name}.$responseKey"
                    }
                    val selected = value.get(responseKey)
                    visit(selected, index + 1)
                }
                else ->
                    throw IllegalArgumentException(
                        "Path ${path.joinToString(".")} traverses a non-object value",
                    )
            }

        return visit(input, 0)
    }

    private fun isNodeType(type: ViaductSchema.CompositeTypeDef): Boolean =
        type.possibleObjectTypes.isNotEmpty() &&
            type.possibleObjectTypes.all(nodeType.possibleObjectTypes::contains)

    private fun parseResultId(
        value: GraphQLValue<*>,
        context: EvaluationContext,
    ): String =
        when (value) {
            is StringValue -> {
                val source = value.requiredValue()
                val match = ID_FROM_ARGUMENT_EXPRESSION.matchEntire(source)
                if (match == null) {
                    require(!source.startsWith(ID_FROM_ARGUMENT_PREFIX)) {
                        "Invalid resolver-test ID expression: $source"
                    }
                    require(source != ERROR_SENTINEL) {
                        "$ERROR_SENTINEL is reserved as an error sentinel"
                    }
                    source
                } else {
                    val argumentName = match.groupValues[1]
                    val argumentType =
                        context.argumentDefinitions
                            ?.arg(argumentName)
                            ?.type
                    when {
                        argumentType == null ||
                            argumentType.isList ||
                            (argumentType.baseTypeDef as? ViaductSchema.Scalar)?.name != "ID" ->
                            throw IllegalArgumentException(
                                "idFrom(\$$argumentName) requires an ID argument",
                            )
                        else ->
                            argumentValue(argumentName, context) as? String
                                ?: throw IllegalArgumentException(
                                    "idFrom(\$$argumentName) requires a non-null ID argument",
                                )
                    }
                }
            }
            is IntValue -> value.value.toString()
            else -> throw IllegalArgumentException("Node id must be an ID literal")
        }
}

private data class EvaluationContext(
    val input: EngineObjectData.Sync,
    val arguments: Arguments.Resolved?,
    val argumentDefinitions: ViaductSchema.Field?,
)

private data class PreparedObjectFragment(
    val source: String,
    val errorVariables: Set<String>,
)

private fun SpecSelection.withArgumentErrors(errorVariables: Set<String>): SpecSelection {
    return when (this) {
        is SpecSelection.Field ->
            SpecSelection.Field.of(
                alias = alias,
                field = schemaField,
                arguments = arguments.fieldExpressions().mapValues { (_, value) -> value.withArgumentErrors(errorVariables) },
                subselections = subselections?.map { it.withArgumentErrors(errorVariables) },
                inclusionCondition = inclusionCondition,
                fieldDirectives = fieldDirectives,
            )
        is SpecSelection.InlineFragment ->
            SpecSelection.InlineFragment.of(
                typeCondition = typeCondition,
                selections = selections.map { it.withArgumentErrors(errorVariables) },
                inclusionCondition = inclusionCondition,
            )
    }
}

private fun Any?.withArgumentErrors(errorVariables: Set<String>): Any? =
    when (this) {
        is Arguments.Variable -> if (variableName in errorVariables) ArgumentResolutionError else this
        is List<*> -> map { it.withArgumentErrors(errorVariables) }
        is Map<*, *> -> mapValues { (_, value) -> value.withArgumentErrors(errorVariables) }
        else -> this
    }

private data class DslFieldResolver(
    val typeName: String,
    val fieldName: String,
    val of: String,
    val pathVariables: List<DslPathVariable>,
    val providerVariables: Map<String, GraphQLValue<*>>,
    val result: GraphQLValue<*>,
)

private data class DslPathVariable(
    val name: String,
    val path: List<String>,
)

private data class DslNodeResolver(
    val typeName: String,
    val results: List<DslNodeResult>,
)

private data class DslNodeResult(
    val id: String,
    val result: GraphQLValue<*>,
)

private data class CompiledNodeResult(
    val type: ViaductSchema.Object,
    val id: String,
    val result: GraphQLValue<*>,
)

private fun BigInteger.toIntExact(context: String): Int =
    try {
        intValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$context is outside the 32-bit range: $this")
    }

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
private const val RESULT_ARGUMENT = "result"
private const val OF_ARGUMENT = "of"
private const val PATH_VARS_ARGUMENT = "pathVars"
private const val PROVIDER_VARS_ARGUMENT = "providerVars"
private const val NAME_FIELD = "name"
private const val PATH_FIELD = "path"
private const val ID_FIELD = "id"
private const val TYPENAME_FIELD = "__typename"
private const val ERROR_SENTINEL = "ERROR"
private const val ERROR_VARIABLE_PREFIX = "__resolverTestError"
private const val ID_FROM_ARGUMENT_PREFIX = "idFrom("
private val GRAPHQL_NAME = Regex("[_A-Za-z][_0-9A-Za-z]*")
private val PATH = Regex("[_A-Za-z][_0-9A-Za-z]*(\\.[_A-Za-z][_0-9A-Za-z]*)*")
private val EXPRESSION = Regex("(sum|sumplus1|value)\\((.*)\\)")
private val ID_FROM_ARGUMENT_EXPRESSION =
    Regex("idFrom\\(\\$(${GRAPHQL_NAME.pattern})\\)")
private val ERROR_ARGUMENT_LITERAL = Regex("\"ERROR\"")

private val BUILT_IN_SCHEMA =
    """
    directive @parent on FIELD_DEFINITION
    interface Node { id: ID! }
    type Query { node(id: ID!): Node }
    """.trimIndent()
