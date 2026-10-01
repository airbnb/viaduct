package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicBoolean
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask

/**
 * Prepared object task with one-shot dispatch and synchronous publication preparation, dispatch, and freezing.
 * Supplies its concretely typed operation through [operation], separately from the task lifecycle.
 */
internal abstract class CoroutineOrchestrationTaskBase<O : SharedOperationContext<*>>(
    final override val operation: O,
    final override val objectOER: SharedOERContext,
    queryOER: SharedOERContext =
        SharedOERContext.undemandedQuery(operation.world.schema.requireQueryTypeDef()),
) : SharedOrchestrationTask<O> {
    final override val queryOER: SharedOERContext = queryOER

    private val dispatched = AtomicBoolean(false)

    internal abstract val hasActiveWork: Boolean

    /** Claims dispatch before validation or entering a request-root coroutine. */
    internal fun checkDispatch() {
        if (!dispatched.compareAndSet(false, true)) {
            throw duplicateDispatchException()
        }
        validateDispatch()
    }

    protected open fun duplicateDispatchException(): RuntimeException =
        IllegalArgumentException(
            "Orchestration task at ${objectOER.occurrence.path} was dispatched twice",
        )

    /** Prepares and dispatches field work before sealing this object's field set. */
    internal fun run() {
        prepareAndDispatchFieldWork()
        objectOER.occurrence.target.freeze()
        queryOER.occurrence.target.freeze()
    }

    protected open fun validateDispatch() {}

    protected abstract fun prepareAndDispatchFieldWork()
}
