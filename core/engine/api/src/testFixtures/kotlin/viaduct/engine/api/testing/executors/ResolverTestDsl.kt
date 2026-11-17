@file:Suppress("ForbiddenImport")

package viaduct.engine.api.testing.executors

import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import java.math.BigInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.apache.commons.jexl3.MapContext
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleDSL
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.api.spi.VariableFromFunctionDefinitions
import viaduct.engine.api.testing.DslChecker
import viaduct.engine.api.testing.DslVariable
import viaduct.engine.api.testing.DslVariableSource
import viaduct.engine.api.testing.DslVariablesProvider
import viaduct.engine.api.testing.JEXLError
import viaduct.engine.api.testing.JEXLExecutionCallable
import viaduct.engine.api.testing.JEXLReferenceCallable
import viaduct.engine.api.testing.PreparedJEXLChecker
import viaduct.engine.api.testing.PreparedJEXLVariablesProvider
import viaduct.engine.api.testing.ResolverTestDslDocument
import viaduct.engine.api.testing.createJEXLScriptBindings
import viaduct.engine.api.testing.evaluateJEXL
import viaduct.engine.api.testing.jexlDispatcher
import viaduct.engine.api.testing.jexlObjectValue
import viaduct.engine.api.testing.prepareJEXLScript
import viaduct.engine.api.testing.requireJEXLArgumentNames
import viaduct.engine.api.testing.requireJEXLResolverOutput
import viaduct.engine.api.testing.scriptInteger
import viaduct.engine.api.testing.unwrapJEXLObjectValue
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.SelectionsParserUtils
import viaduct.graphql.utils.collectVariableReferences

fun EngineTestModule.Companion.fromDSL(
    schemaSDL: String,
    scriptBindings: Map<String, Any?> = emptyMap(),
    block: MockTenantModuleDSL<Unit>.() -> Unit = {},
): EngineTestModule {
    val scripted = scriptedEngineTestModule(schemaSDL, scriptBindings)
    val native = EngineTestModule(scripted.fullSchema, block)
    val fields = scripted.fieldResolverExecutors.toList() + native.fieldResolverExecutors
    val nodes = scripted.nodeResolverExecutors.toList() + native.nodeResolverExecutors
    require(fields.map { it.first }.distinct().size == fields.size) { "Duplicate scripted/native field resolver" }
    require(nodes.map { it.first }.distinct().size == nodes.size) { "Duplicate scripted/native node resolver" }
    require(scripted.checkerExecutors.keys.intersect(native.checkerExecutors.keys).isEmpty()) { "Duplicate scripted/native field checker" }
    require(scripted.typeCheckerExecutors.keys.intersect(native.typeCheckerExecutors.keys).isEmpty()) { "Duplicate scripted/native type checker" }
    return EngineTestModule(
        fullSchema = scripted.fullSchema,
        fieldResolverExecutors = fields,
        nodeResolverExecutors = nodes,
        checkerExecutors = scripted.checkerExecutors + native.checkerExecutors,
        typeCheckerExecutors = scripted.typeCheckerExecutors + native.typeCheckerExecutors,
    )
}

private fun scriptedEngineTestModule(
    source: String,
    scriptBindings: Map<String, Any?>,
): EngineTestModule {
    val document = ResolverTestDslDocument.parse(source, includeBuiltInSchema = false)
    val schema = createSchemaWithWiring(document.schemaSDL, allowExistingDefaultSchemaComponents = true)
    val nodesById = document.nodeDefinitions.flatMap { node -> node.ids.map { it to node.typeName } }
    require(nodesById.map { it.first }.distinct().size == nodesById.size) { "@nodeResolver ids must be globally unique" }
    val nodeTypes = nodesById.toMap()
    val fields = document.fieldDefinitions.map { definition ->
        val coordinate = "${definition.typeName}/${definition.fieldName}"
        val field = schema.schema.getObjectType(definition.typeName).getFieldDefinition(definition.fieldName)
        val arguments = field.arguments.map { it.name }
        require(definition.pathVariables.none { it.name in arguments }) { "$coordinate pathVars may not redefine field arguments" }
        requireJEXLArgumentNames(arguments, coordinate, scriptBindings)
        val script = prepareJEXLScript(coordinate, definition.script, scriptBindings)
        val variables = ScriptVariables(
            coordinate,
            definition.typeName,
            arguments,
            definition.of,
            definition.qf,
            definition.variables + definition.pathVariables.map { DslVariable(it.name, DslVariableSource.OBJECT, it.path) },
            definition.variablesProvider,
            scriptBindings,
            forChecker = false,
        )
        (definition.typeName to definition.fieldName) to MockFieldUnbatchedResolverExecutor(
            resolverId = "${definition.typeName}.${definition.fieldName}",
            objectSelectionSet = variables.objectRSS,
            querySelectionSet = variables.queryRSS,
            argumentVariables = VariableFromArgumentDefinitions(variables.paths(DslVariableSource.ARGUMENT)),
            objectFieldVariables = VariableFromFieldDefinitions(variables.paths(DslVariableSource.OBJECT)),
            queryFieldVariables = VariableFromFieldDefinitions(variables.paths(DslVariableSource.QUERY)),
            variablesFromFunctionProvider = variables.functionProvider,
            isSelective = definition.isSelective,
        ) { args, obj, query, demand, context ->
            withContext(jexlDispatcher) {
                val invocation = ScriptInvocation(schema, context, nodeTypes)
                val bindings = createJEXLScriptBindings(arguments, args, scriptBindings)
                invocation.bindExecution(bindings)
                bindings.set("objectValue", if (variables.objectRSS != null) jexlObjectValue(obj, fromRSS = true) else null)
                bindings.set("queryValue", if (variables.queryRSS != null) jexlObjectValue(query, fromRSS = true) else null)
                script.evaluateJEXL(bindings) { output ->
                    requireJEXLResolverOutput(output, coordinate)
                    invocation.convert(field.type, output, coordinate).projectTo(demand.takeIf { definition.isSelective })
                }
            }
        }
    }
    val nodes = document.nodeDefinitions.map { definition ->
        val type = requireNotNull(schema.schema.getObjectType(definition.typeName))
        require(type.interfaces.any { it.name == "Node" }) { "${type.name} does not implement Node" }
        val coordinate = "${type.name}/@nodeResolver"
        val script = prepareJEXLScript(coordinate, definition.script, scriptBindings)
        type.name to MockNodeUnbatchedResolverExecutor(type.name, definition.isSelective) { id, demand, context ->
            withContext(jexlDispatcher) {
                require(id in definition.ids) { "$coordinate has no fixture for id '$id'" }
                val invocation = ScriptInvocation(schema, context, nodeTypes)
                val bindings = createJEXLScriptBindings(emptyList(), emptyMap(), scriptBindings)
                invocation.bindExecution(bindings)
                bindings.set("id", id)
                script.evaluateJEXL(bindings) { output ->
                    requireJEXLResolverOutput(output, coordinate)
                    require(output !is Map<*, *> || "id" !in output) { "$coordinate supplies id from the Node identity" }
                    invocation.convert(type, output, coordinate).projectTo(demand.takeIf { definition.isSelective }) as? EngineObjectData
                        ?: throw IllegalArgumentException("$coordinate requires an object")
                }
            }
        }
    }

    fun checker(definition: DslChecker): CheckerExecutor {
        val coordinate = "${definition.typeName}${definition.fieldName?.let { "/$it" }.orEmpty()}.checker"
        val type = requireNotNull(schema.schema.getObjectType(definition.typeName)) { "$coordinate requires an object type" }
        val arguments = definition.fieldName?.let { name ->
            requireNotNull(type.getFieldDefinition(name)) { "$coordinate has no field '$name'" }.arguments.map { it.name }
        }.orEmpty()
        val prepared = PreparedJEXLChecker(coordinate, definition.script, arguments, scriptBindings)
        val pairs = definition.inputs.associate { input ->
            input.name to ScriptVariables(
                "$coordinate.inputs.${input.name}",
                definition.typeName,
                arguments,
                input.of,
                input.qf,
                input.variables,
                input.variablesProvider,
                scriptBindings,
                forChecker = true,
            )
        }
        return object : CheckerExecutor {
            override val checkerMetadata = CheckerMetadata(coordinate, definition.typeName, definition.fieldName)
            override val requiredSelectionSets = buildMap {
                pairs.forEach { (name, pair) ->
                    pair.objectRSS?.let { put("$name/objectValue", it) }
                    pair.queryRSS?.let { put("$name/queryValue", it) }
                }
            }

            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataMap: Map<String, EngineObjectData.Sync>,
                context: EngineExecutionContext,
                checkerType: CheckerExecutor.CheckerType,
            ) = withContext(jexlDispatcher) {
                val inputs = pairs.mapValues { (name, _) ->
                    mapOf(
                        "objectValue" to (objectDataMap["$name/objectValue"] ?: ResolvedEngineObjectData(schema.schema.getObjectType(definition.typeName), emptyMap())),
                        "queryValue" to (objectDataMap["$name/queryValue"] ?: ResolvedEngineObjectData(schema.schema.queryType, emptyMap())),
                    )
                }
                val bindings = prepared.bindings(arguments, inputs)
                ScriptInvocation(schema, context, nodeTypes).bindExecution(bindings)
                prepared.evaluate(bindings)
            }
        }
    }
    return EngineTestModule(
        schema,
        fields,
        nodes,
        document.checkerDefinitions.filter { it.fieldName != null }.associate { (it.typeName to checkNotNull(it.fieldName)) to checker(it) },
        document.checkerDefinitions.filter { it.fieldName == null }.associate { it.typeName to checker(it) },
    )
}

private class ScriptVariables(
    private val coordinate: String,
    typeName: String,
    argumentNames: List<String>,
    of: String,
    qf: String,
    definitions: List<DslVariable>,
    provider: DslVariablesProvider?,
    scriptBindings: Map<String, Any?>,
    private val forChecker: Boolean,
) {
    private val objectSelections = of.takeIf(String::isNotBlank)?.let { SelectionsParser.parse(typeName, it) }
    private val querySelections = qf.takeIf(String::isNotBlank)?.let { SelectionsParser.parse("Query", it) }
    private val used = (objectSelections?.collectVariableReferences().orEmpty() + querySelections?.collectVariableReferences().orEmpty()).toSet()
    private val declarations = definitions.associateBy(DslVariable::name).toMutableMap()
    private val resolvers = mutableMapOf<String, VariablesResolver>()
    val functionProvider: VariableFromFunctionDefinitions? = provider?.let { declaration ->
        val prepared = PreparedJEXLVariablesProvider("$coordinate.vars", argumentNames, declaration.names, declaration.script, scriptBindings)
        object : VariableFromFunctionDefinitions {
            override val variableNames = declaration.names

            override suspend fun provideVariables(
                objectData: EngineObjectData.Sync,
                arguments: Map<String, Any?>,
                context: EngineExecutionContext,
            ) = withContext(jexlDispatcher) { prepared.evaluate(arguments) }
        }
    }

    init {
        require(declarations.size == definitions.size) { "$coordinate has duplicate variables" }
        definitions.filter { it.source == DslVariableSource.ARGUMENT }.forEach { definition ->
            require(definition.path.first() in argumentNames) { "$coordinate has no field argument '${definition.path.first()}'" }
        }
        val provided = provider?.names.orEmpty()
        require(provided.intersect(declarations.keys + argumentNames).isEmpty()) { "$coordinate vars may not redefine arguments or variables" }
        require((declarations.keys + provided - used).isEmpty()) { "$coordinate has unused variables" }
        argumentNames.filter { it in used && it !in declarations }.forEach { name ->
            declarations[name] = DslVariable(name, DslVariableSource.ARGUMENT, listOf(name))
        }
        functionProvider?.let { function ->
            val resolver = MockVariablesResolver(*function.variableNames.toTypedArray()) { ctx, context ->
                function.provideVariables(ctx.objectData, ctx.arguments, context)
            }
            function.variableNames.forEach { resolvers[it] = resolver }
        }
    }

    fun paths(source: DslVariableSource): Map<String, String> = declarations.values.filter { it.source == source }.associate { it.name to it.path.joinToString(".") }

    private fun resolver(
        name: String,
        building: Set<String> = emptySet()
    ): VariablesResolver {
        require(name !in building) { "$coordinate has a variable cycle at '$name'" }
        resolvers[name]?.let { return it }
        val declaration = requireNotNull(declarations[name]) { "$coordinate has no source for variable '$name'" }
        val result = when (declaration.source) {
            DslVariableSource.ARGUMENT -> FromArgument(name, declaration.path)
            DslVariableSource.OBJECT, DslVariableSource.QUERY -> {
                val selections = if (declaration.source == DslVariableSource.OBJECT) objectSelections else querySelections
                val view = requireNotNull(selections?.filterToPath(declaration.path)) { "$coordinate has no selection at ${declaration.path}" }
                val dependencies = view.collectVariableReferences().map { resolver(it, building + name) }.distinct()
                FromFieldVariablesResolver(name, declaration.path, RequiredSelectionSet(view, dependencies, forChecker))
            }
        }
        resolvers[name] = result
        return result
    }

    private fun rss(selections: ParsedSelections?): RequiredSelectionSet? =
        selections?.let {
            RequiredSelectionSet(it, it.collectVariableReferences().map { name -> resolver(name) }.distinct(), forChecker)
        }

    val objectRSS = rss(objectSelections)
    val queryRSS = rss(querySelections)
}

private class ScriptInvocation(
    private val schema: EngineSchema,
    private val context: EngineExecutionContext,
    private val nodeTypes: Map<String, String>,
) {
    suspend fun bindExecution(bindings: MapContext) {
        val bridge = currentCoroutineContext().minusKey(ContinuationInterceptor)
        for (mutation in listOf(false, true)) {
            bindings.set(
                if (mutation) "mutation" else "query",
                JEXLExecutionCallable(bridge, mutation) { source, variables ->
                    val root = if (mutation) requireNotNull(schema.schema.mutationType) { "Schema has no Mutation root" } else schema.schema.queryType
                    val selections = context.engineSelectionSetFactory.engineSelectionSet(
                        ParsedSelections.fromDocument(root.name, SelectionsParserUtils.normalizeToFragmentDocument(source, root.name, Parser::parse)),
                        variables
                    )
                    val result = context.resolveSelectionSet(selections, if (mutation) ResolveSelectionSetOptions.MUTATION else ResolveSelectionSetOptions.DEFAULT)
                    QueryScriptObjectData(result, selections)
                }
            )
        }
        bindings.set(
            "ref",
            JEXLReferenceCallable { path, args ->
                if (path == "node") {
                    val id = args["id"]?.toString()
                    require(args.keys == setOf("id") && id != null) { "ref('node') requires id" }
                    context.createNodeReference(id, schema.schema.getObjectType(requireNotNull(nodeTypes[id]) { "No @nodeResolver fixture for id '$id'" }))
                } else {
                    var type = schema.schema.queryType
                    path.split('.').forEach { field ->
                        type = GraphQLTypeUtil.unwrapAll(requireNotNull(type.getFieldDefinition(field)) { "Unknown reference field $field" }.type) as? GraphQLObjectType
                            ?: throw IllegalArgumentException("$path requires an object-valued reference target")
                    }
                    context.createRootFieldReference(path.split('.'), type, args)
                }
            }
        )
    }

    fun convert(
        type: GraphQLOutputType,
        value: Any?,
        path: String,
    ): Any? {
        require(value !== JEXLError) { "$path returned error" }
        if (value == null || value is NodeReference || value is RootFieldReference) return value
        if (type is GraphQLNonNull) return convert(type.wrappedType as GraphQLOutputType, value, path)
        if (type is GraphQLList) {
            val list = when (value) {
                is List<*> -> value
                is Array<*> -> value.toList()
                is IntArray -> value.toList()
                is LongArray -> value.toList()
                is BooleanArray -> value.toList()
                is FloatArray -> value.toList()
                is DoubleArray -> value.toList()
                else -> throw IllegalArgumentException("$path requires a list")
            }
            return list.mapIndexed { index, item -> convert(type.wrappedType as GraphQLOutputType, item, "$path[$index]") }
        }
        if (type is GraphQLCompositeType) {
            require(value is Map<*, *>) { "$path requires an object" }
            val original = unwrapJEXLObjectValue(value)
            (original as? QueryScriptObjectData)?.selections?.selections()?.forEach { selection ->
                require(selection.selectionName == selection.fieldName) { "$path contains aliased selection '${selection.selectionName}'; construct an output map using schema field names" }
            }
            val typename = original?.type?.name ?: value["__typename"] ?: (type as? GraphQLObjectType)?.name
            val concrete = requireNotNull((typename as? String)?.let(schema.schema::getType) as? GraphQLObjectType) { "$path requires an object __typename" }
            require(type == concrete || schema.schema.isPossibleType(type, concrete)) { "$path type ${concrete.name} is not a possible ${type.name}" }
            val builder = ResolvedEngineObjectData.Builder(concrete)
            value.forEach { (name, child) ->
                require(name is String) { "$path object keys must be field names" }
                if (name != "__typename") {
                    val field = requireNotNull(concrete.getFieldDefinition(name)) { "$path has unknown field '$name'" }
                    require(field.arguments.isEmpty()) { "$path must not supply argument-bearing field '$name'" }
                    builder.put(name, convert(field.type, child, "$path.$name"))
                }
            }
            return builder.build()
        }
        return when (type) {
            is GraphQLEnumType -> {
                require(value is String && type.getValue(value) != null) { "$path requires a ${type.name} enum value" }
                value
            }
            is GraphQLScalarType -> when (type.name) {
                "Int" -> scriptInteger(value, path)
                "ID" -> {
                    require(value is String || value is Int || value is Long || value is BigInteger) { "$path requires an ID" }
                    value.toString()
                }
                "Float" -> {
                    require(value is Number && value.toDouble().isFinite()) { "$path requires a finite number" }
                    value.toDouble()
                }
                "String" -> {
                    require(value is String) { "$path requires a String" }
                    value
                }
                "Boolean" -> {
                    require(value is Boolean) { "$path requires a Boolean" }
                    value
                }
                else -> throw IllegalArgumentException("$path has unsupported JEXL result type ${type.name}")
            }
            else -> throw IllegalArgumentException("$path has unsupported JEXL result type $type")
        }
    }
}

private fun Any?.projectTo(demand: EngineSelectionSet?): Any? {
    if (demand == null) return this
    return when (this) {
        is List<*> -> map { it.projectTo(demand) }
        is EngineObjectData.Sync -> ResolvedEngineObjectData.Builder(type).also { builder ->
            getSelections().filter { demand.containsField(type.name, it) }.forEach { name ->
                val field = type.getFieldDefinition(name)
                val nested = if (GraphQLTypeUtil.unwrapAll(field.type) is GraphQLCompositeType) demand.selectionSetForField(type.name, name) else null
                builder.put(name, get(name).projectTo(nested))
            }
        }.build()
        else -> this
    }
}

private class QueryScriptObjectData(
    private val value: EngineObjectData.Sync,
    selections: EngineSelectionSet,
) : EngineObjectData.Sync by value {
    val selections = selections.selectionSetForType(value.type.name)

    override fun get(selection: String): Any? {
        val item = value.get(selection)
        if (selections.traversableSelections().none { it.selectionName == selection }) return item
        val nested = selections.selectionSetForSelection(type.name, selection)

        fun wrap(value: Any?): Any? =
            when (value) {
                is EngineObjectData.Sync -> QueryScriptObjectData(value, nested)
                is List<*> -> value.map { wrap(it) }
                else -> value
            }
        return wrap(item)
    }

    override fun getOrNull(selection: String): Any? = if (isPresent(selection)) get(selection) else null

    override suspend fun fetch(selection: String): Any? = get(selection)

    override suspend fun fetchOrNull(selection: String): Any? = getOrNull(selection)
}
