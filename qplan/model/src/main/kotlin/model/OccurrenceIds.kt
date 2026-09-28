package model

import model.registry.ResolverTarget
import model.registry.render

/**
 * Opaque identity of one concrete resolver application.
 *
 * Equality is structural over the identity of the Query-rooted [root] result and the exact result
 * tree [path]. Runtime code may carry, compare, hash, and print this value, but cannot decompose
 * its address.
 */
sealed interface ResolverOccurrenceId {
    companion object {
        /**
         * Returns the resolver occurrence identified by its Query-rooted result and exact path.
         */
        fun at(
            root: ObjectEngineResult,
            path: List<PathComponent>,
        ): ResolverOccurrenceId =
            ResolverOccurrenceIdImpl(
                root = root,
                path = path.toList(),
            )
    }
}

/**
 * Opaque identity of one variable declaration instantiated for one resolver occurrence.
 *
 * Equality is structural over the resolver occurrence, resolver target, and local variable name.
 */
sealed interface VariableInstanceId {
    /** The concrete resolver application that owns this variable instance. */
    val resolverOccurrenceId: ResolverOccurrenceId

    companion object {
        fun of(
            resolverOccurrenceId: ResolverOccurrenceId,
            resolverTarget: ResolverTarget,
            variableName: String,
        ): VariableInstanceId {
            require(variableName.isNotEmpty()) {
                "A variable instance requires a nonempty name"
            }
            return VariableInstanceIdImpl(
                resolverOccurrenceId = resolverOccurrenceId,
                resolverTarget = resolverTarget,
                variableName = variableName,
            )
        }
    }
}

private data class ResolverOccurrenceIdImpl(
    val root: ObjectEngineResult,
    val path: List<PathComponent>,
) : ResolverOccurrenceId {
    // Symbolic paths contain variable instances owned by earlier occurrences. Cache this
    // immutable address's hash so repeated binding/cell lookups do not rewalk that lineage.
    private val addressHash = 31 * root.hashCode() + path.hashCode()

    override fun hashCode(): Int = addressHash

    override fun toString(): String =
        "ResolverOccurrenceId(" +
            "root=${System.identityHashCode(root)}, " +
            "path=${path.renderOccurrencePath()}" +
            ")"
}

/**
 * Returns a hash of this occurrence's root-relative address.
 *
 * This deliberately omits the Query-rooted result identity and recursively treats symbolic object
 * keys the same way. It is suitable only for comparisons between otherwise equivalent executions
 * rooted at different OERs. Runtime identity and ordinary [Any.hashCode] remain root-qualified.
 */
internal fun ResolverOccurrenceId.rootRelativeHashCode(): Int =
    (this as ResolverOccurrenceIdImpl).path.fold(1) { hash, component ->
        31 * hash +
            when (component) {
                is ObjectEngineResult.ObjectKey -> {
                    var keyHash = component.field.hashCode()
                    keyHash = 31 * keyHash + component.arguments.rootRelativeHashCode()
                    keyHash
                }
                is ListEngineResult.Index -> component.hashCode()
            }
    }

/** Returns whether these occurrences have equal paths after omitting their root identities. */
internal fun ResolverOccurrenceId.hasSameRootRelativeAddressAs(
    other: ResolverOccurrenceId,
): Boolean {
    val leftPath = (this as ResolverOccurrenceIdImpl).path
    val rightPath = (other as ResolverOccurrenceIdImpl).path
    return leftPath.size == rightPath.size &&
        leftPath.zip(rightPath).all { (left, right) ->
            when {
                left is ObjectEngineResult.ObjectKey && right is ObjectEngineResult.ObjectKey ->
                    left.field == right.field &&
                        left.arguments.hasSameRootRelativeStructureAs(right.arguments)
                left is ListEngineResult.Index && right is ListEngineResult.Index -> left == right
                else -> false
            }
        }
}

private class VariableInstanceIdImpl(
    override val resolverOccurrenceId: ResolverOccurrenceId,
    private val resolverTarget: ResolverTarget,
    private val variableName: String,
) : VariableInstanceId {
    private val identityHash =
        31 * (31 * resolverOccurrenceId.hashCode() + resolverTarget.hashCode()) + variableName.hashCode()

    override fun equals(other: Any?): Boolean =
        other is VariableInstanceIdImpl &&
            resolverOccurrenceId == other.resolverOccurrenceId &&
            resolverTarget == other.resolverTarget &&
            variableName == other.variableName

    override fun hashCode(): Int = identityHash

    override fun toString(): String =
        "VariableInstanceId(" +
            "resolver=$resolverOccurrenceId, " +
            "target=${resolverTarget.render()}, " +
            "variable=$variableName" +
            ")"
}

private fun List<PathComponent>.renderOccurrencePath(): String =
    joinToString(prefix = "[", postfix = "]") { component ->
        when (component) {
            is ObjectEngineResult.ObjectKey ->
                "${component.field.containingDef.name}/${component.field.name}"
            is ListEngineResult.Index -> "index=${component.index}"
        }
    }
