package semantics.arbitrary

import java.security.MessageDigest
import model.Arguments
import model.EngineErrorData
import model.EngineInputData
import model.EngineInputListData
import model.EngineInputObjectData
import model.EngineOutputData
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.Selection
import model.SelectionForest
import model.inputType
import model.outputValue
import model.registry.ResolverRegistry
import model.requireArg
import model.requireField
import model.rootRelativeHashCode
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Resource limits for diagnostic resolution witnesses.
 *
 * Witnesses are test infrastructure, not semantic results. Bounds make accidental use on an
 * unexpectedly large generated value fail explicitly instead of retaining an unbounded trace.
 */
data class ResolutionWitnessBounds(
    val maxApplications: Int = 1_000_000,
    val maxResultNodes: Int = 1_000_000,
    val maxOperationSelections: Int = 100_000,
    val maxClosureSites: Int = 10_000,
    val maxFingerprintNodes: Int = 100_000,
    val maxFingerprintCharacters: Int = 1_000_000,
) {
    init {
        require(maxApplications > 0)
        require(maxResultNodes > 0)
        require(maxOperationSelections > 0)
        require(maxClosureSites > 0)
        require(maxFingerprintNodes > 0)
        require(maxFingerprintCharacters > 0)
    }
}

class ResolutionWitnessBoundExceededException(
    bound: String,
    maximum: Int,
) : IllegalStateException("Resolution witness exceeded $bound bound of $maximum")

/** A deterministic, permutation-invariant structural description used only for diagnostics. */
@JvmInline
value class ResolutionFingerprint(
    val value: String,
)

/** One selection-aware generated node callback and the demand delivered to it. */
data class SelectiveNodeResolverApplicationRecord(
    val typeName: String,
    val id: String,
    val suppliedDemandFingerprint: ResolutionFingerprint,
)

internal class SelectiveNodeResolverApplicationLog(
    private val bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
) {
    private val lock = Any()
    private val records = mutableListOf<SelectiveNodeResolverApplicationRecord>()
    private var recording = true

    fun record(
        typeName: String,
        id: String,
        suppliedDemand: SelectionForest,
    ) {
        val record =
            SelectiveNodeResolverApplicationRecord(
                typeName = typeName,
                id = id,
                suppliedDemandFingerprint = suppliedDemand.resolutionDigest(bounds),
            )
        synchronized(lock) {
            if (!recording) return
            if (records.size >= bounds.maxApplications) {
                throw ResolutionWitnessBoundExceededException(
                    "selective-node application",
                    bounds.maxApplications,
                )
            }
            records += record
        }
    }

    fun snapshot(): List<SelectiveNodeResolverApplicationRecord> = synchronized(lock) { records.toList() }

    fun clear() {
        synchronized(lock) { records.clear() }
    }

    fun <T> withoutRecording(block: () -> T): T {
        val previous = synchronized(lock) { recording.also { recording = false } }
        return try {
            block()
        } finally {
            synchronized(lock) { recording = previous }
        }
    }
}

/** The canonical field identity of one field-resolver application after fixture lowering. */
data class ResolverApplicationKey(
    val field: FieldCoordinate,
    val arguments: Arguments.Resolved,
)

/**
 * The observable identity of one deterministic field-resolver application.
 *
 * This identity distinguishes equal field coordinates and arguments when their materialized
 * resolver inputs differ, but it is not occurrence-complete. Use
 * [ResolverOccurrenceApplicationIdentity] when equal-input applications at different result paths
 * must remain distinct.
 */
data class ResolverApplicationIdentity(
    val key: ResolverApplicationKey,
    val inputFingerprint: ResolutionFingerprint,
)

data class ResolverApplicationObservation(
    val identity: ResolverApplicationIdentity,
    val suppliedDemandFingerprint: ResolutionFingerprint?,
)

/** One application key qualified by its exact Query-rooted resolver occurrence. */
data class ResolverOccurrenceApplicationKey(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val applicationKey: ResolverApplicationKey,
)

/** One application identity qualified by its exact Query-rooted resolver occurrence. */
data class ResolverOccurrenceApplicationIdentity(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val applicationIdentity: ResolverApplicationIdentity,
)

/** One occurrence-qualified application identity and its supplied-demand fingerprint. */
data class ResolverOccurrenceApplicationObservation(
    val identity: ResolverOccurrenceApplicationIdentity,
    val suppliedDemandFingerprint: ResolutionFingerprint?,
)

data class ResolverApplicationRecord(
    val key: ResolverApplicationKey,
    val inputFingerprint: ResolutionFingerprint,
    val suppliedDemandFingerprint: ResolutionFingerprint?,
) {
    val identity: ResolverApplicationIdentity
        get() = ResolverApplicationIdentity(key, inputFingerprint)

    val observation: ResolverApplicationObservation
        get() = ResolverApplicationObservation(identity, suppliedDemandFingerprint)

    companion object {
        fun capture(
            field: FieldCoordinate,
            arguments: Arguments.Resolved,
            input: EngineObjectData.Sync,
            suppliedDemand: SelectionForest? = null,
            bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
        ): ResolverApplicationRecord =
            ResolverApplicationRecord(
                key = ResolverApplicationKey(field, arguments),
                inputFingerprint = input.resolutionFingerprint(bounds),
                suppliedDemandFingerprint = suppliedDemand?.resolutionDigest(bounds),
            )
    }
}

data class ResolverOccurrenceApplicationRecord(
    val resolverOccurrenceId: ResolverOccurrenceId,
    val occurrencePath: List<PathComponent>,
    val application: ResolverApplicationRecord,
) {
    val occurrenceKey: ResolverOccurrenceApplicationKey
        get() = ResolverOccurrenceApplicationKey(resolverOccurrenceId, application.key)

    val identity: ResolverOccurrenceApplicationIdentity
        get() = ResolverOccurrenceApplicationIdentity(resolverOccurrenceId, application.identity)

    val observation: ResolverOccurrenceApplicationObservation
        get() =
            ResolverOccurrenceApplicationObservation(
                identity,
                application.suppliedDemandFingerprint,
            )

    companion object {
        fun capture(
            resolverOccurrenceId: ResolverOccurrenceId,
            occurrencePath: List<PathComponent>,
            field: FieldCoordinate,
            arguments: Arguments.Resolved,
            input: EngineObjectData.Sync,
            suppliedDemand: SelectionForest? = null,
            bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
        ): ResolverOccurrenceApplicationRecord =
            ResolverOccurrenceApplicationRecord(
                resolverOccurrenceId = resolverOccurrenceId,
                occurrencePath = occurrencePath.toList(),
                application =
                    ResolverApplicationRecord.capture(
                        field = field,
                        arguments = arguments,
                        input = input,
                        suppliedDemand = suppliedDemand,
                        bounds = bounds,
                    ),
            )
    }
}

/** Mutable bounded recorder intended to be snapshotted before an extensional oracle is run. */
class ResolutionApplicationLog(
    private val bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
) {
    private val lock = Any()
    private val records = mutableListOf<ResolverApplicationRecord>()
    @Volatile
    private var recording = true

    fun record(
        field: FieldCoordinate,
        arguments: Arguments.Resolved,
        input: EngineObjectData.Sync,
        suppliedDemand: SelectionForest? = null,
    ) {
        if (!recording) return
        val record =
            ResolverApplicationRecord.capture(
                field,
                arguments,
                input,
                suppliedDemand,
                bounds,
            )
        synchronized(lock) {
            if (!recording) return
            if (records.size >= bounds.maxApplications) {
                throw ResolutionWitnessBoundExceededException(
                    "application",
                    bounds.maxApplications,
                )
            }
            records += record
        }
    }

    fun <T> withoutRecording(block: () -> T): T {
        val previous: Boolean =
            synchronized(lock) {
                recording.also { recording = false }
            }
        return try {
            block()
        } finally {
            synchronized(lock) {
                recording = previous
            }
        }
    }

    fun snapshot(): ResolutionWitness =
        synchronized(lock) {
            ResolutionWitness(records.toList())
        }

    fun clear() {
        synchronized(lock) {
            records.clear()
        }
    }
}

/** Thread-safe bounded recorder for applications observed at exact OER occurrence paths. */
class ResolutionOccurrenceApplicationLog(
    private val bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
) {
    private val lock = Any()
    private val records = mutableListOf<ResolverOccurrenceApplicationRecord>()

    fun record(
        resolverOccurrenceId: ResolverOccurrenceId,
        occurrencePath: List<PathComponent>,
        field: FieldCoordinate,
        arguments: Arguments.Resolved,
        input: EngineObjectData.Sync,
        suppliedDemand: SelectionForest? = null,
    ) {
        val record =
            ResolverOccurrenceApplicationRecord.capture(
                resolverOccurrenceId = resolverOccurrenceId,
                occurrencePath = occurrencePath,
                field = field,
                arguments = arguments,
                input = input,
                suppliedDemand = suppliedDemand,
                bounds = bounds,
            )
        synchronized(lock) {
            if (records.size >= bounds.maxApplications) {
                throw ResolutionWitnessBoundExceededException(
                    "application",
                    bounds.maxApplications,
                )
            }
            records += record
        }
    }

    fun snapshot(): ResolutionOccurrenceWitness =
        synchronized(lock) {
            ResolutionOccurrenceWitness(records.toList())
        }
}

data class ResolutionWitness(
    val applications: List<ResolverApplicationRecord>,
) {
    /** Coarse counts that deliberately combine applications with different inputs. */
    fun applicationCounts(): Map<ResolverApplicationKey, Int> = applications.groupingBy(ResolverApplicationRecord::key).eachCount()

    /** Exact observable counts for deterministic field-resolver applications. */
    fun applicationIdentityCounts(): Map<ResolverApplicationIdentity, Int> = applications.groupingBy(ResolverApplicationRecord::identity).eachCount()

    /** Exact application counts including the demand supplied at each application boundary. */
    fun applicationObservationCounts(): Map<ResolverApplicationObservation, Int> = applications.groupingBy(ResolverApplicationRecord::observation).eachCount()

    fun duplicateApplications(): Map<ResolverApplicationKey, Int> = applicationCounts().filterValues { count -> count > 1 }

    fun unrelatedApplications(allowed: AllowedResolverClosure): List<ResolverApplicationRecord> =
        applications.filter { application ->
            application.key.field !in allowed.canonicalFields
        }
}

data class ResolutionOccurrenceWitness(
    val applications: List<ResolverOccurrenceApplicationRecord>,
) {
    fun applicationKeyCounts(): Map<ResolverOccurrenceApplicationKey, Int> = applications.groupingBy(ResolverOccurrenceApplicationRecord::occurrenceKey).eachCount()

    fun applicationIdentityCounts(): Map<ResolverOccurrenceApplicationIdentity, Int> = applications.groupingBy(ResolverOccurrenceApplicationRecord::identity).eachCount()

    fun applicationObservationCounts(): Map<ResolverOccurrenceApplicationObservation, Int> = applications.groupingBy(ResolverOccurrenceApplicationRecord::observation).eachCount()
}

/** Resolver fields conservatively reachable from fields directly selected by an operation. */
data class AllowedResolverClosure(
    val directlySelectedFields: Set<ViaductSchema.ObjectField>,
    val canonicalFields: Set<FieldCoordinate>,
)

fun SelectionForest.allowedResolverClosure(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): AllowedResolverClosure {
    val directlySelected = linkedSetOf<ViaductSchema.ObjectField>()
    var visitedSelections = 0

    fun collect(selections: SelectionForest) {
        selections.forEach { selection ->
            visitedSelections += 1
            if (visitedSelections > bounds.maxOperationSelections) {
                throw ResolutionWitnessBoundExceededException(
                    "operation-selection",
                    bounds.maxOperationSelections,
                )
            }
            selection.possibleTypes.forEach { possibleType ->
                val field = possibleType.field(selection.key.field.name)
                if (field is ViaductSchema.ObjectField && field in registry) {
                    directlySelected += field
                }
            }
            collect(selection.subselections)
        }
    }

    collect(this)
    val closure = linkedSetOf<ViaductSchema.ObjectField>()
    val pending = ArrayDeque<ViaductSchema.ObjectField>()
    directlySelected.forEach { site ->
        closure += site
        pending += site
    }
    if (closure.size > bounds.maxClosureSites) {
        throw ResolutionWitnessBoundExceededException(
            "closure-site",
            bounds.maxClosureSites,
        )
    }
    while (pending.isNotEmpty()) {
        val site = pending.removeFirst()
        registry.mayDemandFrom(site).forEach { dependency ->
            if (closure.add(dependency)) {
                if (closure.size > bounds.maxClosureSites) {
                    throw ResolutionWitnessBoundExceededException(
                        "closure-site",
                        bounds.maxClosureSites,
                    )
                }
                pending += dependency
            }
        }
    }

    return AllowedResolverClosure(
        directlySelectedFields = directlySelected,
        canonicalFields = closure.mapTo(linkedSetOf(), ViaductSchema.Field::fieldCoordinate),
    )
}

fun Arguments.resolutionFingerprint(
    expectedField: ViaductSchema.Field,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).arguments(this, expectedField),
    )

fun EngineObjectData.Sync.resolutionFingerprint(bounds: ResolutionWitnessBounds = ResolutionWitnessBounds()): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).output(this),
    )

/** A deterministic structural comparison key for the heterogeneous output-data union. */
internal fun EngineOutputData?.outputResolutionFingerprint(bounds: ResolutionWitnessBounds = ResolutionWitnessBounds()): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).output(this),
    )

fun SelectionForest.resolutionFingerprint(bounds: ResolutionWitnessBounds = ResolutionWitnessBounds()): ResolutionFingerprint =
    ResolutionFingerprint(
        FingerprintBudget(bounds).forest(this),
    )

/**
 * A compact deterministic structural digest for potentially large occurrence-preserving demand.
 */
fun SelectionForest.resolutionDigest(bounds: ResolutionWitnessBounds = ResolutionWitnessBounds()): ResolutionFingerprint {
    val canonical = resolutionFingerprint(bounds).value.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
    return ResolutionFingerprint(
        "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) },
    )
}

private class FingerprintBudget(
    private val bounds: ResolutionWitnessBounds,
) {
    private var nodes = 0

    fun arguments(
        arguments: Arguments,
        expectedField: ViaductSchema.Field,
    ): String =
        when (arguments) {
            Arguments.Error -> node("error-args")
            is Arguments.Resolved ->
                node(
                    "args(" +
                        arguments.fieldValues.entries
                            .sortedBy { entry -> entry.key }
                            .joinToString(",") { (name, value) ->
                                atom(name) +
                                    "=" +
                                    input(value, expectedField.requireArg(name).inputType)
                            } +
                        ")",
                )
            else -> node("open-args:${arguments.rootRelativeHashCode()}")
        }

    fun output(value: EngineOutputData?): String =
        when {
            value == null -> node("null")
            value is EngineErrorData -> node("error")
            value is Int -> node("int:$value")
            value is Double -> node("float:${value.toBits()}")
            value is String -> node("string:${atom(value)}")
            value is Boolean -> node("boolean:$value")
            value is List<*> ->
                node(
                    "list[" +
                        value.joinToString(separator = ",", transform = ::output) +
                        "]",
                )
            value is EngineObjectData.Sync ->
                node(
                    "object:${atom(value.type.name)}{" +
                        value.getSelections()
                            .map { selection ->
                                atom(selection) + "=" + output(value.outputValue(selection))
                            }.sorted()
                            .joinToString(",") +
                        "}",
                )
            else -> error("Unsupported output value $value")
        }

    fun forest(forest: SelectionForest): String {
        val selections = mutableListOf<String>()
        forest.forEach { selection -> selections += selection(selection) }
        return node("forest[" + selections.sorted().joinToString(",") + "]")
    }

    private fun selection(selection: Selection): String =
        node(
            "selection(" +
                canonicalKey(selection.key) +
                ";nominal=" + atom(selection.key.field.containingDef.name) +
                ";possible=" +
                selection.possibleTypes
                    .map { type -> atom(type.name) }
                    .sorted()
                    .joinToString(",", "[", "]") +
                ";children=" + forest(selection.subselections) +
                ")",
        )

    private fun canonicalKey(key: ObjectEngineResult.Key): String =
        node(
            "key(" +
                atom(key.field.containingDef.name) +
                "/" + atom(key.field.name) +
                ";" + arguments(key.arguments, key.field) +
                ")",
        )

    private fun input(
        value: EngineInputData?,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    ): String {
        if (value == null) return node("null")

        val elementType = expectedType.unwrapList()
        if (elementType != null) {
            val values = requireType<EngineInputListData>(value)
            return node(
                "list:${typeExpr(elementType)}[" +
                    values.joinToString(separator = ",") { element ->
                        input(element, elementType)
                    } +
                    "]",
            )
        }
        return when (val expectedNamedType = expectedType.baseTypeDef) {
            is ViaductSchema.Scalar ->
                when (expectedNamedType.name) {
                    "Int" -> node("int:${value as Int}")
                    "Float" ->
                        node("float:${(value as Double).toBits()}")
                    "String" ->
                        node("string:${atom(value as String)}")
                    "Boolean" ->
                        node("boolean:${value as Boolean}")
                    "ID" -> node("id:${atom(value as String)}")
                    else -> error("Unsupported scalar: ${expectedNamedType.name}")
                }
            is ViaductSchema.Enum ->
                node(
                    "enum:${atom(expectedNamedType.name)}:" +
                        atom(value as String),
                )
            is ViaductSchema.Input -> {
                val fields = requireType<EngineInputObjectData>(value)
                node(
                    "input-object:${atom(expectedNamedType.name)}{" +
                        fields.entries
                            .sortedBy(Map.Entry<String, EngineInputData?>::key)
                            .joinToString(",") { (name, fieldValue) ->
                                atom(name) +
                                    "=" +
                                    input(
                                        fieldValue,
                                        expectedNamedType.requireField(name).inputType,
                                    )
                            } +
                        "}",
                )
            }
            else -> error("Unsupported input type: ${expectedNamedType.name}")
        }
    }

    private inline fun <reified T> requireType(value: EngineInputData): T {
        require(value is T)
        return value
    }

    private fun typeExpr(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.TypeDef>): String {
        val elementType = typeExpr.unwrapList()
        return if (elementType == null) {
            "named(${atom(typeExpr.baseTypeDef.name)},nullable=${typeExpr.isNullable})"
        } else {
            "list(${typeExpr(elementType)},nullable=${typeExpr.isNullable})"
        }
    }

    private fun node(value: String): String {
        nodes += 1
        if (nodes > bounds.maxFingerprintNodes) {
            throw ResolutionWitnessBoundExceededException(
                "fingerprint-node",
                bounds.maxFingerprintNodes,
            )
        }
        if (value.length > bounds.maxFingerprintCharacters) {
            throw ResolutionWitnessBoundExceededException(
                "fingerprint-character",
                bounds.maxFingerprintCharacters,
            )
        }
        return value
    }

    private fun atom(value: String): String = "${value.length}:$value"
}

private fun ViaductSchema.Field.fieldCoordinate(): FieldCoordinate = FieldCoordinate(containingDef.name, name)
