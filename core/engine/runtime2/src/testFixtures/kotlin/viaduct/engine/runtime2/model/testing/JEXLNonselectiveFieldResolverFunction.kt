@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.testing

import graphql.parser.Parser
import java.math.BigInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.apache.commons.jexl3.JexlException
import org.apache.commons.jexl3.MapContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.testing.JEXLError
import viaduct.engine.api.testing.JEXLExecutionCallable
import viaduct.engine.api.testing.JEXLReferenceCallable
import viaduct.engine.api.testing.PreparedJEXLChecker
import viaduct.engine.api.testing.PreparedJEXLVariablesProvider
import viaduct.engine.api.testing.createJEXLScriptBindings
import viaduct.engine.api.testing.evaluateJEXL
import viaduct.engine.api.testing.jexlDispatcher
import viaduct.engine.api.testing.jexlObjectValue
import viaduct.engine.api.testing.prepareJEXLScript
import viaduct.engine.api.testing.requireJEXLArgumentNames
import viaduct.engine.api.testing.requireJEXLResolverOutput
import viaduct.engine.api.testing.scriptInteger
import viaduct.engine.api.testing.unwrapJEXLObjectValue
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineErrorDataReadException
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.Runtime2EngineObjectData
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.FieldCheckerFunction
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.registry.NonselectiveFieldResolverFunction
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.TypeCheckerFunction
import viaduct.engine.runtime2.model.registry.VariablesProviderFunction
import viaduct.engine.runtime2.model.registry.nodeResolverOf
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.mutationSelections
import viaduct.engine.runtime2.schema.specFragmentFromDocument
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.SelectionsParserUtils

fun createJEXLNonselectiveFieldResolverFunction(
    schemas: ViaductAndGJSchema,
    field: ViaductSchema.ObjectField,
    script: String,
    hasObjectFragment: Boolean = false,
    hasQueryFragment: Boolean = false,
    scriptBindings: Map<String, Any?> = emptyMap(),
): NonselectiveFieldResolverFunction = PreparedJEXLResolver(schemas, field, script, hasObjectFragment, hasQueryFragment, scriptBindings)::invoke

fun createJEXLNodeResolverFunction(
    schemas: ViaductAndGJSchema,
    type: ViaductSchema.Object,
    script: String,
    scriptBindings: Map<String, Any?> = emptyMap(),
): NodeResolverFunction {
    val coordinate = "${type.name}/@nodeResolver"
    val prepared = prepareJEXLScript(coordinate, script, scriptBindings)
    val adapter = JEXLOutputAdapter(schemas)
    return nodeResolverOf { id, context ->
        withContext(jexlDispatcher) {
            val bindings = MapContext(HashMap(scriptBindings))
            bindings.bindExecution(schemas, context)
            bindings.set("id", id)
            bindings.set("error", JEXLError)
            bindings.set("ref", runtime2ReferenceCallable(schemas))
            withRuntime2ReadErrors {
                prepared.evaluateJEXL(bindings) { output ->
                    requireJEXLResolverOutput(output, coordinate)
                    if (output is Map<*, *>) {
                        require("id" !in output) { "$coordinate supplies id from the Node identity" }
                        adapter.convert(ViaductSchema.TypeExpr(type), output + ("id" to id), coordinate)
                    } else {
                        require(output == null || output === JEXLError || output is EngineErrorData || output is RootFieldReferenceData) { "$coordinate requires an object or reference" }
                        adapter.convert(ViaductSchema.TypeExpr(type), output, coordinate)
                    }
                }
            }
        }
    }
}

fun createJEXLVariablesProviderFunction(
    field: ViaductSchema.ObjectField,
    names: Set<String>,
    script: String,
    scriptBindings: Map<String, Any?> = emptyMap(),
): VariablesProviderFunction = createJEXLVariablesProviderFunction("${field.containingDef.name}/${field.name}.vars", field, names, script, scriptBindings)

internal fun createJEXLVariablesProviderFunction(
    coordinate: String,
    field: ViaductSchema.ObjectField?,
    names: Set<String>,
    script: String,
    scriptBindings: Map<String, Any?> = emptyMap(),
): VariablesProviderFunction {
    val prepared = PreparedJEXLVariablesProvider(coordinate, field?.args?.map { it.name }.orEmpty(), names, script, scriptBindings)
    return { arguments ->
        withContext(jexlDispatcher) {
            withRuntime2ReadErrors { prepared.evaluate(arguments.fieldValues) }
        }
    }
}

fun createJEXLFieldCheckerFunction(
    schemas: ViaductAndGJSchema,
    field: ViaductSchema.ObjectField,
    script: String,
    scriptBindings: Map<String, Any?> = emptyMap(),
): FieldCheckerFunction {
    val prepared = Runtime2JEXLChecker(schemas, "${field.containingDef.name}/${field.name}.checker", field, script, scriptBindings)
    return { arguments, inputs, context -> prepared.invoke(arguments, inputs, context) }
}

fun createJEXLTypeCheckerFunction(
    schemas: ViaductAndGJSchema,
    type: ViaductSchema.Object,
    script: String,
    scriptBindings: Map<String, Any?> = emptyMap(),
): TypeCheckerFunction {
    val prepared = Runtime2JEXLChecker(schemas, "${type.name}.checker", null, script, scriptBindings)
    return { inputs, context -> prepared.invoke(null, inputs, context) }
}

private class Runtime2JEXLChecker(
    private val schemas: ViaductAndGJSchema,
    coordinate: String,
    field: ViaductSchema.ObjectField?,
    source: String,
    scriptBindings: Map<String, Any?>,
) {
    private val checker = PreparedJEXLChecker(coordinate, source, field?.args?.map { it.name }.orEmpty(), scriptBindings)

    suspend fun invoke(
        arguments: Arguments.Resolved?,
        inputs: Map<String, CheckerInput>,
        context: ResolutionExecutionContext,
    ): CheckerResult =
        withContext(jexlDispatcher) {
            val bindings = checker.bindings(
                arguments?.fieldValues.orEmpty(),
                inputs.mapValues { (_, input) -> mapOf("objectValue" to input.objectValue, "queryValue" to input.queryValue) },
            )
            bindings.bindExecution(schemas, context)
            withRuntime2ReadErrors { checker.evaluate(bindings) }
        }
}

private suspend fun <T> withRuntime2ReadErrors(block: suspend () -> T): T =
    try {
        block()
    } catch (exception: JexlException) {
        generateSequence<Throwable>(exception) { it.cause }.forEach { cause ->
            if (cause is EngineErrorDataReadException) throw cause
        }
        throw exception
    }

private suspend fun MapContext.bindExecution(
    schemas: ViaductAndGJSchema,
    context: ResolutionExecutionContext,
) {
    val bridgeContext = currentCoroutineContext().minusKey(ContinuationInterceptor)
    set("query", JEXLExecutionCallable(bridgeContext, mutation = false) { selections, variables -> executeSelections(schemas, context, selections, variables, mutation = false) })
    set("mutation", JEXLExecutionCallable(bridgeContext, mutation = true) { selections, variables -> executeSelections(schemas, context, selections, variables, mutation = true) })
}

private class PreparedJEXLResolver(
    private val schemas: ViaductAndGJSchema,
    private val field: ViaductSchema.ObjectField,
    source: String,
    private val hasObjectFragment: Boolean,
    private val hasQueryFragment: Boolean,
    private val scriptBindings: Map<String, Any?>,
) {
    private val coordinate = "${field.containingDef.name}/${field.name}"
    private val sourceSchema = SourceSchemaAdapter(schemas.loweredSchema)
    private val script = prepareJEXLScript(coordinate, source, scriptBindings)
    private val outputAdapter = JEXLOutputAdapter(schemas)

    init {
        requireJEXLArgumentNames(field.args.map { it.name }, coordinate, scriptBindings)
    }

    suspend fun invoke(
        objectValue: EngineObjectData.Sync,
        queryValue: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        @Suppress("UNUSED_PARAMETER") additionalContextForResolver: Any?,
        executionContext: ResolutionExecutionContext,
    ): ResolverOutputData? {
        return withContext(jexlDispatcher) {
            val bindings = createJEXLScriptBindings(field.args.map { it.name }, arguments.fieldValues, scriptBindings)
            bindings.bindExecution(schemas, executionContext)
            bindings.set("ref", runtime2ReferenceCallable(schemas))
            bindings.set("objectValue", if (hasObjectFragment) jexlObjectValue(objectValue, fromRSS = true) else null)
            bindings.set("queryValue", if (hasQueryFragment) jexlObjectValue(queryValue, fromRSS = true) else null)
            // Subqueries must be able to use the engine dispatcher while JEXL blocks here.
            withRuntime2ReadErrors {
                script.evaluateJEXL(bindings) { output ->
                    requireJEXLResolverOutput(output, coordinate)
                    sourceSchema.lowerOutput(field, outputAdapter.convert(sourceSchema.typeExpr(field), output, coordinate))
                }
            }
        }
    }
}

private class JEXLOutputAdapter(private val schemas: ViaductAndGJSchema) {
    private val sourceSchema = SourceSchemaAdapter(schemas.loweredSchema)
    private val errorResult = EngineErrorData.of()

    fun convert(
        type: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        value: Any?,
        path: String,
    ): ResolverOutputData? {
        if (value === JEXLError) return errorResult
        if (value == null || value is EngineErrorData || value is RootFieldReferenceData) return value
        if (type.isList) {
            val elements = when (value) {
                is List<*> -> value
                is Array<*> -> value.toList()
                is IntArray -> value.toList()
                is LongArray -> value.toList()
                is BooleanArray -> value.toList()
                is FloatArray -> value.toList()
                is DoubleArray -> value.toList()
                else -> throw IllegalArgumentException("$path requires a list")
            }
            return elements.mapIndexed { index, item -> convert(checkNotNull(type.unwrapList()), item, "$path[$index]") }
        }
        val declared = type.baseTypeDef
        if (declared is ViaductSchema.CompositeTypeDef) {
            require(value is Map<*, *>) { "$path requires an object" }
            val original = unwrapJEXLObjectValue(value)
            original?.requireUnaliased(path, sourceSchema)
            val typename = original?.type?.name ?: value["__typename"]
            val concrete = when {
                typename != null -> {
                    require(typename is String) { "$path.__typename requires a type name" }
                    schemas.loweredSchema.requireType(typename) as? ViaductSchema.Object
                        ?: throw IllegalArgumentException("$path.__typename is not an object type: $typename")
                }
                declared is ViaductSchema.Object -> declared
                else -> throw IllegalArgumentException("$path requires __typename for ${declared.name}")
            }
            require(concrete.name in declared.possibleObjectTypes.map { it.name }) {
                "$path type ${concrete.name} is not a possible ${declared.name}"
            }
            return schemas.loweredSchema.objectOf(concrete.name) {
                value.forEach { (name, item) ->
                    require(name is String) { "$path object keys must be field names" }
                    if (name != "__typename") {
                        val child = sourceSchema.field(concrete.name, name)
                        require(child.args.isEmpty()) { "$path must not supply argument-bearing field '$name'" }
                        field(name) setTo sourceSchema.lowerOutput(child, convert(sourceSchema.typeExpr(child), item, "$path.$name"))
                    }
                }
            }
        }
        val normalized = when (declared.name) {
            "Int" -> scriptInteger(value, path)
            "ID" -> {
                require(value is String || value is Int || value is Long || value is BigInteger) { "$path requires an ID" }
                value.toString()
            }
            "Float" -> {
                require(value is Number) { "$path requires a number" }
                value.toDouble()
            }
            else -> {
                require(declared is ViaductSchema.Enum || declared.name in setOf("String", "Boolean")) {
                    "$path has unsupported JEXL result type ${declared.name}"
                }
                value
            }
        }
        return try {
            coerceSimpleValue(declared as ViaductSchema.SimpleTypeDef, normalized)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("$path: ${exception.message}", exception)
        }
    }
}

private fun EngineObjectData.Sync.requireUnaliased(
    path: String,
    sourceSchema: SourceSchemaAdapter,
) {
    (this as? Runtime2EngineObjectData)?.selectionFields?.forEach { (selection, field) ->
        require(selection == field.name || (selection == "__typename" && field == sourceSchema.field(type.name, selection))) {
            "$path contains aliased selection '$selection' for field '${field.name}'; construct an output map using schema field names"
        }
    }
}

private suspend fun executeSelections(
    schemas: ViaductAndGJSchema,
    executionContext: ResolutionExecutionContext,
    selections: String,
    bindings: Map<String, Any?>,
    mutation: Boolean,
): EngineObjectData.Sync {
    val root = if (mutation) {
        requireNotNull(schemas.loweredSchema.mutationTypeDef) { "Cannot execute mutation selections without a Mutation root" }
    } else {
        schemas.loweredSchema.requireQueryTypeDef()
    }
    val source = if (SelectionsParserUtils.classify(selections) == SelectionsParserUtils.SelectionsForm.FRAGMENTS) {
        selections
    } else {
        "fragment ${SelectionsParserUtils.EntryPointFragmentName} on ${root.name} $selections"
    }
    val fragment = schemas.specFragmentFromDocument(
        document = Parser.parse(source),
        bindings = bindings,
    ).fragment
    require(fragment.nominalType == root) {
        "Cannot execute selections with type ${fragment.nominalType.name} on schema root type ${root.name}"
    }
    val forest = fragment.materializeSelections
    return if (mutation) {
        executionContext.resolveMutationSelectionSet(forest, forest.mutationSelections(schemas.loweredSchema, root))
    } else {
        executionContext.resolveSelectionSet(forest)
    }
}

private fun runtime2ReferenceCallable(schemas: ViaductAndGJSchema): JEXLReferenceCallable {
    val sourceSchema = SourceSchemaAdapter(schemas.loweredSchema)
    return JEXLReferenceCallable { path, values ->
        var typeName = "Query"
        val fields = path.split('.').map { name ->
            val field = sourceSchema.field(typeName, name)
            require(field is ViaductSchema.ObjectField) { "$path requires object fields" }
            typeName = sourceSchema.typeExpr(field).baseTypeDef.name
            field
        }
        try {
            RootFieldReferenceData.of(fields, values)
        } catch (cause: ClassCastException) {
            throw IllegalArgumentException("Invalid reference arguments for $path", cause)
        }
    }
}
