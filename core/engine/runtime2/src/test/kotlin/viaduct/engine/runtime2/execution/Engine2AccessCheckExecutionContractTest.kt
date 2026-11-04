@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.execution.AccessCheckExecutionContract
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

@ExperimentalCoroutinesApi
class Engine2AccessCheckExecutionContractTest : AccessCheckExecutionContract, Engine2QueryExecutionContract {
    @Test
    fun `secret reads wait for field checks and respect denial`() =
        runTest(timeout = 10.seconds) {
            val allowedCheck = PendingAccessCheck()
            val deniedCheck = PendingAccessCheck()
            val independentFieldResolved = CompletableDeferred<Unit>()
            val returnedItemKeys = mutableListOf<Int>()
            val secretsReadByResolvers = mutableListOf<String>()

            // Each Item already contains its secret. The field checker protects Item.secret.
            val module = itemsReturningSecretsWithFieldChecks(
                secretsByKey = mapOf(1 to "secret-1", 2 to "secret-2"),
                checkSecret = { key ->
                    val check = if (key == 1) allowedCheck else deniedCheck
                    if (!check.awaitDecision()) throw SecurityException("secret denied")
                },
                // Nullable Item.secretCopy requires secret as input even when the client selects only secretCopy.
                secretCopyRequiredSelections = "secret",
                resolveSecretCopy = { requiredItemFields ->
                    (requiredItemFields.get("secret") as String).also { secretsReadByResolvers += it }
                },
                resolveIndependent = {
                    independentFieldResolved.complete(Unit)
                    "ok"
                },
                onItemReturned = { returnedItemKeys += it },
            )
            val execution = backgroundScope.async {
                executeEngine2Query(
                    module = module,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    // allowed and denied name the two item results in the response.
                    query = """
                        {
                          allowed: item(key: 1) { secretCopy }
                          denied: item(key: 2) { secretCopy }
                          independent
                        }
                    """.trimIndent(),
                )
            }

            allowedCheck.started.await()
            deniedCheck.started.await()
            independentFieldResolved.await()
            runCurrent()
            assertEquals(listOf(1, 2), returnedItemKeys.sorted())
            assertTrue(secretsReadByResolvers.isEmpty())
            assertFalse(execution.isCompleted)

            allowedCheck.decision.complete(true)
            runCurrent()
            assertEquals(listOf("secret-1"), secretsReadByResolvers)
            assertFalse(execution.isCompleted)

            deniedCheck.decision.complete(false)
            val result = execution.await()
            assertEquals(
                mapOf(
                    "allowed" to mapOf("secretCopy" to "secret-1"),
                    "denied" to mapOf("secretCopy" to null),
                    "independent" to "ok",
                ),
                result.getData(),
            )
            // Denial nulls the nullable copy field and reports its client path, not the internal secret input.
            val error = result.errors.single()
            assertEquals(listOf("denied", "secretCopy"), error.path)
            assertTrue(error.message.contains("secret denied"))
            assertEquals(listOf("secret-1"), secretsReadByResolvers)
        }

    @Test
    fun `secret reads wait for type checks and respect denial`() =
        runTest(timeout = 10.seconds) {
            val allowedCheck = PendingAccessCheck()
            val deniedCheck = PendingAccessCheck()
            val independentFieldResolved = CompletableDeferred<Unit>()
            val returnedItemKeys = mutableListOf<Int>()
            val secretsReadByResolvers = mutableListOf<String>()

            // Each Item already contains its secret. The type checker protects the whole Item.
            val module = itemsReturningSecretsWithTypeChecks(
                secretsByKey = mapOf(1 to "secret-1", 2 to "secret-2"),
                checkItem = { key ->
                    val check = if (key == 1) allowedCheck else deniedCheck
                    if (!check.awaitDecision()) throw SecurityException("item denied")
                },
                // Nullable Query copy fields require Item.secret as input beyond the fields requested by the client.
                allowedSecretCopyRequiredSelections = "item(key: 1) { secret }",
                deniedSecretCopyRequiredSelections = "item(key: 2) { secret }",
                resolveSecretCopy = { requiredQueryFields ->
                    val item = requiredQueryFields.get("item") as EngineObjectData.Sync
                    (item.get("secret") as String).also { secretsReadByResolvers += it }
                },
                resolveIndependent = {
                    independentFieldResolved.complete(Unit)
                    "ok"
                },
                onItemReturned = { returnedItemKeys += it },
            )
            val execution = backgroundScope.async {
                executeEngine2Query(
                    module = module,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    query = "{ allowedSecretCopy deniedSecretCopy independent }",
                )
            }

            allowedCheck.started.await()
            deniedCheck.started.await()
            independentFieldResolved.await()
            runCurrent()
            assertEquals(listOf(1, 2), returnedItemKeys.sorted())
            assertTrue(secretsReadByResolvers.isEmpty())
            assertFalse(execution.isCompleted)

            allowedCheck.decision.complete(true)
            runCurrent()
            assertEquals(listOf("secret-1"), secretsReadByResolvers)
            assertFalse(execution.isCompleted)

            deniedCheck.decision.complete(false)
            val result = execution.await()
            assertEquals(
                mapOf("allowedSecretCopy" to "secret-1", "deniedSecretCopy" to null, "independent" to "ok"),
                result.getData(),
            )
            // Denial nulls the nullable Query copy field; its error uses the client path, not item.secret.
            val error = result.errors.single()
            assertEquals(listOf("deniedSecretCopy"), error.path)
            assertTrue(error.message.contains("item denied"))
            assertEquals(listOf("secret-1"), secretsReadByResolvers)
        }

    @Engine2Disabled(Engine2DisabledKind.TODO, "ErrorData: Preserve resolver-error precedence when the field checker also fails")
    override fun `async field throws, checker throws`() {
        super<AccessCheckExecutionContract>.`async field throws, checker throws`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "AsyncEOD: Asynchronous object ingress fails before leaf completion")
    override fun `sync field throws, checker throws`() {
        super<AccessCheckExecutionContract>.`sync field throws, checker throws`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "ErrorData: Preserve resolver-error precedence for checked required selections")
    override fun `objectValueFragment field - field throws, checker throws`() {
        super<AccessCheckExecutionContract>.`objectValueFragment field - field throws, checker throws`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerVarsRSS: Checker variables resolvers with their own required selections are unsupported")
    override fun `checker with rss - access checks skipped`() {
        super<AccessCheckExecutionContract>.`checker with rss - access checks skipped`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "MutationChk: Mutation namespace field checkers are unsupported")
    override fun `mutation field with checker`() {
        super<AccessCheckExecutionContract>.`mutation field with checker`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerErrorData: Checker errors do not reach the configured error builder")
    override fun `field access check errors are handled`() {
        super<AccessCheckExecutionContract>.`field access check errors are handled`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerErrorData: Preserve list item results and custom error metadata on checker failure")
    override fun `list item access check errors are handled`() {
        super<AccessCheckExecutionContract>.`list item access check errors are handled`()
    }

    @Engine2Disabled(Engine2DisabledKind.TODO, "CheckerVarsRSS: Checker variables resolvers with their own required selections are unsupported")
    override fun `type checker RSS variable resolver can fetch from current object`() {
        super<AccessCheckExecutionContract>.`type checker RSS variable resolver can fetch from current object`()
    }

    private fun itemsReturningSecretsWithFieldChecks(
        secretsByKey: Map<Int, String>,
        checkSecret: suspend (Int) -> Unit,
        secretCopyRequiredSelections: String,
        resolveSecretCopy: suspend (EngineObjectData.Sync) -> String,
        resolveIndependent: suspend () -> String,
        onItemReturned: (Int) -> Unit,
    ): EngineTestModule =
        EngineTestModule(
            """
            extend type Query {
                item(key: Int!): Item @resolver
                independent: String @resolver
            }
            type Item {
                key: Int!
                secret: String
                secretCopy: String @resolver
            }
            """.trimIndent(),
        ) {
            field("Query" to "item") {
                resolver {
                    fn { selectors, _ ->
                        selectors.associateWith { selector ->
                            val key = selector.arguments.getValue("key") as Int
                            onItemReturned(key)
                            Result.success(createEngineObjectData("Item", mapOf("key" to key, "secret" to secretsByKey.getValue(key))))
                        }
                    }
                }
            }
            field("Item" to "secret") {
                checker {
                    objectSelections("object", "key")
                    fn { _, inputs ->
                        val key = inputs.getValue("object").get("key")
                        checkSecret(key as Int)
                    }
                }
            }
            field("Item" to "secretCopy") {
                resolver {
                    objectSelections(secretCopyRequiredSelections)
                    fn { _, requiredItemFields, _, _, _ -> resolveSecretCopy(requiredItemFields) }
                }
            }
            field("Query" to "independent") {
                resolver {
                    fn { _, _, _, _, _ -> resolveIndependent() }
                }
            }
        }

    private fun itemsReturningSecretsWithTypeChecks(
        secretsByKey: Map<Int, String>,
        checkItem: suspend (Int) -> Unit,
        allowedSecretCopyRequiredSelections: String,
        deniedSecretCopyRequiredSelections: String,
        resolveSecretCopy: suspend (EngineObjectData.Sync) -> String,
        resolveIndependent: suspend () -> String,
        onItemReturned: (Int) -> Unit,
    ): EngineTestModule =
        EngineTestModule(
            """
            extend type Query {
                item(key: Int!): Item @resolver
                allowedSecretCopy: String @resolver
                deniedSecretCopy: String @resolver
                independent: String @resolver
            }
            type Item {
                key: Int!
                secret: String
            }
            """.trimIndent(),
        ) {
            field("Query" to "item") {
                resolver {
                    fn { selectors, _ ->
                        selectors.associateWith { selector ->
                            val key = selector.arguments.getValue("key") as Int
                            onItemReturned(key)
                            Result.success(createEngineObjectData("Item", mapOf("key" to key, "secret" to secretsByKey.getValue(key))))
                        }
                    }
                }
            }
            type("Item") {
                checker {
                    objectSelections("object", "key")
                    fn { _, inputs ->
                        val key = inputs.getValue("object").get("key")
                        checkItem(key as Int)
                    }
                }
            }
            field("Query" to "allowedSecretCopy") {
                resolver {
                    objectSelections(allowedSecretCopyRequiredSelections)
                    fn { _, requiredQueryFields, _, _, _ -> resolveSecretCopy(requiredQueryFields) }
                }
            }
            field("Query" to "deniedSecretCopy") {
                resolver {
                    objectSelections(deniedSecretCopyRequiredSelections)
                    fn { _, requiredQueryFields, _, _, _ -> resolveSecretCopy(requiredQueryFields) }
                }
            }
            field("Query" to "independent") {
                resolver {
                    fn { _, _, _, _, _ -> resolveIndependent() }
                }
            }
        }

    private suspend fun executeEngine2Query(
        module: EngineTestModule,
        dispatcher: CoroutineDispatcher,
        query: String,
    ) = module.engine2Viaduct(dispatcher).execute(ExecutionInput.create(query), SchemaId.Base)

    private class PendingAccessCheck {
        val started = CompletableDeferred<Unit>()
        val decision = CompletableDeferred<Boolean>()

        suspend fun awaitDecision(): Boolean {
            started.complete(Unit)
            return decision.await()
        }
    }

    private fun EngineTestModule.engine2Viaduct(dispatcher: CoroutineDispatcher): StandardViaduct =
        StandardViaduct.Builder()
            .withSchemaConfiguration(SchemaConfiguration.fromSchema(fullSchema))
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(toModuleConfigSource()))
            .withCheckerExecutorFactory(MockCheckerExecutorFactory(checkerExecutors, typeCheckerExecutors))
            .withLenientResolverValidation()
            .withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
            })
            .withCoroutineInterop(object : CoroutineInterop by DefaultCoroutineInterop {
                override fun <T> enterThreadLocalCoroutineContext(
                    callerContext: CoroutineContext,
                    block: suspend CoroutineScope.() -> T,
                ): CompletableFuture<T> =
                    DefaultCoroutineInterop.enterThreadLocalCoroutineContext(callerContext) {
                        withContext(dispatcher, block)
                    }
            })
            .build()
}
