package semantics.resolver26

import java.util.concurrent.atomic.AtomicBoolean
import model.requireQueryTypeDef
import semantics.shared.SharedOperationContext
import semantics.shared.SharedOERContext
import semantics.shared.SharedOrchestrationTask

/**
 * Prepared object task with one-shot dispatch and synchronous field installation/freezing.
 * Supplies its concretely typed operation through [operation], separately from the task lifecycle.
 */
internal abstract class CoroutineOrchestrationTask<O : SharedOperationContext<*>>(
    final override val operation: O,
    final override val objectOER: SharedOERContext,
) : SharedOrchestrationTask<O> {
    final override val queryOER: SharedOERContext =
        SharedOERContext.undemandedQuery(operation.world.schema.requireQueryTypeDef())

    private val launched = AtomicBoolean(false)

    internal abstract val hasActiveWork: Boolean

    /** Claims dispatch before validation or entering a request-root coroutine. */
    internal fun checkDispatch() {
        if (!launched.compareAndSet(false, true)) {
            throw duplicateDispatchException()
        }
        validateDispatch()
    }

    protected open fun duplicateDispatchException(): RuntimeException =
        IllegalArgumentException(
            "Orchestration task at ${objectOER.occurrence.path} was dispatched twice",
        )

    /** Installs field tasks before sealing this object's field set. */
    internal fun run() {
        installFieldTasks()
        objectOER.occurrence.target.freeze()
    }

    protected open fun validateDispatch() {}

    protected abstract fun installFieldTasks()
}
