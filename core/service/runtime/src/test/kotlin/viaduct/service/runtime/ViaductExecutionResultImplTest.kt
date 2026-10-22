package viaduct.service.runtime

import graphql.ErrorType
import graphql.ExecutionResult as GJExecutionResult
import graphql.ExecutionResultImpl as GJExecutionResultImpl
import graphql.GraphQLError as GJGraphQLError
import graphql.execution.ResultPath
import graphql.incremental.IncrementalExecutionResultImpl
import graphql.language.SourceLocation
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CompletedEntry
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.PendingEntry
import viaduct.engine.runtime.execution.IncrementalExecutionResultImpl as EngineIncrementalExecutionResultImpl
import viaduct.engine.runtime.execution.UpdateExecutionResultImpl
import viaduct.service.api.ExecutionResult
import viaduct.service.api.IncrementalEntry as ServiceIncrementalEntry

class ViaductExecutionResultImplTest {
    private val path = ResultPath.rootPath().segment("users").segment(0)

    private val error = GJGraphQLError.newError()
        .message("Test error")
        .path(listOf("users", 0, "name"))
        .location(SourceLocation(2, 3, "query.graphql"))
        .errorType(ErrorType.ValidationError)
        .extensions(mapOf("code" to "TEST"))
        .build()

    private val errorSpecification = mapOf(
        "message" to "Test error",
        "path" to listOf("users", 0, "name"),
        "locations" to listOf(mapOf("line" to 2, "column" to 3)),
        "extensions" to mapOf("code" to "TEST", "classification" to "ValidationError"),
    )

    @Test
    fun `standard execution rejects and cancels an unexpected incremental result`() {
        var cancelled = false
        val result = object : IncrementalExecutionResult by mkIncrementalExecutionResult(emptyMap(), emptyList(), emptyList(), true) {
            override fun cancel() {
                cancelled = true
            }
        }
        assertThrows<IllegalStateException> { result.toStandardExecutionResult() }
        assertTrue(cancelled)
    }

    @Test
    fun `converts standard results with data errors and extensions`() {
        val result = mkExecutionResult(
            data = mapOf("users" to listOf(mapOf("name" to null))),
            errors = listOf(error),
            extensions = mapOf("trace" to "123"),
        ).toExecutionResult()

        val sync = assertInstanceOf(ExecutionResult.Standard::class.java, result)
        assertEquals(mapOf("users" to listOf(mapOf("name" to null))), sync.getData())
        assertEquals(true, sync.isDataPresent)
        assertEquals(mapOf("trace" to "123"), sync.extensions)
        assertEquals(error.message, sync.errors.single().message)
        assertEquals(error.path, sync.errors.single().path)
        assertEquals("query.graphql", sync.errors.single().locations!!.single().sourceName)
        assertEquals(mapOf("code" to "TEST"), sync.errors.single().extensions)
        assertEquals(
            mapOf(
                "data" to sync.getData(),
                "errors" to listOf(errorSpecification),
                "extensions" to mapOf("trace" to "123"),
            ),
            result.toSpecification(),
        )
    }

    @Test
    fun `distinguishes absent data from explicit null for both adapters`() {
        val absent = mkExecutionResult(null, listOf(error), isDataPresent = false)
        val explicitNull = mkExecutionResult(null, listOf(error))
        val gjAbsent = GJExecutionResultImpl.newExecutionResult().addError(error).build()
        val gjNull = GJExecutionResultImpl.newExecutionResult().data(null).addError(error).build()

        assertEquals(mapOf("errors" to listOf(errorSpecification)), absent.toExecutionResult().toSpecification())
        assertEquals(absent.toExecutionResult().toSpecification(), gjAbsent.toExecutionResult().toSpecification())
        assertEquals(
            mapOf("data" to null, "errors" to listOf(errorSpecification)),
            explicitNull.toExecutionResult().toSpecification(),
        )
        assertEquals(explicitNull.toExecutionResult().toSpecification(), gjNull.toExecutionResult().toSpecification())
    }

    @Test
    fun `converts initial results with pending work`() {
        val result = mkIncrementalExecutionResult(
            data = mapOf("users" to listOf(emptyMap<String, Any?>())),
            errors = listOf(error),
            pending = listOf(PendingEntry("0", path, "details"), PendingEntry("1", ResultPath.rootPath(), null)),
            hasNext = true,
            extensions = mapOf("trace" to "123"),
        ).toExecutionResult()

        val initial = assertInstanceOf(ExecutionResult.Initial::class.java, result)
        assertEquals(mapOf("users" to listOf(emptyMap<String, Any?>())), initial.getData())
        assertEquals(true, initial.hasNext)
        assertEquals(mapOf("trace" to "123"), initial.extensions)
        assertEquals(error.message, initial.errors.single().message)
        assertEquals(listOf("0", "1"), initial.pending.map { it.id })
        assertEquals(listOf(listOf("users", 0), emptyList<Any>()), initial.pending.map { it.path })
        assertEquals(listOf("details", null), initial.pending.map { it.label })
        assertEquals(
            listOf(
                mapOf("id" to "0", "path" to listOf("users", 0), "label" to "details"),
                mapOf("id" to "1", "path" to emptyList<Any>()),
            ),
            initial.pending.map { it.toSpecification() },
        )
        assertEquals(
            mapOf(
                "data" to mapOf("users" to listOf(emptyMap<String, Any?>())),
                "errors" to listOf(errorSpecification),
                "pending" to listOf(
                    mapOf("id" to "0", "path" to listOf("users", 0), "label" to "details"),
                    mapOf("id" to "1", "path" to emptyList<Any>()),
                ),
                "hasNext" to true,
                "extensions" to mapOf("trace" to "123"),
            ),
            result.toSpecification(),
        )
    }

    @Test
    fun `converts updates with incremental pending and completed entries`() {
        val result = mkExecutionUpdate(
            hasNext = true,
            incremental = listOf(IncrementalEntry.Object("0", mapOf("name" to null), listOf(error), ResultPath.rootPath().segment("profile"))),
            completed = listOf(CompletedEntry("0", emptyList()), CompletedEntry("1", listOf(error))),
            pending = listOf(PendingEntry("2", path.segment("profile"), "")),
        ).toExecutionResult()

        val update = assertInstanceOf(ExecutionResult.Update::class.java, result)
        assertEquals(true, update.hasNext)
        val entry = assertInstanceOf(ServiceIncrementalEntry.Object::class.java, update.incremental.single())
        assertEquals("0", entry.id)
        assertEquals(mapOf("name" to null), entry.data)
        assertEquals(listOf("profile"), entry.subPath)
        assertEquals(error.message, entry.errors.single().message)
        assertEquals(listOf("0", "1"), update.completed.map { it.id })
        assertEquals(emptyList<Any>(), update.completed.first().errors)
        assertEquals(error.message, update.completed.last().errors.single().message)
        assertEquals("2", update.pending.single().id)
        assertEquals(listOf("users", 0, "profile"), update.pending.single().path)
        assertEquals("", update.pending.single().label)
        assertEquals(
            mapOf(
                "hasNext" to true,
                "incremental" to listOf(
                    mapOf("id" to "0", "data" to mapOf("name" to null), "errors" to listOf(errorSpecification), "subPath" to listOf("profile")),
                ),
                "completed" to listOf(mapOf("id" to "0"), mapOf("id" to "1", "errors" to listOf(errorSpecification))),
                "pending" to listOf(mapOf("id" to "2", "path" to listOf("users", 0, "profile"), "label" to "")),
            ),
            result.toSpecification(),
        )
    }

    @Test
    fun `omits optional empty entries and preserves empty data`() {
        val result = mkExecutionUpdate(
            hasNext = false,
            incremental = listOf(IncrementalEntry.Object("0", emptyMap(), emptyList(), null)),
            completed = listOf(CompletedEntry("0", emptyList())),
            pending = emptyList(),
        ).toExecutionResult()

        assertEquals(
            mapOf(
                "hasNext" to false,
                "incremental" to listOf(mapOf("id" to "0", "data" to emptyMap<String, Any?>())),
                "completed" to listOf(mapOf("id" to "0")),
            ),
            result.toSpecification(),
        )
        assertEquals(
            mapOf("hasNext" to false),
            mkExecutionUpdate(false, emptyList(), emptyList(), emptyList()).toExecutionResult().toSpecification(),
        )
        assertEquals(
            mapOf("data" to emptyMap<String, Any?>()),
            mkExecutionResult(emptyMap(), emptyList()).toExecutionResult().toSpecification(),
        )
    }

    @Test
    fun `preserves engine error and entry order in properties and serialization`() {
        val a = GJGraphQLError.newError().message("a").path(listOf("x")).build()
        val b = GJGraphQLError.newError().message("b").path(listOf("x")).build()
        val z = GJGraphQLError.newError().message("a").path(listOf("z")).build()
        val errors = listOf(z, b, a)
        val expected = errors.map { it.toSpecification() }
        val expectedPaths = errors.map { it.path }
        val expectedMessages = errors.map { it.message }

        val sync = mkExecutionResult(null, errors).toStandardExecutionResult()
        val initial = mkIncrementalExecutionResult(null, errors, emptyList(), false).toExecutionResult()
        val legacy = GJExecutionResultImpl.newExecutionResult().errors(errors).build().toStandardExecutionResult()

        assertEquals(expected, sync.toSpecification()["errors"])
        assertEquals(expected, initial.toSpecification()["errors"])
        assertEquals(expected, legacy.toSpecification()["errors"])

        val result = mkExecutionUpdate(
            false,
            listOf(IncrementalEntry.Object("1", emptyMap(), errors, ResultPath.rootPath())),
            listOf(CompletedEntry("1", errors), CompletedEntry("0", emptyList())),
            emptyList(),
        ).toExecutionResult()

        for (adapted in listOf(sync.errors, initial.errors, legacy.errors, result.incremental.single().errors, result.completed.first().errors)) {
            assertEquals(expectedPaths, adapted.map { it.path })
            assertEquals(expectedMessages, adapted.map { it.message })
        }

        assertEquals(expected, result.incremental.single().toSpecification()["errors"])
        assertEquals(emptyList<Any>(), assertInstanceOf(ServiceIncrementalEntry.Object::class.java, result.incremental.single()).subPath)
        assertEquals(expected, result.completed.first().toSpecification()["errors"])
        assertEquals(listOf("1", "0"), result.completed.map { it.id })
    }

    @Test
    fun `delegates custom error serialization without normalization`() {
        val specification = errorSpecification + mapOf("extensions" to emptyMap<String, Any?>(), "custom" to "preserved")
        val customError = object : GJGraphQLError by error {
            override fun getExtensions(): Map<String, Any?> = mapOf("code" to "TEST")

            override fun toSpecification(): Map<String, Any?> = specification
        }
        val errors = listOf(customError)
        val sync = mkExecutionResult(null, errors)
        val initial = mkIncrementalExecutionResult(null, errors, listOf(PendingEntry("0", path, null)), true)
        val incremental = IncrementalEntry.Object("0", emptyMap(), errors, null)
        val completed = CompletedEntry("0", errors)
        val update = mkExecutionUpdate(false, listOf(incremental), listOf(completed), emptyList())

        assertEquals(mapOf("code" to "TEST"), sync.toStandardExecutionResult().errors.single().extensions)
        assertEquals(mapOf("code" to "TEST"), initial.toExecutionResult().errors.single().extensions)
        assertEquals(sync.toSpecification(), sync.toExecutionResult().toSpecification())
        assertEquals(initial.toSpecification(), initial.toExecutionResult().toSpecification())
        assertEquals(update.toSpecification(), update.toExecutionResult().toSpecification())
        val adaptedUpdate = update.toExecutionResult()
        assertEquals(incremental.toSpecification(), adaptedUpdate.incremental.single().toSpecification())
        assertEquals(completed.toSpecification(), adaptedUpdate.completed.single().toSpecification())
        assertEquals(mapOf("code" to "TEST"), adaptedUpdate.incremental.single().errors.single().extensions)
        assertEquals(mapOf("code" to "TEST"), adaptedUpdate.completed.single().errors.single().extensions)
        assertEquals(
            listOf(specification),
            GJExecutionResultImpl.newExecutionResult().errors(errors).build().toExecutionResult().toSpecification()["errors"],
        )
    }

    @Test
    fun `legacy adapter retains non-map data and extensions without rewriting serialization`() {
        val result = GJExecutionResultImpl.newExecutionResult()
            .data(listOf("value"))
            .extensions(mapOf("trace" to "123"))
            .build()
            .toStandardExecutionResult()

        assertEquals(mapOf("data" to listOf("value")), result.getData())
        assertEquals(true, result.isDataPresent)
        assertEquals(mapOf("trace" to "123"), result.extensions)
        assertEquals(
            mapOf("data" to listOf("value"), "extensions" to mapOf("trace" to "123")),
            result.toSpecification(),
        )
    }

    @Test
    fun `legacy adapter rejects graphql-java incremental results`() {
        val result = IncrementalExecutionResultImpl.newIncrementalExecutionResult().build()

        assertThrows<IllegalArgumentException> { result.toExecutionResult() }
    }

    private fun mkExecutionResult(
        data: Map<String, Any?>?,
        errors: List<GJGraphQLError> = emptyList(),
        extensions: Map<Any, Any?>? = null,
        isDataPresent: Boolean = true,
    ): GJExecutionResult =
        GJExecutionResult.newExecutionResult()
            .apply { if (isDataPresent) data(data) }
            .errors(errors)
            .extensions(extensions)
            .build()

    private fun mkIncrementalExecutionResult(
        data: Map<String, Any?>?,
        errors: List<GJGraphQLError>,
        pending: List<PendingEntry>,
        hasNext: Boolean,
        extensions: Map<Any, Any?>? = null,
    ): IncrementalExecutionResult = EngineIncrementalExecutionResultImpl(mkExecutionResult(data, errors, extensions), pending, hasNext, emptyFlow())

    private fun mkExecutionUpdate(
        hasNext: Boolean,
        incremental: List<IncrementalEntry>,
        completed: List<CompletedEntry>,
        pending: List<PendingEntry>,
    ): IncrementalExecutionResult.Update = UpdateExecutionResultImpl(hasNext, incremental, completed, pending)
}
