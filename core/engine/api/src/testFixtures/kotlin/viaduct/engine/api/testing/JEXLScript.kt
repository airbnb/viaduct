@file:Suppress("ForbiddenImport")
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package viaduct.engine.api.testing

import java.math.BigInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.JexlException
import org.apache.commons.jexl3.JexlFeatures
import org.apache.commons.jexl3.JexlScript
import org.apache.commons.jexl3.MapContext
import org.apache.commons.jexl3.introspection.JexlPermissions
import org.apache.commons.jexl3.introspection.JexlSandbox
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData

val jexlDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)

object JEXLError

fun prepareJEXLScript(
    coordinate: String,
    source: String,
    scriptBindings: Map<String, Any?>,
): JexlScript {
    val reserved = setOf("query", "mutation", "objectValue", "queryValue", "error", "ref", "id", "inputs", "allow", "deny", "denyWithMessage")
    require(scriptBindings.keys.intersect(reserved).isEmpty()) { "$coordinate has script bindings colliding with reserved names: ${scriptBindings.keys.intersect(reserved)}" }
    val bindingTypes = scriptBindings.values.filterNotNull().map { it.javaClass }.distinct()
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
            JexlPermissions.ClassPermissions(JEXLExecutionCallable::class.java, JEXLReferenceCallable::class.java, JEXLCheckerDenialCallable::class.java, *bindingTypes.toTypedArray()).compose(
                "viaduct.engine.api.testing { +JEXLObjectValue { get(); } }",
                "java.lang { Object { getClass(); } }",
            ),
        )
        .sandbox(
            JexlSandbox().apply {
                for (type in bindingTypes) {
                    permissions(type.name, false, true, false).read("class").execute("getClass")
                }
                for (type in listOf(JEXLExecutionCallable::class.java, JEXLReferenceCallable::class.java, JEXLCheckerDenialCallable::class.java)) {
                    permissions(type.name, false, false, true).execute("call")
                }
            },
        )
        .strict(true)
        .silent(false)
        .safe(false)
        .cancellable(true)
        .create()
    return engine.createScript(engine.createInfo(coordinate, 1, 1), source)
}

fun requireJEXLArgumentNames(
    argumentNames: Collection<String>,
    coordinate: String,
    scriptBindings: Map<String, Any?>,
) {
    require(argumentNames.none { it in setOf("query", "mutation", "objectValue", "queryValue", "error", "ref") }) {
        "$coordinate has an argument colliding with a JEXL resolver binding"
    }
    require(argumentNames.none { it in scriptBindings }) { "$coordinate has an argument colliding with a script binding" }
}

fun createJEXLScriptBindings(
    argumentNames: Collection<String>,
    arguments: Map<String, Any?>,
    scriptBindings: Map<String, Any?>,
): MapContext =
    MapContext(HashMap(scriptBindings)).also { bindings ->
        bindings.set("error", JEXLError)
        argumentNames.forEach { bindings.set(it, scriptValue(arguments[it])) }
    }

suspend fun JexlScript.evaluateJEXL(bindings: MapContext): Any? = evaluateJEXL(bindings) { it }

suspend fun <T> JexlScript.evaluateJEXL(
    bindings: MapContext,
    convertOutput: (Any?) -> T,
): T =
    try {
        convertOutput(runInterruptible { execute(bindings) })
    } catch (exception: JEXLInputReadException) {
        throw checkNotNull(exception.cause)
    } catch (exception: JexlException) {
        currentCoroutineContext().ensureActive()
        generateSequence<Throwable>(exception) { it.cause }.forEach { cause ->
            if (cause is CancellationException) throw cause
            if (cause is JEXLInputReadException) throw checkNotNull(cause.cause)
        }
        if (exception is JexlException.Throw) {
            val info = exception.info
            throw RuntimeException("${info.name}@${info.line}:${info.column}: ${exception.value}", exception)
        }
        throw exception
    }

fun scriptInput(
    value: Any?,
    path: String
): Any? =
    when (value) {
        null, is String, is Boolean -> value
        is Float, is Double -> (value as Number).toDouble().also { require(it.isFinite()) { "$path requires a finite number" } }
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
        is FloatArray -> scriptInput(value.toList(), path)
        is DoubleArray -> scriptInput(value.toList(), path)
        else -> throw IllegalArgumentException("$path requires an input value; errors and other objects are not allowed")
    }

fun scriptInteger(
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

class PreparedJEXLVariablesProvider(
    private val coordinate: String,
    private val argumentNames: List<String>,
    names: Set<String>,
    source: String,
    private val scriptBindings: Map<String, Any?>,
) {
    private val expectedNames = names.toSet()
    private val script: JexlScript

    init {
        requireJEXLArgumentNames(argumentNames, coordinate, scriptBindings)
        require(expectedNames.isNotEmpty()) { "$coordinate requires variable names" }
        script = prepareJEXLScript(coordinate, source, scriptBindings)
    }

    suspend fun evaluate(arguments: Map<String, Any?>): Map<String, Any?> {
        val output = script.evaluateJEXL(createJEXLScriptBindings(argumentNames, arguments, scriptBindings))
        require(output !== JEXLError) { "$coordinate returned error" }
        require(output is Map<*, *>) { "$coordinate requires a variable map" }
        require(output.keys == expectedNames) {
            "$coordinate returned invalid variables. Missing keys: ${expectedNames - output.keys}. Extra keys: ${output.keys - expectedNames}"
        }
        return expectedNames.associateWith { name -> scriptInput(output[name], "$coordinate.$name") }
    }
}

class PreparedJEXLChecker(
    private val coordinate: String,
    source: String,
    private val argumentNames: List<String>,
    private val scriptBindings: Map<String, Any?>,
) {
    private val script = prepareJEXLScript(coordinate, source, scriptBindings)
    private val denyWithMessage = JEXLCheckerDenialCallable()

    init {
        requireJEXLArgumentNames(argumentNames, coordinate, scriptBindings)
        require(argumentNames.none { it in setOf("inputs", "allow", "deny", "denyWithMessage") }) {
            "$coordinate has an argument colliding with a JEXL checker binding"
        }
    }

    fun bindings(
        arguments: Map<String, Any?>,
        inputs: Map<String, Map<String, EngineObjectData.Sync>>,
    ): MapContext =
        createJEXLScriptBindings(argumentNames, arguments, scriptBindings).also { bindings ->
            bindings.set("allow", CheckerResult.Success)
            bindings.set("deny", JEXLCheckerDenial("$coordinate denied access"))
            bindings.set("denyWithMessage", denyWithMessage)
            bindings.set("inputs", scriptValue(inputs, fromRSS = true))
        }

    suspend fun evaluate(bindings: MapContext): CheckerResult {
        val result = script.evaluateJEXL(bindings)
        require(result === CheckerResult.Success || result is JEXLCheckerDenial) { "$coordinate must return allow, deny, or denyWithMessage(...)" }
        return result as CheckerResult
    }
}

private class JEXLCheckerDenial(message: String) : CheckerResult.Error {
    override val error = IllegalStateException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

class JEXLCheckerDenialCallable internal constructor() {
    fun call(message: Any?): CheckerResult.Error {
        require(message is String) { "denyWithMessage requires a String message" }
        return JEXLCheckerDenial(message)
    }
}

const val JEXL_FROM_RSS = "**FROM_RSS**"

fun jexlObjectValue(
    value: EngineObjectData.Sync,
    fromRSS: Boolean = false,
): Map<String, Any?> = JEXLObjectValue(value, fromRSS)

fun unwrapJEXLObjectValue(value: Any?): EngineObjectData.Sync? = (value as? JEXLObjectValue)?.value

private class JEXLObjectValue(
    val value: EngineObjectData.Sync,
    private val fromRSS: Boolean = false,
) : AbstractMap<String, Any?>() {
    override val entries: Set<Map.Entry<String, Any?>>
        get() = buildSet {
            if (fromRSS) add(java.util.AbstractMap.SimpleImmutableEntry(JEXL_FROM_RSS, true))
            value.getSelections().forEach { key -> add(java.util.AbstractMap.SimpleImmutableEntry(key, get(key))) }
        }

    override fun containsKey(key: String): Boolean = (fromRSS && key == JEXL_FROM_RSS) || value.isPresent(key)

    override fun get(key: String): Any? =
        if (fromRSS && key == JEXL_FROM_RSS) {
            true
        } else {
            val input = try {
                value.get(key)
            } catch (exception: Exception) {
                if (value.isPresent(key)) throw JEXLInputReadException(exception)
                throw exception
            }
            scriptValue(input, fromRSS)
        }
}

private class JEXLInputReadException(cause: Exception) : RuntimeException(cause)

fun scriptValue(
    value: Any?,
    fromRSS: Boolean = false,
): Any? =
    when (value) {
        is EngineObjectData.Sync -> JEXLObjectValue(value, fromRSS)
        is Map<*, *> -> java.util.Collections.unmodifiableMap(value.mapValues { (_, item) -> scriptValue(item, fromRSS) })
        is List<*> -> java.util.Collections.unmodifiableList(value.map { scriptValue(it, fromRSS) })
        else -> value
    }

fun requireJEXLResolverOutput(
    value: Any?,
    path: String,
) {
    when (value) {
        is Map<*, *> -> {
            require(JEXL_FROM_RSS !in value) { "$path contains $JEXL_FROM_RSS; construct a fresh output map instead of returning an RSS object" }
            value.forEach { (name, item) -> requireJEXLResolverOutput(item, "$path.$name") }
        }
        is List<*> -> value.forEachIndexed { index, item -> requireJEXLResolverOutput(item, "$path[$index]") }
        is Array<*> -> value.forEachIndexed { index, item -> requireJEXLResolverOutput(item, "$path[$index]") }
    }
}

class JEXLExecutionCallable(
    private val bridgeContext: CoroutineContext,
    private val mutation: Boolean,
    private val execute: suspend (String, Map<String, Any?>) -> EngineObjectData.Sync,
) {
    fun call(selections: String): Map<String, Any?> = call(selections, emptyMap<String, Any?>())

    fun call(
        selections: String,
        variables: Map<*, *>,
    ): Map<String, Any?> {
        val operation = if (mutation) "mutation" else "query"
        val bindings = variables.entries.associate { (name, value) ->
            require(name is String) { "$operation variable names must be strings" }
            name to scriptInput(value, "$operation variable '$name'")
        }
        return JEXLObjectValue(runBlocking(bridgeContext) { execute(selections, bindings) })
    }
}

class JEXLReferenceCallable(
    private val createReference: (String, Map<String, Any?>) -> Any,
) {
    fun call(path: String): Any = call(path, emptyMap())

    fun call(
        path: String,
        arguments: Map<String, Any?>,
    ): Any {
        require(Regex("[_A-Za-z][_0-9A-Za-z]*(\\.[_A-Za-z][_0-9A-Za-z]*)*").matches(path)) { "Invalid root-field-reference path: $path" }
        val values = arguments.mapValues { (name, value) -> scriptInput(value, "reference '$path' argument '$name'") }
        return createReference(path, values)
    }
}
