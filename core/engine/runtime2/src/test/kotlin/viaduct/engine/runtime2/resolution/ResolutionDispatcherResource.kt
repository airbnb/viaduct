package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * Supplies one configured Resolution dispatcher for the lifetime of each concrete JUnit class.
 *
 * JUnit creates test instances per method by default, so the extension owns the resource by test
 * class rather than storing it on an individual instance.
 */
@ExtendWith(ResolutionDispatcherExtension::class)
interface ResolutionDispatcherResource {
    val resolverDispatcher: ExecutorCoroutineDispatcher
        get() = ResolutionDispatcherExtension.dispatcherFor(javaClass)

    fun SharedOperationContext<*>.resolveWithTestDispatcher(selections: SelectionForest): ObjectEngineResult =
        resolve(
            selections = selections,
            coroutineContext = resolverDispatcher,
        )
}

internal class ResolutionDispatcherExtension : BeforeAllCallback, AfterAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        val testClass = context.requiredTestClass
        val dispatcher =
            ResolutionDispatcherFactory.create(configuredResolutionThreadCount())
        val existing = dispatchers.putIfAbsent(testClass, dispatcher)
        if (existing != null) {
            dispatcher.close()
            error("Resolution dispatcher already exists for ${testClass.name}")
        }
    }

    override fun afterAll(context: ExtensionContext) {
        dispatchers.remove(context.requiredTestClass)?.close()
    }

    companion object {
        private val dispatchers =
            ConcurrentHashMap<Class<*>, ExecutorCoroutineDispatcher>()

        fun dispatcherFor(testClass: Class<*>): ExecutorCoroutineDispatcher =
            checkNotNull(dispatchers[testClass]) {
                "No Resolution dispatcher is active for ${testClass.name}"
            }
    }
}
