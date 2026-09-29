package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import model.ObjectEngineResult
import model.SelectionForest
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import semantics.shared.SharedOperationContext

/**
 * Supplies one configured Resolver26 dispatcher for the lifetime of each concrete JUnit class.
 *
 * JUnit creates test instances per method by default, so the extension owns the resource by test
 * class rather than storing it on an individual instance.
 */
@ExtendWith(Resolver26DispatcherExtension::class)
interface Resolver26DispatcherResource {
    val resolverDispatcher: ExecutorCoroutineDispatcher
        get() = Resolver26DispatcherExtension.dispatcherFor(javaClass)

    fun SharedOperationContext<*>.resolveWithTestDispatcher(selections: SelectionForest): ObjectEngineResult =
        resolve(
            selections = selections,
            coroutineContext = resolverDispatcher,
        )
}

internal class Resolver26DispatcherExtension : BeforeAllCallback, AfterAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        val testClass = context.requiredTestClass
        val dispatcher =
            ResolutionDispatcherFactory.create(configuredResolutionThreadCount())
        val existing = dispatchers.putIfAbsent(testClass, dispatcher)
        if (existing != null) {
            dispatcher.close()
            error("Resolver26 dispatcher already exists for ${testClass.name}")
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
                "No Resolver26 dispatcher is active for ${testClass.name}"
            }
    }
}
