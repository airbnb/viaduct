@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.benchmark

import graphql.GraphQLContext
import graphql.execution.ValuesResolver
import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.InputValueWithState
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.api.spi.VariableFromFunctionDefinitions
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.argumentsOfGround
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.collectVariableReferences

class BenchmarkExecutorAdapter(
    private val world: TestWorld,
    private val registry: ArbitraryRegistry,
) {
    private val schema = createSchemaWithWiring(graphql.schema.idl.SchemaPrinter().print(world.schemas.graphQLSchema), allowExistingDefaultSchemaComponents = true)
    private val occurrence = ResolverOccurrenceId.at(world.schema.testRoot(), emptyList())

    @Volatile private var frozen = false

    fun freeze() {
        frozen = true
    }

    fun module(): EngineTestModule {
        val fields = world.schemas.graphQLSchema.allTypesAsList.filterIsInstance<GraphQLObjectType>()
            .filterNot { it.name.startsWith("__") }
            .flatMap { type ->
                type.fieldDefinitions.mapNotNull { definition ->
                    if (type.name == "Query" && definition.name == "node") return@mapNotNull null
                    val field = world.schema.requireObjectField(type.name, definition.name)
                    if (field !in world.resolverRegistry) return@mapNotNull null
                    val resolver = world.resolverRegistry.resolver(field)
                    val templates = ResolverFragmentTemplates(
                        resolver.instantiateObjectMaterializationSelections(occurrence),
                        resolver.instantiateQueryMaterializationSelections(occurrence),
                        resolver.variables,
                        resolver.variablesProvider,
                    )
                    val coordinate = FieldCoordinate(type.name, definition.name)
                    val required = requiredSelections(
                        type.name,
                        templates,
                        forChecker = false,
                        objectSource = registry.objectFragmentSources[coordinate],
                        querySource = registry.queryFragmentSources[coordinate],
                    )
                    val output = runBlocking {
                        sourceOutput(
                            resolver.evaluateRelation(
                                world.schema.objectOf(type.name),
                                world.schema.objectOf("Query"),
                                argumentsOfGround(emptyMap()),
                                selections = viaduct.engine.runtime2.model.selectionForestOf(),
                                selectiveResolvers = false,
                                executionContext = ResolutionExecutionContext.Unsupported,
                            ),
                            definition.type,
                        )
                    }
                    val outputTemplate = outputTemplate(output)
                    (type.name to definition.name) to MockFieldUnbatchedResolverExecutor(
                        resolverId = "benchmark:${type.name}.${definition.name}",
                        objectSelectionSet = required.first,
                        querySelectionSet = required.second,
                        argumentVariables = VariableFromArgumentDefinitions(
                            resolver.variables.entries.mapNotNull { (variable, source) ->
                                (source as? VariableDefinition.FromArgument)?.let {
                                    variable.variableName to (listOf(it.argument.name) + it.inputPath.map { step -> step.name }).joinToString(".")
                                }
                            }.toMap()
                        ),
                        objectFieldVariables = fieldVariables(resolver.variables, ProviderFragment.OBJECT),
                        queryFieldVariables = fieldVariables(resolver.variables, ProviderFragment.QUERY),
                        variablesFromFunctionProvider = resolver.variablesProvider?.let { function ->
                            object : VariableFromFunctionDefinitions {
                                override val variableNames = resolver.variables.filterValues { it == VariableDefinition.FromProvider }.keys.map { it.variableName }.toSet()

                                override suspend fun provideVariables(
                                    objectData: EngineObjectData.Sync,
                                    arguments: Map<String, Any?>,
                                    context: EngineExecutionContext,
                                ): Map<String, Any?> = function(Arguments.Resolved.of(field, arguments))
                            }
                        },
                    ) { _, _, _, _, context -> outputTemplate.instantiate(context) }
                }
            }
        val nodes = world.instance(BenchmarkNodeResolvers::class.java).resolvers.map { (type, resolver) ->
            val prepared = ConcurrentHashMap<String, OutputTemplate>()
            type.name to MockNodeUnbatchedResolverExecutor(typeName = type.name) { id, _, context ->
                val template = prepared[id] ?: run {
                    check(!frozen) { "Unprepared benchmark node: ${type.name}:$id" }
                    val output =
                        outputTemplate(sourceOutput(resolver(id, viaduct.engine.runtime2.model.selectionForestOf(), ResolutionExecutionContext.Unsupported), schema.schema.getObjectType(type.name)))
                    prepared.putIfAbsent(id, output) ?: output
                }
                template.instantiate(context) as EngineObjectData
            }
        }
        val fieldCheckers = world.schemas.graphQLSchema.allTypesAsList.filterIsInstance<GraphQLObjectType>().filterNot { it.name.startsWith("__") }.flatMap { type ->
            type.fieldDefinitions.mapNotNull { definition ->
                val field = world.schema.requireObjectField(type.name, definition.name)
                world.resolverRegistry.fieldChecker(field)?.let { checker ->
                    (type.name to definition.name) to checker(type.name, checker.fragmentTemplates)
                }
            }
        }.toMap()
        val typeCheckers = world.schema.types.values.filterIsInstance<viaduct.graphql.schema.ViaductSchema.Object>().mapNotNull { type ->
            world.resolverRegistry.typeChecker(type)?.let { checker -> type.name to checker(type.name, checker.fragmentTemplates) }
        }.toMap()
        return EngineTestModule(schema, fields, nodes, fieldCheckers, typeCheckers)
    }

    private fun fieldVariables(
        variables: Map<Arguments.Variable, VariableDefinition>,
        root: ProviderFragment
    ): VariableFromFieldDefinitions =
        VariableFromFieldDefinitions(
            variables.entries.mapNotNull { (variable, source) ->
                (source as? VariableDefinition.FromField)?.takeIf { it.providerFragment == root }?.let { variable.variableName to it.responsePath.joinToString(".") }
            }.toMap()
        )

    private fun checker(
        typeName: String,
        templates: Map<String, ResolverFragmentTemplates>
    ): CheckerExecutor =
        MockCheckerExecutor(
            templates.flatMap { (name, pair) ->
                val (objectSelections, querySelections) = requiredSelections(typeName, pair, forChecker = true)
                if (objectSelections == null && querySelections == null) {
                    listOf(name to null)
                } else {
                    listOfNotNull(objectSelections?.let { "$name:object" to it }, querySelections?.let { "$name:query" to it })
                }
            }.toMap(),
        )

    private fun requiredSelections(
        typeName: String,
        templates: ResolverFragmentTemplates,
        forChecker: Boolean,
        objectSource: String? = null,
        querySource: String? = null,
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?> {
        val objectSelections = if (objectSource == null) templates.objectFragmentTemplate.parsed(typeName) else objectSource.takeIf { it.isNotBlank() }?.let { SelectionsParser.parse(typeName, it) }
        val querySelections = if (querySource == null) templates.queryFragmentTemplate.parsed("Query") else querySource.takeIf { it.isNotBlank() }?.let { SelectionsParser.parse("Query", it) }
        val variables = templates.variables.entries.associate { it.key.variableName to it.value }
        val providers = templates.variables.filterValues { it == VariableDefinition.FromProvider }.keys.map { it.variableName }.toSet()
        val provider = templates.variablesProvider?.let { function ->
            MockVariablesResolver(*providers.toTypedArray()) { input, _ ->
                function(argumentsOfGround(input.arguments))
            }
        }
        val resolved = mutableMapOf<String, VariablesResolver>()

        fun variable(name: String): VariablesResolver =
            resolved.getOrPut(name) {
                when (val definition = variables.getValue(name)) {
                    VariableDefinition.FromProvider -> requireNotNull(provider)
                    is VariableDefinition.FromArgument -> FromArgument(name, listOf(definition.argument.name) + definition.inputPath.map { it.name })
                    is VariableDefinition.FromField -> {
                        val selections = if (definition.providerFragment == ProviderFragment.OBJECT) objectSelections else querySelections
                        val filtered = requireNotNull(selections).filterToPath(definition.responsePath)!!
                        val path = SelectionsParser.parse(filtered.typeName, "fragment Main on ${filtered.typeName} ${AstPrinter.printAst(filtered.selections)}")
                        FromFieldVariablesResolver(name, definition.responsePath, RequiredSelectionSet(path, path.collectVariableReferences().map(::variable), forChecker))
                    }
                }
            }

        fun required(selections: ParsedSelections?): RequiredSelectionSet? =
            selections?.let {
                RequiredSelectionSet(it, it.collectVariableReferences().map(::variable).distinct(), forChecker)
            }
        return required(objectSelections) to required(querySelections)
    }

    private fun MaterializeSelectionForest.parsed(typeName: String): ParsedSelections? =
        if (isEmpty()) null else SelectionsParser.parse(typeName, "fragment Main on $typeName ${AstPrinter.printAst(selectionSet())}")

    private fun MaterializeSelectionForest.selectionSet(): SelectionSet {
        val fields = mutableListOf<graphql.language.Selection<*>>()
        forEach { selection ->
            val name = if (selection.key.field.name == LOWERED_TYPENAME_FIELD) "__typename" else selection.key.field.name
            val field = Field.newField(name).alias(selection.responseKey.takeUnless { it == name || it == LOWERED_TYPENAME_FIELD })
            val sourceField = schema.schema.getObjectType(selection.key.field.containingDef.name)?.getFieldDefinition(name)
            field.arguments(
                selection.key.arguments.fieldExpressions().map { (arg, value) ->
                    Argument.newArgument(arg, literal(value, requireNotNull(sourceField).getArgument(arg).type)).build()
                }
            )
            when (val condition = selection.inclusionCondition) {
                InclusionCondition.Always -> Unit
                InclusionCondition.Never -> field.directive(Directive.newDirective().name("skip").argument(Argument("if", BooleanValue(true))).build())
                is InclusionCondition.Requires -> condition.values.forEach { (variable, included) ->
                    field.directive(Directive.newDirective().name(if (included) "include" else "skip").argument(Argument("if", VariableReference(variable.variableName))).build())
                }
                else -> error("Unsupported benchmark inclusion condition")
            }
            if (!selection.subselections.isEmpty()) field.selectionSet(selection.subselections.selectionSet())
            selection.possibleTypes.forEach { type ->
                fields += InlineFragment.newInlineFragment().typeCondition(TypeName(type.name)).selectionSet(SelectionSet(listOf(field.build()))).build()
            }
        }
        return SelectionSet(fields)
    }

    private fun literal(
        value: Any?,
        type: GraphQLInputType
    ): Value<*> =
        when {
            value is Arguments.Variable -> VariableReference(value.variableName)
            type is GraphQLNonNull -> literal(value, type.wrappedType as GraphQLInputType)
            value is List<*> && type is GraphQLList -> ArrayValue(value.map { literal(it, type.wrappedType as GraphQLInputType) })
            value is Map<*, *> && type is GraphQLInputObjectType -> ObjectValue(
                value.map { (name, entry) ->
                    ObjectField(name as String, literal(entry, type.getFieldDefinition(name).type))
                }
            )
            else -> ValuesResolver.valueToLiteral(InputValueWithState.newInternalValue(value), type, GraphQLContext.getDefault(), Locale.ROOT)
        }

    private fun sourceOutput(
        value: Any?,
        expectedType: GraphQLOutputType
    ): Any? =
        when (value) {
            is RootFieldReferenceData -> value.nodeReferenceIdentityOrNull()?.let { identity ->
                object : NodeReference {
                    override val type = schema.schema.getObjectType(identity.type.name)
                    override val id = identity.id
                }
            } ?: object : RootFieldReference {
                override val rootFieldPath = value.path.map { it.name }
                override val type = schema.schema.getType(value.type.name) as GraphQLCompositeType
                override val args = value.arguments.fieldValues
            }
            is EngineObjectData.Sync -> {
                val type = schema.schema.getObjectType(value.type.name)
                ResolvedEngineObjectData(
                    type,
                    value.getSelections().filter { it != LOWERED_TYPENAME_FIELD }.associateWith {
                        sourceOutput(value.outputValue(it), type.getFieldDefinition(it).type)
                    }
                )
            }
            is EngineErrorData -> fallbackOutput(expectedType)
            is List<*> -> {
                val list = GraphQLTypeUtil.unwrapNonNull(expectedType) as GraphQLList
                value.map { sourceOutput(it, list.wrappedType as GraphQLOutputType) }
            }
            else -> value
        }

    private fun fallbackOutput(
        type: GraphQLOutputType,
        ancestors: Set<String> = emptySet()
    ): Any? {
        if (type !is GraphQLNonNull) return null
        return when (val unwrapped = type.wrappedType) {
            is GraphQLList -> emptyList<Any?>()
            is GraphQLScalarType -> when (unwrapped.name) {
                "Int" -> 1
                "Float" -> 1.0
                "Boolean" -> true
                "String", "ID" -> "benchmark"
                else -> error("Unsupported benchmark scalar: ${unwrapped.name}")
            }
            is GraphQLEnumType -> unwrapped.values.first().name
            is GraphQLObjectType -> {
                check(unwrapped.name !in ancestors) { "Cyclic benchmark error replacement: ${unwrapped.name}" }
                ResolvedEngineObjectData(
                    unwrapped,
                    unwrapped.fieldDefinitions.filter {
                        world.schema.requireObjectField(unwrapped.name, it.name) !in world.resolverRegistry
                    }.associate { it.name to fallbackOutput(it.type, ancestors + unwrapped.name) }
                )
            }
            else -> error("Unsupported benchmark error type: $unwrapped")
        }
    }
}

private class OutputTemplate(
    val value: Any?,
    val create: ((EngineExecutionContext) -> Any?)? = null,
) {
    fun instantiate(context: EngineExecutionContext): Any? = if (create == null) value else create.invoke(context)
}

private fun outputTemplate(value: Any?): OutputTemplate =
    when (value) {
        // Production node and root references carry per-request resolution state.
        is NodeReference -> OutputTemplate(value) { it.createNodeReference(value.id, value.type) }
        is RootFieldReference -> OutputTemplate(value) {
            it.createRootFieldReference(value.rootFieldPath, value.type as GraphQLObjectType, value.args)
        }
        is ResolvedEngineObjectData -> {
            val values = value.getSelections().associateWith(value::get)
            val children = values.mapValues { outputTemplate(it.value) }.filterValues { it.create != null }
            if (children.isEmpty()) {
                OutputTemplate(value)
            } else {
                OutputTemplate(value) { context ->
                    ResolvedEngineObjectData(value.type, values + children.mapValues { it.value.instantiate(context) })
                }
            }
        }
        is List<*> -> {
            val children = value.map(::outputTemplate)
            if (children.none { it.create != null }) OutputTemplate(value) else OutputTemplate(value) { context -> children.map { it.instantiate(context) } }
        }
        else -> OutputTemplate(value)
    }
