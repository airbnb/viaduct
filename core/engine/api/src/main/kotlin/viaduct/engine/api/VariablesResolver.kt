package viaduct.engine.api

import graphql.language.AstPrinter
import java.lang.IllegalStateException
import kotlin.IllegalArgumentException
import viaduct.engine.api.VariablesResolver.ResolveCtx
import viaduct.graphql.utils.collectVariableReferences

/** A VariablesResolver produces values for a fixed set of GraphQL variables */
interface VariablesResolver {
    /** the names of variables resolved by this [VariablesResolver] */
    val variableNames: Set<String>

    /**
     * The required selection set for this [VariablesResolver].
     * These selections will be loaded by the engine and accessible in the objectData
     * provided to this object's [resolve] method
     */
    val requiredSelectionSet: RequiredSelectionSet? get() = null

    /**
     * Per-item invocation data available to a [VariablesResolver.resolve] method.
     * This class carries only data specific to a particular invocation — the shared,
     * per-request [EngineExecutionContext] is passed as a separate parameter to [resolve].
     * @param objectData The data resolved from this objects [requiredSelectionSet]
     * @param arguments The argument values provided to the field whose resolver depends on these values.
     */
    data class ResolveCtx(
        val objectData: EngineObjectData,
        val arguments: Map<String, Any?>
    )

    /**
     * Resolve variable values.
     * The returned map must include entries for exactly the keys described in [variableNames].
     * @param ctx Per-item invocation data (object data and arguments).
     * @param context The per-request [EngineExecutionContext], shared across all invocations within a single GraphQL request.
     */
    suspend fun resolve(
        ctx: ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?>

    /**
     * Return a [VariablesResolver] that wraps this instance and validates that
     * resolved variable values exactly match the variables declared in [variableNames]
     */
    fun validated(): VariablesResolver =
        // optimization for Empty, which is inherently valid
        if (this == Empty) {
            this
        } else {
            Validated(this)
        }

    companion object {
        /** A [VariablesResolver] that resolves no variable values */
        val Empty: VariablesResolver = object : VariablesResolver {
            override val variableNames: Set<String> = emptySet()

            override suspend fun resolve(
                ctx: ResolveCtx,
                context: EngineExecutionContext
            ): Map<String, Any?> = emptyMap()
        }

        private data class Const(val values: Map<String, Any?>) : VariablesResolver {
            override val variableNames: Set<String> = values.keys

            override suspend fun resolve(
                ctx: ResolveCtx,
                context: EngineExecutionContext
            ) = values
        }

        private class Builder(
            private val objectSelections: ParsedSelections?,
            private val querySelections: ParsedSelections?,
            private val variables: List<SelectionSetVariable>,
            private val forChecker: Boolean,
            private val attribution: ExecutionAttribution?,
        ) {
            private val variablesByName = variables.groupBy { it.name }
                .mapValues { (name, values) ->
                    check(values.size == 1) {
                        "Found duplicate bindings for variable `$name"
                    }
                    values.first()
                }

            // variable name -> VariablesResolver
            // A VariablesResolver can resolve multiple variables and may be bound to
            // more than 1 key
            private val bindings: MutableMap<String, VariablesResolver> = mutableMapOf()

            fun buildAll(): List<VariablesResolver> {
                variables.forEach { v -> buildOne(emptySet(), v) }
                return bindings.values.distinct()
            }

            private fun buildOne(
                building: Set<String>,
                v: SelectionSetVariable
            ): VariablesResolver {
                val extant = bindings[v.name]
                if (extant != null) return extant

                val vr = when (v) {
                    is FromArgumentVariable -> createFromArgument(v)
                    is FromObjectFieldVariable -> createFromField(
                        building,
                        v,
                        objectSelections ?: throw IllegalStateException("No object selections provided, can't resolve variable `${v.name}` from object field `${v.valueFromPath}`")
                    )

                    is FromQueryFieldVariable -> createFromField(
                        building,
                        v,
                        querySelections ?: throw IllegalStateException("No query selections provided, can't resolve variable `${v.name}` from query field `${v.valueFromPath}`")
                    )
                }
                bindings[v.name] = vr
                return vr
            }

            private fun createFromArgument(v: FromArgumentVariable): VariablesResolver {
                val path = v.valueFromPath.split(".")
                return FromArgument(v.name, path)
            }

            private fun createFromField(
                building: Set<String>,
                v: FromFieldVariable,
                selections: ParsedSelections
            ): VariablesResolver {
                if (v.valueFromPath.isBlank()) {
                    throw IllegalArgumentException("Path for variable `${v.name}` is empty")
                }
                val path = v.valueFromPath.split(".")
                if (path.isEmpty()) {
                    throw IllegalArgumentException("Path for variable `${v.name}` is empty")
                }

                val view = requireNotNull(selections.filterToPath(path)) {
                    val selectionsStr = AstPrinter.printAst(selections.selections)
                    """
                        |No selections found for path `$path` in selection set:
                        |$selectionsStr
                    """.trimMargin()
                }
                val nestedVarRefs = view.selections.collectVariableReferences()
                if (v.name in building) {
                    // if v.name is in `building`, then this variables selection set somehow includes itself
                    // For example, given required selection set "x(a:$a)", a variable cycle is formed
                    // if $a is the value of x
                    throw VariableCycleException(v.name, selections)
                }
                val nestedVariablesResolvers = nestedVarRefs.map { vname ->
                    val nestedVar = checkNotNull(variablesByName[vname]) {
                        "Unknown variable $vname"
                    }
                    // add the current variable to the `building` set and recurse
                    buildOne(building + v.name, nestedVar)
                }

                val requiredSelectionSet = RequiredSelectionSet(
                    view,
                    nestedVariablesResolvers,
                    forChecker,
                    // concatenate the attribution to indicate the full chain of attributions
                    attribution?.toTagString()?.let { ExecutionAttribution.fromVariablesResolver(it) },
                )
                return FromFieldVariablesResolver(v.name, path, requiredSelectionSet)
            }
        }

        /** Create a [VariablesResolver] for a static map of pre-resolved values */
        fun const(vars: Map<String, Any?>): VariablesResolver =
            if (vars.isEmpty()) {
                Empty
            } else {
                Const(vars)
            }

        /** Create a [VariablesResolver] that produces resolved values using the provided [SelectionSetVariable]s */
        fun fromSelectionSetVariables(
            objectSelections: ParsedSelections?,
            querySelections: ParsedSelections?,
            variables: List<SelectionSetVariable>,
            forChecker: Boolean,
            attribution: ExecutionAttribution? = null,
        ): List<VariablesResolver> = Builder(objectSelections, querySelections, variables, forChecker, attribution).buildAll()
    }
}

data class Validated(val delegate: VariablesResolver) : VariablesResolver by delegate {
    override suspend fun resolve(
        ctx: ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?> =
        delegate.resolve(ctx, context).also { result ->
            check(result.keys == variableNames) {
                val extra = (result.keys - variableNames).let {
                    if (it.isNotEmpty()) {
                        "Extra keys: ${it.joinToString(",")}"
                    } else {
                        ""
                    }
                }
                val missing = (variableNames - result.keys).let {
                    if (it.isNotEmpty()) {
                        "Missing keys: ${it.joinToString(",")}"
                    } else {
                        ""
                    }
                }
                "VariablesProvider returned invalid variables. $extra $missing"
            }
        }
}

data class FromArgument(val name: String, val path: List<String>) : VariablesResolver {
    init {
        require(path.isNotEmpty()) {
            "Argument path for variable `$name` is empty"
        }
    }

    private val reader = InputValueReader(path)
    override val variableNames: Set<String> = setOf(name)

    override suspend fun resolve(
        ctx: ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?> = mapOf(name to reader.read(ctx.arguments))
}

data class FromFieldVariablesResolver(val name: String, val path: List<String>, override val requiredSelectionSet: RequiredSelectionSet) : VariablesResolver {
    init {
        require(path.isNotEmpty()) {
            "Path for variable `$name` is empty"
        }
    }

    private val reader = EngineDataReader(path)
    override val variableNames: Set<String> = setOf(name)

    override suspend fun resolve(
        ctx: ResolveCtx,
        context: EngineExecutionContext
    ): Map<String, Any?> = mapOf(name to reader.read(ctx.objectData))
}

/** Return a merged set of all variable names provided by these [VariablesResolver]s */
val List<VariablesResolver>.variableNames: Set<String>
    get() =
        flatMap { it.variableNames }.toSet()

/** Return a combined map of all variable values resolved by these [VariableResolver]s */
suspend fun List<VariablesResolver>.resolve(
    ctx: ResolveCtx,
    context: EngineExecutionContext
): Map<String, Any?> = fold(emptyMap()) { acc, vr -> acc + vr.resolve(ctx, context) }

/** check that all values provide disjoint sets of variables */
fun List<VariablesResolver>.checkDisjoint() {
    fold(emptySet<String>()) { seen, v ->
        v.variableNames.fold(seen) { seenNames, name ->
            check(name !in seenNames) {
                "Multiple VariablesResolver's provide a value for variable `$name`"
            }
            seenNames + name
        }
    }
}

class VariableCycleException(val varName: String, val selections: ParsedSelections) : Exception() {
    override val message: String get() {
        val selectionsStr = AstPrinter.printAst(selections.selections)
        return """
            |Detected cycle for variable `$varName` in selection set:
            |$selectionsStr
            """.trimMargin()
    }
}
