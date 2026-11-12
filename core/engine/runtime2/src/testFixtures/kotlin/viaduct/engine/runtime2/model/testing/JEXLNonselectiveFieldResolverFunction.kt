@file:Suppress("ForbiddenImport")
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime2.model.testing

import java.math.BigInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.JexlException
import org.apache.commons.jexl3.JexlFeatures
import org.apache.commons.jexl3.JexlScript
import org.apache.commons.jexl3.MapContext
import org.apache.commons.jexl3.introspection.JexlPermissions
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineErrorDataReadException
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.Runtime2EngineObjectData
import viaduct.engine.runtime2.model.registry.NonselectiveFieldResolverFunction
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.VariablesProviderFunction
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.materializeSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

private val jexlDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)

private object JEXLError

fun createJEXLNonselectiveFieldResolverFunction(
    schemas: ViaductAndGJSchema,
    field: ViaductSchema.ObjectField,
    script: String,
    hasObjectFragment: Boolean = false,
    hasQueryFragment: Boolean = false,
): NonselectiveFieldResolverFunction = PreparedJEXLResolver(schemas, field, script, hasObjectFragment, hasQueryFragment)::invoke

fun createJEXLVariablesProviderFunction(
    field: ViaductSchema.ObjectField,
    names: Set<String>,
    script: String,
): VariablesProviderFunction {
    val coordinate = "${field.containingDef.name}/${field.name}.vars"
    requireJEXLArgumentNames(field, coordinate)
    val expectedNames = names.toSet()
    require(expectedNames.isNotEmpty()) { "$coordinate requires variable names" }
    val prepared = prepareJEXLScript(coordinate, script)
    return { arguments ->
        withContext(jexlDispatcher) {
            val output = prepared.evaluate(scriptBindings(field, arguments))
            require(output !== JEXLError) { "$coordinate returned error" }
            require(output is Map<*, *>) { "$coordinate requires a variable map" }
            require(output.keys == expectedNames) {
                "$coordinate returned invalid variables. Missing keys: ${expectedNames - output.keys}. Extra keys: ${output.keys - expectedNames}"
            }
            expectedNames.associateWith { name -> scriptInput(output[name], "$coordinate.$name") }
        }
    }
}

private fun prepareJEXLScript(
    coordinate: String,
    source: String
): JexlScript {
    val engine = JexlBuilder()
        .features(
            JexlFeatures()
                .lexical(true)
                .lexicalShade(true)
                .newInstance(false)
                .sideEffectGlobal(false)
                .pragma(false)
                .annotation(false),
        )
        .permissions(
            JexlPermissions.ClassPermissions(JEXLQueryCallable::class.java).compose(
                "viaduct.engine.runtime2.model.testing { +JEXLObjectValue { get(); } }",
                "java.lang { Object { getClass(); } }",
            ),
        )
        .strict(true)
        .silent(false)
        .safe(false)
        .cancellable(true)
        .create()
    return engine.createScript(engine.createInfo(coordinate, 1, 1), source)
}

private fun requireJEXLArgumentNames(
    field: ViaductSchema.ObjectField,
    coordinate: String
) {
    require(field.args.none { it.name in setOf("query", "objectValue", "queryValue", "error") }) {
        "$coordinate has an argument colliding with a JEXL resolver binding"
    }
}

private fun scriptBindings(
    field: ViaductSchema.ObjectField,
    arguments: Arguments.Resolved
): MapContext =
    MapContext().also { bindings ->
        bindings.set("error", JEXLError)
        field.args.forEach { bindings.set(it.name, scriptValue(arguments.fieldValues[it.name])) }
    }

private suspend fun JexlScript.evaluate(bindings: MapContext): Any? =
    try {
        runInterruptible { execute(bindings) }
    } catch (exception: JexlException) {
        currentCoroutineContext().ensureActive()
        generateSequence<Throwable>(exception) { it.cause }.forEach { cause ->
            if (cause is CancellationException || cause is EngineErrorDataReadException) throw cause
        }
        throw exception
    }

private fun scriptInput(
    value: Any?,
    path: String
): EngineInputData? =
    when (value) {
        null, is String, is Boolean, is Double -> value
        is Int, is Long, is BigInteger -> scriptInteger(value, path)
        is Map<*, *> -> value.entries.associate { (name, item) ->
            require(name is String) { "$path input-object keys must be strings" }
            name to scriptInput(item, "$path.$name")
        }
        is List<*> -> value.mapIndexed { index, item -> scriptInput(item, "$path[$index]") }
        is Array<*> -> scriptInput(value.toList(), path)
        is IntArray -> value.toList()
        is LongArray -> scriptInput(value.toList(), path)
        is BooleanArray -> value.toList()
        is DoubleArray -> value.toList()
        else -> throw IllegalArgumentException("$path requires an input value; errors and other objects are not allowed")
    }

private class PreparedJEXLResolver(
    private val schemas: ViaductAndGJSchema,
    private val field: ViaductSchema.ObjectField,
    source: String,
    private val hasObjectFragment: Boolean,
    private val hasQueryFragment: Boolean,
) {
    private val coordinate = "${field.containingDef.name}/${field.name}"
    private val sourceSchema = SourceSchemaAdapter(schemas.loweredSchema)
    private val script = prepareJEXLScript(coordinate, source)

    init {
        requireJEXLArgumentNames(field, coordinate)
        require(field.args.all { !it.type.isList && it.type.baseTypeDef.name == "Int" }) {
            "$coordinate JEXL resolver arguments must be integers"
        }
    }

    suspend fun invoke(
        objectValue: EngineObjectData.Sync,
        queryValue: EngineObjectData.Sync,
        arguments: Arguments.Resolved,
        @Suppress("UNUSED_PARAMETER") additionalContextForResolver: Any?,
        executionContext: ResolutionExecutionContext,
    ): ResolverOutputData? {
        return withContext(jexlDispatcher) {
            val bridgeContext = currentCoroutineContext().minusKey(ContinuationInterceptor)
            val bindings = scriptBindings(field, arguments)
            bindings.set("query", JEXLQueryCallable(schemas, executionContext, bridgeContext))
            bindings.set("objectValue", if (hasObjectFragment) JEXLObjectValue(objectValue) else null)
            bindings.set("queryValue", if (hasQueryFragment) JEXLObjectValue(queryValue) else null)
            // Subqueries must be able to use the engine dispatcher while JEXL blocks here.
            val output = script.evaluate(bindings)
            sourceSchema.lowerOutput(field, convert(sourceSchema.typeExpr(field), output, coordinate))
        }
    }

    private fun convert(
        type: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        value: Any?,
        path: String,
    ): ResolverOutputData? {
        if (value === JEXLError) return EngineErrorData.of()
        if (value == null) return null
        if (type.isList) {
            val elements = when (value) {
                is List<*> -> value
                is Array<*> -> value.toList()
                is IntArray -> value.toList()
                is LongArray -> value.toList()
                else -> throw IllegalArgumentException("$path requires a list")
            }
            return elements.mapIndexed { index, item -> convert(checkNotNull(type.unwrapList()), item, "$path[$index]") }
        }
        val declared = type.baseTypeDef
        if (declared is ViaductSchema.CompositeTypeDef) {
            require(value is Map<*, *>) { "$path requires an object" }
            if (value is JEXLObjectValue) value.requireUnaliased(path, sourceSchema)
            val typename = if (value is JEXLObjectValue) value.typeName else value["__typename"]
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
                        field(name) setTo convert(sourceSchema.typeExpr(child), item, "$path.$name")
                    }
                }
            }
        }
        require(declared.name == "Int") { "$path has unsupported JEXL result type ${declared.name}" }
        return scriptInteger(value, path)
    }
}

private fun scriptInteger(
    value: Any,
    path: String
): Int {
    val integer = when (value) {
        is Int -> value.toBigInteger()
        is Long -> value.toBigInteger()
        is BigInteger -> value
        else -> throw IllegalArgumentException("$path requires an integer")
    }
    require(integer >= Int.MIN_VALUE.toBigInteger() && integer <= Int.MAX_VALUE.toBigInteger()) {
        "$path integer is outside GraphQL Int range: $integer"
    }
    return integer.toInt()
}

private class JEXLObjectValue(private val value: EngineObjectData.Sync) : AbstractMap<String, Any?>() {
    val typeName: String get() = value.type.name

    fun requireUnaliased(
        path: String,
        sourceSchema: SourceSchemaAdapter
    ) {
        (value as? Runtime2EngineObjectData)?.selectionFields?.forEach { (selection, field) ->
            require(selection == field.name || (selection == "__typename" && field == sourceSchema.field(typeName, selection))) {
                "$path contains aliased selection '$selection' for field '${field.name}'; construct an output map using schema field names"
            }
        }
    }

    override val entries: Set<Map.Entry<String, Any?>>
        get() = value.getSelections().map { key -> java.util.AbstractMap.SimpleImmutableEntry(key, get(key)) }.toSet()

    override fun containsKey(key: String): Boolean = value.isPresent(key)

    override fun get(key: String): Any? = scriptValue(value.get(key))
}

private fun scriptValue(value: Any?): Any? =
    when (value) {
        is EngineObjectData.Sync -> JEXLObjectValue(value)
        is Map<*, *> -> java.util.Collections.unmodifiableMap(value.mapValues { (_, item) -> scriptValue(item) })
        is List<*> -> java.util.Collections.unmodifiableList(value.map(::scriptValue))
        else -> value
    }

class JEXLQueryCallable internal constructor(
    private val schemas: ViaductAndGJSchema,
    private val executionContext: ResolutionExecutionContext,
    private val bridgeContext: CoroutineContext,
) {
    fun call(selections: String): Map<String, Any?> = call(selections, emptyMap())

    fun call(
        selections: String,
        variables: Map<String, Any?>,
    ): Map<String, Any?> {
        val bindings = variables.mapValues { (name, value) -> value?.let { scriptInteger(it, "query variable '$name'") } }
        val (_, forest) = schemas.materializeSelectionsFrom(
            source = "fragment JEXLQuery on Query $selections",
            bindings = bindings,
            preserveSourceResponseKeys = true,
        )
        return JEXLObjectValue(runBlocking(bridgeContext) { executionContext.resolveSelectionSet(forest) })
    }
}
