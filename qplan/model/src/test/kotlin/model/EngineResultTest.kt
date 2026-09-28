@file:Suppress("ForbiddenImport")

package model

import viaduct.graphql.schema.ViaductSchema

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineResultTest {
    @Test
    fun `error carriers use reference equality and preserve output error identity`() {
        val cause = IllegalStateException("source failure")
        val firstErrorData = EngineErrorData.of()
        val secondErrorData = EngineErrorData.of()
        val causedErrorData = EngineErrorData.of(cause)
        val firstResult = ErrorEngineResult.of(firstErrorData)
        val secondResultForSameData = ErrorEngineResult.of(firstErrorData)

        assertNull(firstErrorData.cause)
        assertSame(cause, causedErrorData.cause)
        assertNotSame(firstErrorData, secondErrorData)
        assertNotEquals(firstErrorData, secondErrorData)
        assertSame(firstErrorData, firstResult.errorData)
        assertNotSame(firstResult, secondResultForSameData)
        assertNotEquals(firstResult, secondResultForSameData)
    }

    @Test
    fun `fixture DSL constructs nested argument-bearing results`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val result =
            world.engineResultOf("User") {
                field("lookup", "limit" to 1) resolvesTo "one"
                "aliases" resolvesTo listOf("A", null)
                "friend" resolvesTo
                    engineResultOf("User") {
                        "first" resolvesTo "Grace"
                    }
            }

        assertEquals(
            "one",
            result
                .getCell(schema.key("User", "lookup", "limit" to 1))
                .getValue()
                .get(),
        )
        val aliases =
            assertIs<ListEngineResult>(
                result.getCell(schema.key("User", "aliases")).getValue().get(),
            )
        assertEquals(
            listOf("A", null),
            aliases.map { cell -> cell.getValue().get() },
        )
        val friend =
            assertIs<ObjectEngineResult>(
                result.getCell(schema.key("User", "friend")).getValue().get(),
            )
        assertEquals(
            "Grace",
            friend.getCell(schema.key("User", "first")).getValue().get(),
        )
    }

    @Test
    fun `object type checker result is independently monotonic`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)

        assertFalse(result.isTypeCheckerResultSet())
        assertFailsWith<IllegalStateException> { result.getTypeCheckerResult() }

        result.setTypeCheckerResult(null)

        assertTrue(result.isTypeCheckerResultSet())
        assertNull(result.getTypeCheckerResult().get())
        assertTrue(result.isCompleted)
        assertFailsWith<IllegalStateException> {
            result.setTypeCheckerResult(CheckerResult.Success)
        }
    }

    @Test
    fun `object type checker promise supports deferred completion failure and cancellation`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val successful = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val successPromise = successful.createTypeCheckerResultPromise()

        assertFalse(successful.isCompleted)
        assertTrue(successPromise.complete(CheckerResult.Success))
        assertSame(CheckerResult.Success, successful.getTypeCheckerResult().get())
        assertTrue(successful.isCompleted)

        val failed = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val failure = IllegalStateException("checker failed")
        failed.createTypeCheckerResultPromise()
        assertTrue(failed.failTypeCheckerResult(failure))
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { failed.getTypeCheckerResult().get() },
        )
        assertFalse(failed.failTypeCheckerResult(IllegalStateException("late")))

        val cancelled = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val cancellation = CancellationException("checker cancelled")
        cancelled.createTypeCheckerResultPromise()
        assertTrue(cancelled.cancelTypeCheckerResult(cancellation))
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { cancelled.getTypeCheckerResult().get() },
        )
        assertFalse(cancelled.cancelTypeCheckerResult(cancellation))
    }

    @Test
    fun `completed object comparison and union include the object type checker result`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val query = schema.requireQueryTypeDef()
        val absent = ObjectEngineResult.of(query)
        val noChecker = ObjectEngineResult.of(type = query, typeCheckerResult = null)
        val denial = TestCheckerError()
        val denied = ObjectEngineResult.of(type = query, typeCheckerResult = denial)

        assertFalse(absent.sameCompletedResultAs(noChecker))
        assertFalse(noChecker.sameCompletedResultAs(denied))

        val union = absent.union(denied)
        assertSame(denial, union.getTypeCheckerResult().get())
        assertFalse(absent.isTypeCheckerResultSet())
        assertFailsWith<IllegalArgumentException> {
            denied.union(ObjectEngineResult.of(type = query, typeCheckerResult = TestCheckerError()))
        }
    }

    @Test
    fun `list engine result retains its elements`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val elementType = schema.requireField("Query", "value").outputType
        val result = world.listResultOf(elementType, "one", null)

        assertEquals(
            listOf("one", null),
            result.map { cell -> cell.getValue().get() },
        )
        result.forEach { cell ->
            assertNull(cell.getFieldCheckerResult().get())
        }
        assertEquals(elementType, result.typeExpr)
    }

    @Test
    fun `typed empty list retains its intended element type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.requireField("Query", "value").outputType

        val result = ListEngineResult.of(elementType, emptyList())

        assertEquals(elementType, result.typeExpr)
        assertEquals(0, result.size)
    }

    @Test
    fun `pending list creates fixed activated cells with writable uncompleted values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.requireField("Query", "required").outputType

        val result = ListEngineResult.ofPendingValues(elementType, size = 2)

        assertEquals(elementType, result.typeExpr)
        assertEquals(2, result.size)
        result.forEach { cell ->
            cell.checkActivated()
            assertFailsWith<IllegalStateException> { cell.getFieldCheckerResult() }
            assertFalse(cell.getValue().isCompleted)
        }

        val firstPlaceholder = result[0].getValue()
        val firstWriter = result[0].createValuePromise()
        assertSame(firstPlaceholder, firstWriter)
        assertTrue(firstWriter.complete("first"))
        assertTrue(result[1].setValue("second"))

        assertEquals(listOf("first", "second"), result.map { cell -> cell.getValue().get() })
        assertFailsWith<IllegalStateException> { result[0].createValuePromise() }
        assertFailsWith<IllegalStateException> { result[1].setValue("again") }
    }

    @Test
    fun `pending list validates size and eventual element values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.requireField("Query", "required").outputType

        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.ofPendingValues(elementType, size = -1)
        }

        val result = ListEngineResult.ofPendingValues(elementType, size = 1)
        val writer = result.single().createValuePromise()

        assertFailsWith<IllegalArgumentException> { writer.complete(null) }
        assertFalse(writer.isCompleted)
        writer.complete("valid")
        assertEquals("valid", writer.get())
    }

    @Test
    fun `object result factory rejects values that violate field typing`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Query", "required"),
                emptyMap(),
            )
        assertFailsWith<IllegalArgumentException> {
            ObjectEngineResult.of(schema.requireQueryTypeDef(), mapOf(key to null))
        }
    }

    @Test
    fun `strict read does not reserve a missing mutable value`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)

        assertFailsWith<NoSuchElementException> { result.getCell(key) }
        assertFalse(result.isCellSet(key))
    }

    @Test
    fun `mutable object publishes each value once`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val firstValue = "first"
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap(), mutable = true)

        assertFalse(result.isCellSet(key))
        val cell = result.reserveCell(key)
        val readerPlaceholder = cell.reserveValue()
        assertFalse(readerPlaceholder.isCompleted)

        val writerPromise = cell.createValuePromise()
        assertSame(readerPlaceholder, writerPromise)
        cell.setActivated(true)
        writerPromise.complete(firstValue)

        assertTrue(result.isCellSet(key))
        assertSame(firstValue, result.getCell(key).getValue().get())
        assertFalse(cell.cancelValue(CancellationException("late")))
        assertEquals(setOf(key), result.keys)

        assertFailsWith<IllegalStateException> {
            cell.setValue("second")
        }
        assertSame(firstValue, result.getCell(key).getValue().get())
    }

    @Test
    fun `cancelling a claimed cell value activates and terminates its promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val cell = result.reserveCell(key)
        val promise = cell.createValuePromise()
        val cancellation = CancellationException("writer cancelled")

        assertTrue(cell.cancelValue(cancellation))

        cell.checkActivated()
        assertTrue(promise.isCompleted)
        assertSame(cancellation, assertFailsWith<CancellationException> { promise.get() })
        assertFalse(cell.cancelValue(cancellation))
        assertFalse(promise.complete("late"))
    }

    @Test
    fun `cancelling a claimed field checker result activates and terminates its promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val cell =
            ObjectEngineResult
                .of(schema.requireQueryTypeDef(), mutable = true)
                .reserveCell(schema.key("Query", "first"))
        val fieldPromise = cell.createFieldCheckerResultPromise()
        val cancellation = CancellationException("checker writer cancelled")

        assertTrue(cell.cancelFieldCheckerResult(cancellation))

        cell.checkActivated()
        assertSame(cancellation, assertFailsWith<CancellationException> { fieldPromise.get() })
        assertFalse(cell.cancelFieldCheckerResult(cancellation))
    }

    @Test
    fun `value await suspends until the cell is activated`() =
        runBlocking {
            val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
            val key = schema.key("Query", "first")
            val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
            val cell = result.reserveCell(key)
            val promise = cell.createValuePromise()
            val awaiting = async { promise.await() }

            assertFalse(awaiting.isCompleted)
            cell.setActivated(true)
            assertFalse(awaiting.isCompleted)
            promise.complete("ready")

            assertEquals("ready", awaiting.await())
        }

    @Test
    fun `not activated cell rejects every slot operation`() =
        runBlocking {
            val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
            val key = schema.key("Query", "first")
            val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
            val cell = result.reserveCell(key)
            val promise = cell.createValuePromise()
            val activation = async { cell.fetchActivated() }

            assertFalse(activation.isCompleted)

            assertTrue(cell.setActivated(false))

            assertFalse(activation.await())
            assertFailsWith<IllegalStateException> { cell.checkActivated() }
            assertFailsWith<IllegalStateException> { promise.get() }
            assertFailsWith<IllegalStateException> { promise.complete("excluded") }
            assertFailsWith<IllegalStateException> { promise.await() }
            assertFailsWith<IllegalStateException> { cell.setValue("excluded") }
            assertFalse(cell.setActivated(true))
        }

    @Test
    fun `direct cell value installation is activated and completed from birth`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)

        val cell = result.setCellValue(key, "ready")

        cell.checkActivated()
        assertEquals("ready", cell.getValue().get())
        assertFailsWith<IllegalStateException> { cell.setValue("again") }
    }

    @Test
    fun `completed-result comparison treats a not-activated cell as absent`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val withInactiveCell =
            ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        withInactiveCell.reserveCell(key).also { cell ->
            cell.createValuePromise()
            cell.setActivated(false)
        }
        withInactiveCell.freeze()
        val withoutCell = ObjectEngineResult.of(schema.requireQueryTypeDef())

        assertTrue(withInactiveCell.sameCompletedResultAs(withoutCell))
    }

    @Test
    fun `direct cell value installation completes an existing reader placeholder`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val reservedCell = result.reserveCell(key)
        val readerPlaceholder = reservedCell.reserveValue()

        val installedCell = result.setCellValue(key, "ready")

        assertSame(reservedCell, installedCell)
        assertEquals("ready", readerPlaceholder.get())
    }

    @Test
    fun `object keys reuse stable snapshots between reservations`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)

        val emptyKeys = result.keys
        assertSame(emptyKeys, result.keys)

        result.reserveCell(firstKey)
        val firstKeys = result.keys
        assertTrue(emptyKeys.isEmpty())
        assertEquals(setOf(firstKey), firstKeys)
        assertSame(firstKeys, result.keys)

        result.reserveCell(secondKey)
        assertEquals(setOf(firstKey), firstKeys)
        assertEquals(setOf(firstKey, secondKey), result.keys)
    }

    @Test
    fun `freeze fails unclaimed reader placeholders and rejects new values`() =
        runBlocking {
            val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
            val firstKey = schema.key("Query", "first")
            val secondKey = schema.key("Query", "second")
            val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
            val firstCell = result.reserveCell(firstKey)
            val missing = firstCell.reserveValue()
            val awaitingMissing = async { missing.await() }

            result.freeze()

            assertFailsWith<NoSuchElementException> { awaitingMissing.await() }
            assertFailsWith<NoSuchElementException> { result.reserveCell(secondKey) }
            assertFailsWith<IllegalStateException> {
                firstCell.createValuePromise()
            }
            assertFailsWith<IllegalStateException> {
                firstCell.setValue("late")
            }
            assertFailsWith<IllegalStateException> { result.freeze() }
        }

    @Test
    fun `claimed value promise may complete after freeze`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val cell = result.reserveCell(key)
        val readerPlaceholder = cell.reserveValue()
        val writerPromise = cell.createValuePromise()

        result.freeze()
        cell.setActivated(true)
        writerPromise.complete("ready")

        assertSame(readerPlaceholder, writerPromise)
        assertEquals("ready", cell.getValue().get())
    }

    @Test
    fun `concurrent reader and writer share one value promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val start = CountDownLatch(1)
        val promises = ConcurrentLinkedQueue<Promise<EngineResult?>>()
        val reader =
            thread {
                start.await()
                promises += result.reserveCell(key).reserveValue()
            }
        val writer =
            thread {
                start.await()
                promises += result.reserveCell(key).createValuePromise()
            }

        start.countDown()
        reader.join()
        writer.join()

        assertEquals(2, promises.size)
        assertSame(promises.first(), promises.last())
    }

    @Test
    fun `cell value and field checker result are independently monotonic`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result =
            ObjectEngineResult.of(type = schema.requireQueryTypeDef(), mutable = true)

        assertFalse(result.isCellSet(key))
        val cell = result.reserveCell(key)
        assertFailsWith<IllegalStateException> { cell.getValue() }
        assertFailsWith<IllegalStateException> { cell.getFieldCheckerResult() }
        assertFalse(cell.isFieldCheckerResultSet())

        val value = cell.createValuePromise()
        val fieldCheckerResult = cell.createFieldCheckerResultPromise()
        cell.setActivated(true)
        assertTrue(cell.isFieldCheckerResultSet())

        assertFailsWith<UncompletedPromiseException> { value.get() }
        assertFailsWith<UncompletedPromiseException> { fieldCheckerResult.get() }

        value.complete("ready")
        fieldCheckerResult.complete(CheckerResult.Success)

        assertEquals("ready", cell.getValue().get())
        assertSame(CheckerResult.Success, cell.getFieldCheckerResult().get())
        assertFailsWith<IllegalStateException> {
            cell.setFieldCheckerResult(null)
        }
    }

    @Test
    fun `checker result slots use the production result type and nullable absence`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val completedError = TestCheckerError()
        val completed =
            ObjectEngineResult.of(
                type = schema.requireQueryTypeDef(),
                values = mapOf(firstKey to "ready"),
                fieldCheckerResults = mapOf(firstKey to completedError),
            )

        assertSame(completedError, completed.getCell(firstKey).getFieldCheckerResult().get())

        val mutable = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val direct = mutable.reserveCell(firstKey)
        direct.setFieldCheckerResult(completedError)
        assertSame(completedError, direct.getFieldCheckerResult().get())

        val deferred = mutable.reserveCell(secondKey).createFieldCheckerResultPromise()
        mutable.getCell(secondKey).setActivated(true)
        deferred.complete(CheckerResult.Success)
        assertSame(CheckerResult.Success, deferred.get())
    }

    @Test
    fun `claimed field checker result can complete exceptionally`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val cell =
            ObjectEngineResult
                .of(schema.requireQueryTypeDef(), mutable = true)
                .reserveCell(schema.key("Query", "first"))
        cell.createFieldCheckerResultPromise()
        cell.setActivated(true)
        val failure = IllegalStateException("checker failed")

        assertTrue(cell.failFieldCheckerResult(failure))
        assertSame(failure, assertFailsWith<IllegalStateException> { cell.getFieldCheckerResult().get() })
        assertFalse(cell.failFieldCheckerResult(IllegalStateException("second failure")))
    }

    @Test
    fun `unpublished checker slots differ from completed no-checker results`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val unpublished = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        unpublished.setCellValue(key, "ready")
        unpublished.freeze()
        val noChecker =
            ObjectEngineResult.of(
                type = schema.requireQueryTypeDef(),
                values = mapOf(key to "ready"),
            )

        assertFalse(unpublished.sameCompletedResultAs(noChecker))

        val union = unpublished.union(noChecker)
        assertNull(union.getCell(key).getFieldCheckerResult().get())
    }

    @Test
    fun `immutable object and union results reject writes`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val requiredKey = schema.key("Query", "required")
        val immutable =
            ObjectEngineResult.of(
                schema.requireQueryTypeDef(),
                mapOf(firstKey to "existing"),
            )

        assertFailsWith<IllegalStateException> {
            immutable.getCell(firstKey).setValue("first")
        }
        val fixture =
            schema.engineResultOf("Query") {
                "first" resolvesTo "existing"
            }
        assertFailsWith<IllegalStateException> {
            fixture.getCell(firstKey).setValue("first")
        }

        val left =
            ObjectEngineResult.of(
                schema.requireQueryTypeDef(),
                mapOf(firstKey to "first"),
                mutable = true,
            )
        val right =
            ObjectEngineResult.of(
                schema.requireQueryTypeDef(),
                mapOf(secondKey to "second"),
            )
        val union = left.union(right)

        assertFailsWith<NoSuchElementException> {
            union.reserveCell(requiredKey)
        }
    }

    @Test
    fun `mutable object rejects invalid values before publication`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap(), mutable = true)
        val foreignKey = schema.key("User", "first")
        val requiredKey = schema.key("Query", "required")
        val lookupWithError =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("User", "lookup"),
                mapOf("limit" to ArgumentResolutionError),
            )
        val user =
            ObjectEngineResult.of(
                schema.requireObjectField("User", "first").containingDef,
                emptyMap(),
                mutable = true,
            )

        assertFailsWith<IllegalArgumentException> {
            result.reserveCell(foreignKey).setValue("wrong owner")
        }
        assertFalse(result.isCellSet(foreignKey))

        assertFailsWith<IllegalArgumentException> {
            result.reserveCell(requiredKey).setValue(null)
        }
        assertTrue(result.isCellSet(requiredKey))

        assertFailsWith<IllegalArgumentException> {
            user.reserveCell(lookupWithError).setValue("not an error")
        }
        assertTrue(user.isCellSet(lookupWithError))

        val errorResult = ErrorEngineResult.of(EngineErrorData.of())
        user.getCell(lookupWithError).setValue(errorResult)
        assertSame(errorResult, user.getCell(lookupWithError).getValue().get())
    }

    @Test
    fun `concurrent object writers produce one winner and one exception`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val key = schema.key("Query", "first")
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap(), mutable = true)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val writers =
            listOf("first", "second").map { value ->
                thread {
                    ready.countDown()
                    start.await()
                    try {
                        result.reserveCell(key).setValue(value)
                        successes.incrementAndGet()
                    } catch (throwable: Throwable) {
                        failures.add(throwable)
                    }
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        assertEquals(1, successes.get())
        assertIs<IllegalStateException>(failures.single())
        val value = assertIs<String>(result.getCell(key).getValue().get())
        assertTrue(value in setOf("first", "second"))
    }

    @Test
    fun `concurrent object writes to distinct keys are retained`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val result = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap(), mutable = true)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val writes =
            listOf(
                schema.key("Query", "first") to "first",
                schema.key("Query", "second") to "second",
            )
        val writers =
            writes.map { (key, value) ->
                thread {
                    ready.countDown()
                    start.await()
                    result.reserveCell(key).setValue(value)
                }
            }

        ready.await()
        start.countDown()
        writers.forEach(Thread::join)

        writes.forEach { (key, value) ->
            assertEquals(
                value,
                result.getCell(key).getValue().get(),
            )
        }
    }

    @Test
    fun `written parent value observes later writes to mutable child`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val userKey = schema.key("Query", "user")
        val firstKey = schema.key("User", "first")
        val child =
            ObjectEngineResult.of(
                schema.requireObjectField("User", "first").containingDef,
                emptyMap(),
                mutable = true,
            )
        val parent = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap(), mutable = true)

        parent.reserveCell(userKey).setValue(child)
        child.reserveCell(firstKey).setValue("later")

        val retainedChild =
            assertIs<ObjectEngineResult>(parent.getCell(userKey).getValue().get())
        assertEquals(
            "later",
            retainedChild.getCell(firstKey).getValue().get(),
        )
    }

    @Test
    fun `object equality is identity based and hashing is stable through mutation`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val firstKey = schema.key("Query", "first")
        val secondKey = schema.key("Query", "second")
        val firstValue = "first"
        val mutable = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        val equivalent =
            ObjectEngineResult.of(
                schema.requireQueryTypeDef(),
                mapOf(firstKey to firstValue),
            )

        mutable.reserveCell(firstKey).also { cell ->
            cell.setValue(firstValue)
            cell.setFieldCheckerResult(null)
        }

        assertNotEquals(equivalent, mutable)
        assertSame(mutable, mutable)
        val hashCode = mutable.hashCode()
        val keyed = hashMapOf(mutable to "retained")

        mutable.reserveCell(secondKey).also { cell ->
            cell.setValue("second")
            cell.setFieldCheckerResult(null)
        }

        assertEquals(hashCode, mutable.hashCode())
        assertEquals("retained", keyed[mutable])
    }

    @Test
    fun `list equality includes type expression and cell occurrence identity`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.requireField("Query", "user").outputType
        val shared =
            schema.engineResultOf("User") {
                "first" resolvesTo "same"
            }
        val equivalent =
            schema.engineResultOf("User") {
                "first" resolvesTo "same"
            }
        val list = ListEngineResult.of(elementType, listOf(shared))

        assertEquals(list, list)
        assertSame(list[0], list[0])
        assertNotEquals(list, ListEngineResult.of(elementType, listOf(shared)))
        assertNotEquals(
            list,
            ListEngineResult.of(elementType, listOf(equivalent)),
        )
        assertNotEquals(
            ListEngineResult.of(schema.requireField("Query", "value").outputType, emptyList()),
            ListEngineResult.of(schema.requireField("Query", "integer").outputType, emptyList()),
        )
    }

    @Test
    fun `completed result comparison is extensional over nested values and checks`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val left =
            schema.engineResultOf("Query") {
                "user" resolvesTo
                    engineResultOf("User") {
                        "first".resolvesTo("same", CheckerResult.Success)
                    }
            }
        val right =
            schema.engineResultOf("Query") {
                "user" resolvesTo
                    engineResultOf("User") {
                        "first".resolvesTo("same", CheckerResult.Success)
                    }
            }
        val differentCheck =
            schema.engineResultOf("Query") {
                "user" resolvesTo
                    engineResultOf("User") {
                        "first".resolvesTo("same", TestCheckerError())
                    }
            }
        val firstKey = schema.key("Query", "first")
        val leftError = ErrorEngineResult.of(EngineErrorData.of())
        val rightError = ErrorEngineResult.of(EngineErrorData.of())
        val leftCheckerError = TestCheckerError()
        val rightCheckerError = TestCheckerError()
        val leftErrorResult =
            ObjectEngineResult.of(
                type = schema.requireQueryTypeDef(),
                values = mapOf(firstKey to leftError),
                fieldCheckerResults = mapOf(firstKey to leftCheckerError),
            )
        val rightErrorResult =
            ObjectEngineResult.of(
                type = schema.requireQueryTypeDef(),
                values = mapOf(firstKey to rightError),
                fieldCheckerResults = mapOf(firstKey to rightCheckerError),
            )

        assertTrue(left.sameCompletedResultAs(right))
        assertFalse(left.sameCompletedResultAs(differentCheck))
        assertTrue(leftErrorResult.sameCompletedResultAs(rightErrorResult))
    }

    @Test
    fun `completed result comparison treats occurrence ids as root relative`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                  consume(value: Int): Int
                }
                """.trimIndent(),
            ).schema
        val query = schema.requireQueryTypeDef()
        val source = schema.requireObjectField("Query", "source")
        val consume = schema.requireObjectField("Query", "consume")

        fun result(occurrenceIndex: Int): ObjectEngineResult {
            val root = ObjectEngineResult.of(query, mutable = true)
            val variable =
                Arguments.Variable.of(source, "value").instantiate(
                    ResolverOccurrenceId.at(
                        root,
                        listOf(ListEngineResult.Index.of(occurrenceIndex)),
                    ),
                )
            val key =
                ObjectEngineResult.ObjectKey.of(
                    consume,
                    Arguments.of(consume, mapOf("value" to variable)),
                )
            root.reserveCell(key).apply {
                setValue(7)
                setFieldCheckerResult(null)
            }
            root.freeze()
            return root
        }

        val first = result(0)
        val equivalent = result(0)
        val differentOccurrence = result(1)

        assertNotEquals(first.keys, equivalent.keys)
        assertTrue(first.sameCompletedResultAs(equivalent))
        assertFalse(first.sameCompletedResultAs(differentOccurrence))
    }

    @Test
    fun `completed result comparison requires the exact cell set`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val selectedOnly =
            schema.engineResultOf("Query") {
                "first" resolvesTo "same"
            }
        val withExtraCell =
            schema.engineResultOf("Query") {
                "first" resolvesTo "same"
                "second" resolvesTo "extra"
            }

        assertFalse(selectedOnly.sameCompletedResultAs(withExtraCell))
    }

    @Test
    fun `completed result comparison distinguishes symbolic and grounded keys`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  source: Int
                  consume(value: Int): Int
                }
                """.trimIndent(),
            ).schema
        val query = schema.requireQueryTypeDef()
        val source = schema.requireObjectField("Query", "source")
        val consume = schema.requireObjectField("Query", "consume")
        val symbolicRoot = ObjectEngineResult.of(query, mutable = true)
        val variable =
            Arguments.Variable.of(source, "value").instantiate(
                ResolverOccurrenceId.at(symbolicRoot, emptyList()),
            )
        val symbolicKey =
            ObjectEngineResult.ObjectKey.of(
                consume,
                Arguments.of(consume, mapOf("value" to variable)),
            )
        symbolicRoot.reserveCell(symbolicKey).apply {
            setValue(7)
            setFieldCheckerResult(null)
        }
        symbolicRoot.freeze()
        val grounded =
            ObjectEngineResult.of(
                type = query,
                values =
                    mapOf(
                        ObjectEngineResult.GroundKey.of(
                            consume,
                            mapOf("value" to 7),
                        ) to 7,
                    ),
                fieldCheckerResults =
                    mapOf(
                        ObjectEngineResult.GroundKey.of(
                            consume,
                            mapOf("value" to 7),
                        ) to null,
                    ),
            )

        assertFalse(symbolicRoot.sameCompletedResultAs(grounded))
    }

    @Test
    fun `completed result comparison rejects an uncompleted promise`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val incomplete =
            ObjectEngineResult.of(type = schema.requireQueryTypeDef(), mutable = true)
        incomplete
            .reserveCell(schema.key("Query", "first"))
            .createValuePromise()

        assertFailsWith<UncompletedPromiseException> {
            incomplete.sameCompletedResultAs(incomplete)
        }
    }

    @Test
    fun `completed result comparison audits completion after an early inequality`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val incomplete = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
        incomplete
            .reserveCell(schema.key("Query", "first"))
            .createValuePromise()

        assertFailsWith<UncompletedPromiseException> {
            "different variant".sameCompletedResultAs(incomplete)
        }
    }

    @Test
    fun `list result factory rejects incompatible element values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.requireField("Query", "value").outputType

        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(elementType, listOf(1))
        }
    }

    @Test
    fun `simple resolver values convert to pre-domain engine results and back`() {
        val schema =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      status: Status
                      int: Int
                      float: Float
                      string: String
                      boolean: Boolean
                      id: ID
                    }

                    enum Status {
                      READY
                    }
                    """.trimIndent(),
                ).schema
        val status = schema.requireType("Status") as ViaductSchema.Enum
        val intType = schema.requireType("Int") as ViaductSchema.Scalar
        val floatType = schema.requireType("Float") as ViaductSchema.Scalar
        val stringType = schema.requireType("String") as ViaductSchema.Scalar
        val booleanType = schema.requireType("Boolean") as ViaductSchema.Scalar
        val idType = schema.requireType("ID") as ViaductSchema.Scalar
        val cases =
            listOf(
                Triple<EngineOutputData, EngineResult, ViaductSchema.SimpleTypeDef>(
                    1,
                    1,
                    intType,
                ),
                Triple(2.5, 2.5, floatType),
                Triple("three", "three", stringType),
                Triple(true, true, booleanType),
                Triple("four", EngineIDResult.of("four"), idType),
                Triple(
                    "READY",
                    status.requireValue("READY"),
                    status,
                ),
            )

        cases.forEach { (value, expectedResult, type) ->
            val result = value.toEngineResult(type)
            assertEquals(expectedResult, result)
            assertEquals(value, result.toEngineOutputData(type))
        }
        assertEquals(EngineIDResult.of("four"), EngineIDResult.of("four"))
        assertSame(status.requireValue("READY"), cases.last().second)
    }

    @Test
    fun `result factories enforce GraphQL scalar domains`() {
        val schema =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      status: Status
                      float: Float
                    }

                    enum Status {
                      READY
                    }

                    enum OtherStatus {
                      READY
                    }
                    """.trimIndent(),
                ).schema
        val status = schema.requireType("Status") as ViaductSchema.Enum
        val otherStatus = schema.requireType("OtherStatus") as ViaductSchema.Enum
        val floatType =
            ViaductSchema.TypeExpr(schema.requireType("Float") as ViaductSchema.Scalar)
        val statusType = ViaductSchema.TypeExpr(status)

        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(floatType, listOf(Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(floatType, listOf(Double.POSITIVE_INFINITY))
        }
        assertFailsWith<IllegalArgumentException> {
            ListEngineResult.of(
                statusType,
                listOf(otherStatus.requireValue("READY")),
            )
        }
    }

    @Test
    fun `equal simple engine results have a union`() {
        val left = "same"
        val right = "same"

        assertSame(left, left.union(right))
        assertSame(left, (left as EngineResult).union(right))
    }

    @Test
    fun `unequal simple engine results have no union`() {
        assertFailsWith<IllegalArgumentException> {
            "left".union("right")
        }
    }

    @Test
    fun `different engine result variants have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val simple: EngineResult = "value"
        val list: EngineResult =
            ListEngineResult.of(
                schema.requireField("Query", "value").outputType,
                emptyList(),
            )

        assertFailsWith<IllegalArgumentException> { simple.union(list) }
        assertFailsWith<IllegalArgumentException> { list.union(simple) }
    }

    @Test
    fun `nullable engine results union only matching nulls`() {
        val absent: EngineResult? = null
        val present: EngineResult = "value"

        assertNull(absent.union(null))
        assertFailsWith<IllegalArgumentException> { absent.union(present) }
        assertFailsWith<IllegalArgumentException> { present.union(absent) }
    }

    @Test
    fun `list engine results union corresponding cells`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val elementType = schema.requireField("Query", "value").outputType
        val left = schema.listResultOf(elementType, "same", null)
        val right = schema.listResultOf(elementType, "same", null)

        assertTrue(left.sameCompletedResultAs(left.union(right)))
    }

    @Test
    fun `list engine results with incompatible shapes have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val stringType = schema.requireField("Query", "value").outputType
        val intType = schema.requireField("Query", "integer").outputType
        val stringResult = schema.listResultOf(stringType, "value")

        assertFailsWith<IllegalArgumentException> {
            ListEngineResult
                .of(stringType, emptyList())
                .union(ListEngineResult.of(intType, emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            ListEngineResult
                .of(stringType, emptyList())
                .union(stringResult)
        }
        assertFailsWith<IllegalArgumentException> {
            schema.listResultOf(stringType, null).union(stringResult)
        }
        assertFailsWith<IllegalArgumentException> {
            schema
                .listResultOf(stringType, "left")
                .union(schema.listResultOf(stringType, "right"))
        }
    }

    @Test
    fun `object engine results union disjoint and recursively shared values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val leftUser =
            schema.engineResultOf("User") {
                "first" resolvesTo "first"
            }
        val rightUser =
            schema.engineResultOf("User") {
                "second" resolvesTo "second"
            }
        val left =
            schema.engineResultOf("Query") {
                "first" resolvesTo "first"
                "user" resolvesTo leftUser
            }
        val right =
            schema.engineResultOf("Query") {
                "second" resolvesTo "second"
                "user" resolvesTo rightUser
            }

        val union = left.union(right)

        assertEquals(
            setOf(
                schema.key("Query", "first"),
                schema.key("Query", "second"),
                schema.key("Query", "user"),
            ),
            union.keys,
        )
        assertEquals(
            "first",
            union.getCell(schema.key("Query", "first")).getValue().get(),
        )
        assertEquals(
            "second",
            union.getCell(schema.key("Query", "second")).getValue().get(),
        )
        val user =
            assertIs<ObjectEngineResult>(
                union.getCell(schema.key("Query", "user")).getValue().get(),
            )
        assertEquals(
            setOf(schema.key("User", "first"), schema.key("User", "second")),
            user.keys,
        )
    }

    @Test
    fun `object engine results with incompatible cells or types have no union`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema

        assertFailsWith<IllegalArgumentException> {
            schema
                .engineResultOf("Query") {
                    "first".resolvesTo("left", CheckerResult.Success)
                }.union(
                    schema.engineResultOf("Query") {
                        "first".resolvesTo("right", CheckerResult.Success)
                    },
                )
        }
        assertFailsWith<IllegalArgumentException> {
            schema
                .engineResultOf("Query") {
                    "first".resolvesTo("same", CheckerResult.Success)
                }.union(
                    schema.engineResultOf("Query") {
                        "first".resolvesTo("same", TestCheckerError())
                    },
                )
        }
        assertFailsWith<IllegalArgumentException> {
            schema.engineResultOf("Query").union(schema.engineResultOf("User"))
        }
    }

    private class TestCheckerError : CheckerResult.Error {
        override val error: Exception = SecurityException("denied")

        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
    }

    private fun ViaductSchema.key(
        typeName: String,
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(requireObjectField(typeName, fieldName), arguments.toMap())

    private companion object {
        const val SCHEMA_SDL =
            """
            type Query {
              value: String
              integer: Int
              required: String!
              first: String
              second: String
              user: User
            }

            type User {
              first: String
              second: String
              aliases: [String]
              friend: User
              lookup(limit: Int): String
            }
            """
    }
}
