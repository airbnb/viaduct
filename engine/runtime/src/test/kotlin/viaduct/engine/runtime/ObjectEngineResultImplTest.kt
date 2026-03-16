@file:OptIn(ExperimentalTime::class)
@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.ACCESS_CHECK_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT

class ObjectEngineResultImplTest {
    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val graphQLObjectType: GraphQLObjectType = mockk()

    private fun newOER() = ObjectEngineResultImpl.newForType(graphQLObjectType)

    @Test
    fun `test write first, read later`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")

            engine.computeIfAbsent(key) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
            }

            assertEquals("value", engine.fetch(key, RAW_VALUE_SLOT))
            assertEquals("value", engine.getValue(key, RAW_VALUE_SLOT).await())
            assertEquals(CheckerResult.Success, engine.fetch(key, ACCESS_CHECK_SLOT))
        }
    }

    @Test
    fun `test read first, write later`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")

            // Start a fetch operation that will initially suspend
            val deferred = testScope.async {
                engine.fetch(key, RAW_VALUE_SLOT)
            }

            // Give the fetch a chance to start
            delay(100)

            // Now write the value
            engine.computeIfAbsent(key) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
            }

            // Verify the fetch gets the value
            assertEquals("value", deferred.await())
            assertEquals(CheckerResult.Success, engine.fetch(key, ACCESS_CHECK_SLOT))
        }
    }

    @Test
    fun `test concurrent computeIfAbsent attempts`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val computeAttempts = 100
            val successfulComputes = AtomicInteger(0)

            // Try to call computeIfAbsent simultaneously from multiple coroutines
            val jobs = List(computeAttempts) {
                testScope.launch {
                    engine.computeIfAbsent(key) { setter ->
                        successfulComputes.incrementAndGet()
                        setter.set(RAW_VALUE_SLOT, Value.fromValue("value"))
                        setter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
                    }
                }
            }

            jobs.joinAll()

            // Verify only one compute succeeded
            assertEquals(1, successfulComputes.get())
        }
    }

    @Test
    fun `test error propagation`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val testException = RuntimeException("test error")

            // Start a read
            val deferred = testScope.async {
                engine.fetch(key, RAW_VALUE_SLOT)
            }

            delay(100)

            // Complete with error
            engine.computeIfAbsent(key) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromThrowable<Nothing>(testException))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
            }

            // Verify error is propagated
            val exception = assertThrows<RuntimeException> {
                runBlocking { deferred.await() }
            }
            assertEquals(testException.message, exception.message)
        }
    }

    @Test
    fun `test fetch with arguments`() {
        runBlocking {
            val engine = newOER()
            val key1 = ObjectEngineResult.Key("test", arguments = mapOf("arg" to 1))
            val key2 = ObjectEngineResult.Key("test", arguments = mapOf("arg" to 2))

            // Write different values for different arguments
            engine.computeIfAbsent(key1) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value1"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
            }
            engine.computeIfAbsent(key2) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value2"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(MockCheckerErrorResult(IllegalAccessException("no access"))))
            }

            // Verify correct values are returned
            assertEquals("value1", engine.fetch(key1, RAW_VALUE_SLOT))
            assertEquals("value2", engine.fetch(key2, RAW_VALUE_SLOT))
            val checkerResult1 = engine.fetch(key1, ACCESS_CHECK_SLOT)
            val checkerResult2 = engine.fetch(key2, ACCESS_CHECK_SLOT)
            assertTrue(checkerResult1 is CheckerResult.Success)
            assertTrue((checkerResult2 as MockCheckerErrorResult).error is IllegalAccessException)
        }
    }

    @Test
    fun `test fetch with alias`() {
        runBlocking {
            val engine = newOER()
            val key1 = ObjectEngineResult.Key("test", "alias1")
            val key2 = ObjectEngineResult.Key("test", "alias2")

            // Write different values for different arguments
            engine.computeIfAbsent(key1) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value1"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
            }
            engine.computeIfAbsent(key2) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value2"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(MockCheckerErrorResult(IllegalAccessException("no access"))))
            }

            // Verify correct values are returned
            assertEquals("value1", engine.fetch(key1, RAW_VALUE_SLOT))
            assertEquals("value2", engine.fetch(key2, RAW_VALUE_SLOT))
            val checkerResult1 = engine.fetch(key1, ACCESS_CHECK_SLOT)
            val checkerResult2 = engine.fetch(key2, ACCESS_CHECK_SLOT)
            assertTrue(checkerResult1 is CheckerResult.Success)
            assertTrue((checkerResult2 as MockCheckerErrorResult).error is IllegalAccessException)
        }
    }

    @Test
    fun `test fetch with alias -- alias matches field name`() {
        runBlocking {
            val engine = newOER()
            val key1 = ObjectEngineResult.Key("test")
            val key2 = ObjectEngineResult.Key("test", "test")

            engine.computeIfAbsent(key1) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value1"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
            }
            // This compute should not execute
            engine.computeIfAbsent(key2) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value2"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
            }

            // Verify correct values are returned
            assertEquals("value1", engine.fetch(key1, RAW_VALUE_SLOT))
            assertEquals("value1", engine.fetch(key2, RAW_VALUE_SLOT))
            assertEquals(CheckerResult.Success, engine.fetch(key1, ACCESS_CHECK_SLOT))
            assertEquals(CheckerResult.Success, engine.fetch(key2, ACCESS_CHECK_SLOT))
        }
    }

    @Test
    fun `test stress test with concurrent reads and writes`() {
        runBlocking {
            val engine = newOER()
            val keys = List(10) { i -> ObjectEngineResult.Key("test$i") }
            val writeJobs = mutableListOf<Job>()
            val readResults = ConcurrentHashMap<String, MutableSet<String>>()

            // Start multiple readers for each key
            val readJobs = keys.flatMap { key ->
                List(50) {
                    testScope.launch {
                        val value = engine.fetch(key, RAW_VALUE_SLOT)
                        engine.fetch(key, ACCESS_CHECK_SLOT)
                        readResults.computeIfAbsent(key.name) {
                            ConcurrentHashMap.newKeySet()
                        }.add(value as String)
                    }
                }
            }

            // Start writers
            keys.forEach { key ->
                writeJobs.add(
                    testScope.launch {
                        engine.computeIfAbsent(key) { setter ->
                            setter.set(RAW_VALUE_SLOT, Value.fromValue("value-${key.name}"))
                            setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
                        }
                    }
                )
            }

            // Wait for all operations to complete
            writeJobs.joinAll()
            readJobs.joinAll()

            // Verify each key got exactly one unique value
            readResults.forEach { (key, values) ->
                assertEquals(1, values.size, "Key $key had multiple different values: $values")
                assertEquals("value-test${key.substring(4)}", values.first())
            }
        }
    }

    @Test
    fun `test race between multiple claim attempts`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val coroutines = 10
            val successfulComputes = AtomicInteger(0)

            // Create a barrier to ensure all coroutines start simultaneously
            val barrier = Mutex(locked = true)

            // Launch coroutines that will race to claim and complete
            val jobs = List(coroutines) { routineNum ->
                testScope.launch {
                    // Wait for the signal to start
                    barrier.withLock { }

                    engine.computeIfAbsent(key) { setter ->
                        successfulComputes.incrementAndGet()
                        setter.set(RAW_VALUE_SLOT, Value.fromValue("value-$routineNum"))
                        setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
                    }
                }
            }

            // Start all coroutines simultaneously
            barrier.unlock()

            // Wait for all coroutines to complete
            jobs.joinAll()

            // Verify only one computeIfAbsent succeeded
            assertEquals(1, successfulComputes.get(), "Only one computeIfAbsent should succeed")
        }
    }

    @Test
    fun `test race between readers and writer`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val readers = 10
            val allValues = ConcurrentHashMap<String, Boolean>()
            val allCheckerValues = ConcurrentHashMap<CheckerResult, Boolean>()

            val readersStarted = CompletableDeferred<Unit>()
            val writerStarted = CompletableDeferred<Unit>()
            val barrier = Mutex(locked = true)

            // Start readers
            val readerJobs = List(readers) {
                testScope.launch {
                    barrier.withLock { }
                    readersStarted.complete(Unit)
                    val value = engine.fetch(key, RAW_VALUE_SLOT)
                    val checkerValue = engine.fetch(key, ACCESS_CHECK_SLOT)
                    allValues[value as String] = true
                    allCheckerValues[checkerValue as CheckerResult] = true
                }
            }

            // Start writer
            val writerJob = testScope.launch {
                writerStarted.complete(Unit)
                readersStarted.await()
                delay(100) // Give readers a chance to get going

                engine.computeIfAbsent(key) { setter ->
                    setter.set(RAW_VALUE_SLOT, Value.fromValue("final-value"))
                    setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
                }
            }

            // Start all coroutines
            barrier.unlock()

            // Wait for completion
            writerStarted.await()
            readerJobs.joinAll()
            writerJob.join()

            // Verify all readers got the same value
            assertEquals(1, allValues.size, "All readers should get the same value")
            assertTrue(allValues.containsKey("final-value"), "All readers should get the completed value")
            assertEquals(1, allCheckerValues.size, "All readers should get the same value")
            assertTrue(allCheckerValues.containsKey(CheckerResult.Success), "All readers should get the completed value")
        }
    }

    @Test
    fun `test race between computeIfAbsent and read`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val readerStarted = CompletableDeferred<Unit>()
            val writerStarted = CompletableDeferred<Unit>()
            val valueRead = AtomicReference<Any?>()

            // Start a reader coroutine
            val readerJob = testScope.launch {
                readerStarted.complete(Unit)
                writerStarted.await()
                valueRead.set(engine.fetch(key, RAW_VALUE_SLOT))
            }

            // Start a writer coroutine
            val writerJob = testScope.launch {
                readerStarted.await()
                writerStarted.complete(Unit)
                engine.computeIfAbsent(key) { setter ->
                    setter.set(RAW_VALUE_SLOT, Value.fromValue("test-value"))
                    setter.set(ACCESS_CHECK_SLOT, Value.fromValue("test-value"))
                }
            }

            // Wait for completion
            withTimeout(5.seconds) {
                joinAll(readerJob, writerJob)
            }

            // Verify the reader got the correct value
            assertEquals("test-value", valueRead.get())
        }
    }

    @Test
    fun `test computeIfAbsent failing prevents subsequent claims`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val firstComputerFailed = CompletableDeferred<Unit>()
            val successfulComputes = AtomicInteger(0)
            val claimAttempted = AtomicInteger(0)

            // First compute - will fail to complete
            val firstClaimerJob = testScope.launch {
                claimAttempted.incrementAndGet()
                runCatching {
                    engine.computeIfAbsent(key) { _ ->
                        successfulComputes.incrementAndGet()
                        firstComputerFailed.complete(Unit)
                        throw RuntimeException("Simulated failure")
                    }
                }
            }

            // Second compute - should fail to compute
            val secondClaimerJob = testScope.launch {
                firstComputerFailed.await() // Wait for the first claimer to fail
                engine.computeIfAbsent(key) { _ ->
                    successfulComputes.incrementAndGet()
                }
                claimAttempted.incrementAndGet()
            }

            // Wait for completion
            withTimeout(5.seconds) {
                joinAll(firstClaimerJob, secondClaimerJob)
            }

            // Verify behavior
            assertEquals(1, successfulComputes.get(), "Only first claim should succeed")
            assertEquals(2, claimAttempted.get(), "Both claimers should have attempted")
        }
    }

    @Test
    fun `test computeIfAbsent with success`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val computations = AtomicInteger(0)

            val result = engine.computeIfAbsent(key) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value-${computations.incrementAndGet()}"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
            }
            val value = result.await()
            assertEquals("value-1", value)
            assertEquals(1, computations.get())

            // Second call should return cached value
            val result2 = engine.computeIfAbsent(key) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("value-${computations.incrementAndGet()}"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
            }
            val value2 = result2.await()

            assertEquals("value-1", value2)
            assertEquals(CheckerResult.Success, engine.fetch(key, ACCESS_CHECK_SLOT))
            assertEquals(1, computations.get())
        }
    }

    @Test
    fun `test computeIfAbsent with exception`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")

            val result = runCatching {
                engine.computeIfAbsent(key) {
                    throw RuntimeException("Computation failed")
                }.await()
            }

            assertTrue(result.isFailure)
            val firstException = result.exceptionOrNull()
            with(firstException) {
                assertTrue(this is RuntimeException)
                assertEquals("Computation failed", this.message)
            }

            // The second call should return an error
            val result2 = runCatching {
                engine.computeIfAbsent(key) { setter ->
                    setter.set(RAW_VALUE_SLOT, Value.fromValue("shouldn't get here"))
                    setter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
                }.await()
            }

            assertTrue(result2.isFailure)
        }
    }

    @Test
    fun `test concurrent computeIfAbsent calls`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val computations = AtomicInteger(0)
            val barrier = Mutex(locked = true)

            val deferreds = List(10) {
                testScope.async {
                    barrier.withLock { }
                    engine.computeIfAbsent(key) { setter ->
                        setter.set(
                            RAW_VALUE_SLOT,
                            Value.fromDeferred(
                                async {
                                    delay(100) // Simulate work
                                    "value-${computations.incrementAndGet()}"
                                }
                            )
                        )
                        setter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
                    }
                }
            }

            barrier.unlock()
            val results = deferreds.awaitAll()
            val values = results.map { it.await() }

            // Verify all got the same value
            assertEquals(1, computations.get())
            assertTrue(values.all { it == "value-1" })
        }
    }

    @Test
    fun `test race between computation and fetch`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val computationStarted = CompletableDeferred<Unit>()
            val computationShouldFinish = CompletableDeferred<Unit>()

            // Start the computing coroutine
            val computeDeferred = testScope.async {
                engine.computeIfAbsent(key) { setter ->
                    computationStarted.complete(Unit)
                    setter.set(
                        RAW_VALUE_SLOT,
                        Value.fromDeferred(
                            async {
                                computationShouldFinish.await()
                                "computed-value"
                            }
                        )
                    )
                    setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
                }
            }

            // Start fetching coroutine after computation has started
            val fetchDeferred = testScope.async {
                computationStarted.await()
                engine.computeIfAbsent(key) { setter ->
                    setter.set(RAW_VALUE_SLOT, Value.fromValue("shouldn't-get-here"))
                    setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
                }
            }

            // Let computation finish
            delay(100)
            computationShouldFinish.complete(Unit)

            // Wait for both jobs
            val results = awaitAll(computeDeferred, fetchDeferred)
            val values = results.map { it.await() }

            // Verify both got the same value
            assertTrue(values.all { it == "computed-value" })
        }
    }

    @Test
    fun `test cancellation during computeIfAbsent`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val computeStarted = CompletableDeferred<Unit>()

            // Start a computation that will be canceled
            val job = testScope.launch {
                try {
                    engine.computeIfAbsent(key) { setter ->
                        computeStarted.complete(Unit)
                        setter.set(
                            RAW_VALUE_SLOT,
                            Value.fromDeferred(
                                async {
                                    delay(Long.MAX_VALUE) // Will be canceled
                                    "value"
                                }
                            )
                        )
                        setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
                    }
                } catch (_: CancellationException) {
                    // Expected
                }
            }

            // Wait for compute to start, then cancel
            computeStarted.await()
            job.cancel()
            job.join()

            // Verify the field is now in the error state
            assertFailsWith<CancellationException> {
                engine.fetch(key, RAW_VALUE_SLOT)
            }
        }
    }

    @Test
    fun `test concurrent reads with delayed write`() {
        runBlocking {
            val engine = newOER()
            val key = ObjectEngineResult.Key("test")
            val readers = 5
            val results = ConcurrentHashMap<Int, Pair<String, CheckerResult>>()
            val writeStarted = CompletableDeferred<Unit>()

            // Start multiple readers
            val readJobs = List(readers) { index ->
                testScope.launch {
                    writeStarted.await() // Wait for write to start
                    val result = engine.fetch(key, RAW_VALUE_SLOT)
                    val checkerResult = engine.fetch(key, ACCESS_CHECK_SLOT)
                    results[index] = (result as String) to (checkerResult as CheckerResult)
                }
            }

            // Start delayed write
            val writeJob = testScope.launch {
                delay(100) // Give readers a chance to queue up
                writeStarted.complete(Unit)
                engine.computeIfAbsent(key) { setter ->
                    setter.set(RAW_VALUE_SLOT, Value.fromValue("test-value"))
                    setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
                }
            }

            joinAll(writeJob, *readJobs.toTypedArray())

            // Verify all readers got the same value
            assertEquals(readers, results.size)
            results.values.forEach { value ->
                assertEquals("test-value" to CheckerResult.Success, value)
            }
        }
    }

    @Test
    fun `test fetch in pending and resolved exceptionally states`() {
        runBlocking {
            val engine = ObjectEngineResultImpl.newPendingForType(graphQLObjectType)
            val key = ObjectEngineResult.Key("test")
            engine.computeIfAbsent(key) { setter ->
                setter.set(RAW_VALUE_SLOT, Value.fromValue("test-value"))
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(CheckerResult.Success))
            }
            assertEquals("test-value", engine.fetch(key, RAW_VALUE_SLOT))
            assertEquals(CheckerResult.Success, engine.fetch(key, ACCESS_CHECK_SLOT))

            engine.resolveExceptionally(RuntimeException("failed"))
            assertThrows<RuntimeException> { engine.fetch(key, RAW_VALUE_SLOT) }
            assertThrows<RuntimeException> { engine.fetch(key, ACCESS_CHECK_SLOT) }
        }
    }

    @Test
    fun `test resolvedExceptionOrNull in Resolved state`() {
        runBlocking {
            val engine = ObjectEngineResultImpl.newPendingForType(graphQLObjectType)
            val deferred = testScope.async { engine.resolvedExceptionOrNull() }
            engine.resolve()
            assertNull(deferred.await())
        }
    }

    @Test
    fun `test resolvedExceptionOrNull in ResolvedExceptionally state`() {
        runBlocking {
            val engine = ObjectEngineResultImpl.newPendingForType(graphQLObjectType)
            val deferred = testScope.async { engine.resolvedExceptionOrNull() }
            engine.resolveExceptionally(RuntimeException("bloop"))
            with(deferred.await()) {
                assertTrue(this is RuntimeException)
                assertEquals("bloop", this.message)
            }
        }
    }

    @Nested
    inner class NewFromMap {
        @Test
        fun `newFromMap with nested data and errors`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { user: User }
                        type User {
                            id: ID!
                            name: String
                            friends: [User]
                            posts: [Post]
                        }
                        type Post {
                            id: ID!
                            title: String
                        }
                    """
                )
                val userType = schema.schema.getObjectType("User")

                val data = mapOf(
                    "id" to "123",
                    "name" to "Alice",
                    "friends" to listOf(
                        mapOf(
                            "id" to "456",
                            "name" to null
                        )
                    ),
                    "posts" to null
                )

                val errors: MutableList<Pair<String, Throwable>> = mutableListOf(
                    "friends.0.name" to RuntimeException("Name not available"),
                    "posts" to RuntimeException("Posts not accessible")
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    userType,
                    data,
                    errors,
                    emptyList(),
                    schema,
                    createEngineSelectionSet("User", "id name friends { id name } posts { id title }", emptyMap(), schema)
                )

                // Test successful fields
                assertEquals("123", result.fetch(ObjectEngineResult.Key("id"), RAW_VALUE_SLOT))
                assertEquals("Alice", result.fetch(ObjectEngineResult.Key("name"), RAW_VALUE_SLOT))

                // Test nested ObjectEngineResult
                val friends = result.fetch(ObjectEngineResult.Key("friends"), RAW_VALUE_SLOT) as List<*>
                val friend = (friends[0] as Cell).fetch(RAW_VALUE_SLOT) as ObjectEngineResultImpl
                assertEquals("456", friend.fetch(ObjectEngineResult.Key("id"), RAW_VALUE_SLOT))

                // Test error propagation
                assertFailsWith<RuntimeException>("Name not available") {
                    friend.fetch(ObjectEngineResult.Key("name"), RAW_VALUE_SLOT)
                }

                assertFailsWith<RuntimeException>("Posts not accessible") {
                    result.fetch(ObjectEngineResult.Key("posts"), RAW_VALUE_SLOT)
                }
            }
        }

        @Test
        fun `newFromMap with aliases, variables, and arguments from both selection and schema`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { user: User }
                        type User {
                            id: ID!
                            name: String
                            friends(onlyDirect: Boolean): [User]
                            friendCount(onlyDirect: Boolean = false): Int
                            socialMedia(siteName: String): String
                        }
                    """
                )
                val userType = schema.schema.getObjectType("User")

                val data = mapOf(
                    "id" to "123",
                    "nickname" to "Alice",
                    "friends" to listOf(
                        mapOf("id" to "456", "name" to "Bob")
                    ),
                    "friendCount" to 10,
                    "socialMedia" to "https://example.com"
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    userType,
                    data,
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "User",
                        """
                            id
                            nickname: name
                            friends(onlyDirect: true) {
                                id
                                name
                            }
                            friendCount
                            socialMedia(siteName: ${'$'}siteVar)
                        """.trimIndent(),
                        mapOf("siteVar" to "example"),
                        schema
                    )
                )

                // Test successful fields
                assertEquals("123", result.fetch(ObjectEngineResult.Key("id"), RAW_VALUE_SLOT))
                assertEquals("Alice", result.fetch(ObjectEngineResult.Key("name", "nickname"), RAW_VALUE_SLOT))

                // Test nested ObjectEngineResult with argument explicit specified
                val friends = result.fetch(ObjectEngineResult.Key("friends", null, mapOf("onlyDirect" to true)), RAW_VALUE_SLOT) as List<*>
                val friend = (friends[0] as Cell).fetch(RAW_VALUE_SLOT) as ObjectEngineResultImpl
                assertEquals("456", friend.fetch(ObjectEngineResult.Key("id"), RAW_VALUE_SLOT))

                // Test ObjectEngineResult with argument implicit from schema
                assertEquals(10, result.fetch(ObjectEngineResult.Key("friendCount", null, mapOf("onlyDirect" to false)), RAW_VALUE_SLOT))

                // Test variable references
                assertEquals("https://example.com", result.fetch(ObjectEngineResult.Key("socialMedia", null, mapOf("siteName" to "example")), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap with multiple fields selected with aliases`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { foo: Foo }
                        type Foo { bar(id:Int):Bar }
                        type Bar { a:Int, b:Int }
                    """
                )
                val fooType = schema.schema.getObjectType("Foo")
                val data = mapOf(
                    "b1" to mapOf("a" to 12),
                    "b2" to mapOf("b" to 21)
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    fooType,
                    data,
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Foo",
                        """
                            b1: bar(id: 1) { a }
                            b2: bar(id: 2) { b }
                        """.trimIndent(),
                        emptyMap(),
                        schema
                    )
                )

                // Test successful fields
                val b1 = result.fetch(ObjectEngineResult.Key("bar", "b1", mapOf("id" to 1)), RAW_VALUE_SLOT) as ObjectEngineResultImpl
                val b2 = result.fetch(ObjectEngineResult.Key("bar", "b2", mapOf("id" to 2)), RAW_VALUE_SLOT) as ObjectEngineResultImpl
                assertEquals(12, b1.fetch(ObjectEngineResult.Key("a"), RAW_VALUE_SLOT))
                assertEquals(21, b2.fetch(ObjectEngineResult.Key("b"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap converts __typename selection`() {
            runBlocking {
                val schema = createSchema("type Query { x:Int }")

                val result = ObjectEngineResultTestHelper.newFromMap(
                    schema.schema.queryType,
                    mapOf("__typename" to "Query"),
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Query",
                        "__typename",
                        emptyMap(),
                        schema
                    )
                )

                assertEquals("Query", result.fetch(ObjectEngineResult.Key("__typename"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap with a widening spread`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { foo: Foo }
                        interface I { x: Int }
                        type Foo implements I { x:Int }
                    """
                )
                val fooType = schema.schema.getObjectType("Foo")
                val data = mapOf("x" to 42)

                val result = ObjectEngineResultTestHelper.newFromMap(
                    fooType,
                    data,
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Foo",
                        "... on I { x }",
                        emptyMap(),
                        schema
                    )
                )

                // Test successful fields
                assertEquals(42, result.fetch(ObjectEngineResult.Key("x"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap with a narrowing spread`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { i:I }
                        interface I { x:Int }
                        type Foo implements I { x:Int, y:Int }
                    """
                )
                val fooType = schema.schema.getObjectType("Foo")
                val data = mapOf("y" to 42)

                val result = ObjectEngineResultTestHelper.newFromMap(
                    fooType,
                    data,
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "I",
                        "... on Foo { y }",
                        emptyMap(),
                        schema
                    )
                )

                // Test successful fields
                assertEquals(42, result.fetch(ObjectEngineResult.Key("y"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap with a nested narrowing spread`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { i:I }
                        interface I { x:Int }
                        type Foo implements I { x:Int, y:Int }
                    """
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    schema.schema.queryType,
                    mapOf("i" to mapOf("__typename" to "Foo", "y" to 42)),
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Query",
                        "i { ... on Foo { y } }",
                        emptyMap(),
                        schema
                    )
                )

                // Test successful fields
                val i = result.fetch(ObjectEngineResult.Key("i"), RAW_VALUE_SLOT) as ObjectEngineResult
                val y = i.fetch(ObjectEngineResult.Key("y"), RAW_VALUE_SLOT)
                assertEquals(42, y)
            }
        }

        @Test
        @Suppress("UNCHECKED_CAST")
        fun `newFromMap with list of objects`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { u:[U] }
                        union U = Foo
                        type Foo { x:Int }
                    """
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    schema.schema.queryType,
                    mapOf("u" to listOf(mapOf("__typename" to "Foo", "x" to 42))),
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Query",
                        "u { ... on Foo { x } }",
                        emptyMap(),
                        schema
                    )
                )

                // Test successful fields
                val us = result.fetch(ObjectEngineResult.Key("u"), RAW_VALUE_SLOT) as List<Cell>
                assertEquals(1, us.size)
                val u = us[0].fetch(RAW_VALUE_SLOT) as ObjectEngineResultImpl
                assertEquals(42, u.fetch(ObjectEngineResult.Key("x"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap with nested objects`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { u:U }
                        union U = Foo
                        type Foo { bar:Bar }
                        type Bar { x:Int }
                    """
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    schema.schema.queryType,
                    mapOf("u" to mapOf("__typename" to "Foo", "bar" to mapOf("x" to 42))),
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Query",
                        "u { ... on Foo { bar { x } } }",
                        emptyMap(),
                        schema
                    )
                )

                // Test successful fields
                val foo = result.fetch(ObjectEngineResult.Key("u"), RAW_VALUE_SLOT) as ObjectEngineResultImpl
                assertEquals("Foo", foo.type.name)
                val bar = foo.fetch(ObjectEngineResult.Key("bar"), RAW_VALUE_SLOT) as ObjectEngineResultImpl
                assertEquals(42, bar.fetch(ObjectEngineResult.Key("x"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap with a nested narrowing spread -- unselected type`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { i:I }
                        interface I { x:Int }
                        type Foo implements I { x:Int }
                        type Bar implements I { x:Int }
                    """
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    schema.schema.queryType,
                    mapOf("i" to mapOf("__typename" to "Foo", "x" to 42)),
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Query",
                        "i { ... on Bar { x } }",
                        emptyMap(),
                        schema
                    )
                )

                val i = result.fetch(ObjectEngineResult.Key("i"), RAW_VALUE_SLOT)
                assertNotNull(i)
            }
        }

        @Test
        fun `newFromMap with a nested narrowing spread -- abstract selections only`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { u:U }
                        union U = Foo
                        type Foo { x:Int }
                    """
                )

                val result = ObjectEngineResultTestHelper.newFromMap(
                    schema.schema.queryType,
                    mapOf("u" to mapOf("__typename" to "Foo", "x" to 42)),
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Query",
                        "u { __typename }",
                        emptyMap(),
                        schema
                    )
                )

                val u = result.fetch(ObjectEngineResult.Key("u"), RAW_VALUE_SLOT)
                u as ObjectEngineResultImpl
                assertEquals("Foo", u.fetch(ObjectEngineResult.Key("__typename"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `newFromMap with an abstract abstract spread`() {
            runBlocking {
                val schema = createSchema(
                    """
                        type Query { foo: Foo }
                        type Foo { x:Int }
                        union U1 = Foo
                        union U2 = Foo
                    """
                )
                val fooType = schema.schema.getObjectType("Foo")
                val data = mapOf("x" to 42)

                val result = ObjectEngineResultTestHelper.newFromMap(
                    fooType,
                    data,
                    emptyList<Pair<String, Throwable>>().toMutableList(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet(
                        "Foo",
                        """
                            ... on U2 {
                                ... on Foo { x }
                            }
                        """.trimIndent(),
                        emptyMap(),
                        schema
                    )
                )

                // Test successful fields
                assertEquals(42, result.fetch(ObjectEngineResult.Key("x"), RAW_VALUE_SLOT))
            }
        }

        @Test
        fun `regression -- newFromMap ignores unselected data`() {
            runBlocking {
                val schema = createSchema("type Query { x:Int }")

                val data = mapOf("__typename" to "Query", "x" to 1)

                val result = ObjectEngineResultTestHelper.newFromMap(
                    schema.schema.queryType,
                    data,
                    mutableListOf(),
                    emptyList(),
                    schema,
                    createEngineSelectionSet("Query", "x", emptyMap(), schema)
                )

                // Test the selected field
                assertEquals(1, result.fetch(ObjectEngineResult.Key("x"), RAW_VALUE_SLOT))

                // Assert that the unselected field was not stored in the OER.
                // We can do this by trying to compute a value for the key we suspect this would have been populated under
                var computeRan = false
                runCatching {
                    result.computeIfAbsent(ObjectEngineResult.Key("__typename")) { computeRan = true }
                }
                assertTrue(computeRan)
            }
        }
    }
}
