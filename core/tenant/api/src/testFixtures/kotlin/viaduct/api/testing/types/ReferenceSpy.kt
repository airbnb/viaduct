package viaduct.api.testing.types

import viaduct.api.context.ExecutionContext
import viaduct.api.context.RootFieldCall
import viaduct.api.internal.InputLikeBase
import viaduct.api.reflect.RootObjectField
import viaduct.api.types.Arguments
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Records the references created by a resolver so tests can verify their production
 * [RootFieldCall]s after execution.
 *
 * Pass this spy to the resolver test runner, then call [assertCalledExactly] with the same
 * generated factory calls used by production code. Calls are compared in order by root path and
 * structurally equal arguments. Use [assertCallArgumentsOf] instead when you need to assert individual arguments as opposed to exact calls.
 */
@OptIn(ExperimentalApi::class, InternalApi::class)
class ReferenceSpy {
    private val calls = mutableListOf<ReferenceInvocation>()
    private var context: ExecutionContext? = null

    /**
     * Verifies that the resolver created exactly [expectedCalls], including order and repeats.
     */
    fun assertCalledExactly(vararg expectedCalls: RootFieldCall<*>) {
        val context = checkNotNull(this.context) {
            "ReferenceSpy must be passed to a resolver test runner before assertions are made."
        }
        val expected = expectedCalls.map { ReferenceInvocation(it.field().pathFromQueryRoot, it.arguments(context)) }
        val actual = calls.toList()
        if (actual != expected) {
            throw AssertionError(
                "Reference calls did not match.\n" +
                    "Expected:\n${expected.render()}\n" +
                    "Actual:\n${actual.render()}"
            )
        }
    }

    /**
     * Verifies [predicate] holds for the arguments of every reference the resolver created to
     * [field], in call order.
     */
    fun <A : Arguments> assertCallArgumentsOf(
        field: RootObjectField<*, *, A>,
        predicate: (List<A>) -> Boolean,
    ) {
        val invocations = invocationsOf(field)
        if (!predicate(invocations.argumentsAs())) {
            throw AssertionError(
                "Reference call arguments for '${field.pathName()}' did not match.\n" +
                    "Actual:\n${invocations.render()}"
            )
        }
    }

    /**
     * [assertCallArgumentsOf] for the first reference the resolver created to [field]. Fails when it
     * created none.
     */
    fun <A : Arguments> assertCallArgumentsOfFirst(
        field: RootObjectField<*, *, A>,
        predicate: (A) -> Boolean,
    ) {
        val invocations = invocationsOf(field)
        if (invocations.isEmpty()) {
            throw AssertionError("Expected a reference to '${field.pathName()}', but none was created.")
        }
        if (!predicate(invocations.argumentsAs<A>().first())) {
            throw AssertionError(
                "First reference call arguments for '${field.pathName()}' did not match.\n" +
                    "Actual:\n${invocations.render()}"
            )
        }
    }

    private fun invocationsOf(field: RootObjectField<*, *, *>): List<ReferenceInvocation> = calls.filter { it.path == field.pathFromQueryRoot }

    internal fun attach(context: ExecutionContext) {
        this.context = context
    }

    internal fun record(invocation: ReferenceInvocation) {
        calls += invocation
    }
}

internal data class ReferenceInvocation(
    val path: List<String>,
    val arguments: Arguments,
)

@Suppress("UNCHECKED_CAST")
private fun <A : Arguments> List<ReferenceInvocation>.argumentsAs(): List<A> = map { it.arguments as A }

@OptIn(ExperimentalApi::class, InternalApi::class)
private fun RootObjectField<*, *, *>.pathName(): String = pathFromQueryRoot.joinToString(".")

private fun List<ReferenceInvocation>.render(): String =
    if (isEmpty()) {
        "  <none>"
    } else {
        mapIndexed { index, invocation ->
            "  ${index + 1}. ${invocation.path.joinToString(".")} " +
                "(arguments=${invocation.arguments.describe()})"
        }.joinToString("\n")
    }

@OptIn(InternalApi::class)
private fun Arguments.describe(): Any = if (this is InputLikeBase) inputData else this
