package semantics.resolver26

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestScopeOwnershipTest {
    @Test
    fun `only orchestration field value and checker task roots can launch on the request scope`() {
        val sourceDirectory = Path.of("src/main/kotlin/semantics/resolver26")
        assertTrue(Files.isDirectory(sourceDirectory), "Resolver26 source directory is missing")
        val sources =
            Files.list(sourceDirectory).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".kt") }.toList()
            }

        val semanticsDirectory = sourceDirectory.parent
        val allSources = Files.walk(semanticsDirectory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(".kt") }.toList()
        }
        val rawRequestScopeLaunch = Regex("""requestScope\s*\.\s*(?:launch|async)\s*(?:\(|\{)""")
        assertEquals(
            listOf(
                "resolver26/CoroutineTaskDispatcher.kt",
                "resolver26/FieldCheckerTask.kt",
                "resolver26/FieldResolverTask.kt",
                "resolver26/TypeCheckerTask.kt",
                "resolvers/resolver21/CoroutineFieldCheckerTask.kt",
                "resolvers/resolver21/CoroutineFieldResolverTask.kt",
                "resolvers/resolver21/CoroutineTypeCheckerTask.kt",
            ),
            allSources.filter { source -> rawRequestScopeLaunch.containsMatchIn(source.readText()) }
                .map { semanticsDirectory.relativize(it).toString() }
                .sorted(),
        )

        val orchestrationDispatch = Regex("""\.\s*dispatchOrchestration\s*\(""")
        assertEquals(
            listOf("FieldResolverTask.kt", "Resolver.kt"),
            sources.filter { source ->
                orchestrationDispatch.containsMatchIn(source.readText())
            }.map(Path::name)
                .sorted(),
        )

        val fieldDispatch = Regex("""(?:\.\s*|::)dispatchFieldResolver(?:\s*\(|\b)""")
        assertEquals(
            listOf("FieldResolverTask.kt", "OrchestrationTask.kt"),
            sources.filter { source -> fieldDispatch.containsMatchIn(source.readText()) }
                .map(Path::name)
                .sorted(),
        )

        // List-element references may be discovered after their containing orchestration freezes.
        // Every ordinary or conditioned passive field publication dispatches from orchestration.
        val fieldResolverSource = sourceDirectory.resolve("FieldResolverTask.kt").readText()
        val beforeListHelper = fieldResolverSource.substringBefore("fun prepareAndDispatchListElement(")
        val afterListHelper = fieldResolverSource.substringAfter("fun prepareConditionedPassiveValue(")
        assertTrue(!fieldDispatch.containsMatchIn(beforeListHelper + afterListHelper))
        assertEquals(1, fieldDispatch.findAll(fieldResolverSource).count())

        val fieldCheckerDispatch = checkerDispatch("dispatchFieldChecker")
        assertEquals(
            listOf("resolver26/OrchestrationTask.kt", "resolvers/resolver21/CoroutineOrchestrationTask.kt"),
            allSources.filter { source -> fieldCheckerDispatch.containsMatchIn(source.readText()) }
                .map { semanticsDirectory.relativize(it).toString() }
                .sorted(),
        )

        val typeCheckerDispatch = checkerDispatch("dispatchTypeChecker")
        assertEquals(
            listOf("resolver26/OrchestrationTask.kt", "resolvers/resolver21/CoroutineOrchestrationTask.kt"),
            allSources.filter { source -> typeCheckerDispatch.containsMatchIn(source.readText()) }
                .map { semanticsDirectory.relativize(it).toString() }
                .sorted(),
        )
    }

    @Test
    fun `checker ownership guard recognizes calls and callable references`() {
        listOf("dispatchFieldChecker", "dispatchTypeChecker").forEach { name ->
            val pattern = checkerDispatch(name)
            listOf("dispatcher.$name(publication)", "dispatcher . $name (publication)", "dispatcher::$name").forEach {
                assertTrue(pattern.containsMatchIn(it), it)
            }
            assertTrue(!pattern.containsMatchIn("fun $name(publication: Publication)"))
        }
    }

    private fun checkerDispatch(name: String): Regex = Regex("""(?:\.\s*|::)$name(?:\s*\(|\b)""")
}
