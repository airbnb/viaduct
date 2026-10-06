package viaduct.engine.runtime2.resolution

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestScopeOwnershipTest {
    @Test
    fun `only task roots and the mutation completion waiter can launch on the request scope`() {
        val projectDirectory = Path.of(System.getProperty("viaduct.runtime2.sourceRoot", "."))
        val sourceDirectory = projectDirectory.resolve("src/main/kotlin/viaduct/engine/runtime2/resolution")
        assertTrue(Files.isDirectory(sourceDirectory), "Resolution source directory is missing")
        val sources =
            Files.list(sourceDirectory).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".kt") }.toList()
            }

        val sourceRoots = listOf(sourceDirectory.parent, projectDirectory.resolve("src/support/kotlin/viaduct/engine/runtime2"))
        val allSources = sourceRoots.flatMap { directory ->
            Files.walk(directory).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".kt") }.toList()
            }
        }

        fun relativePath(source: Path): String = sourceRoots.first { source.startsWith(it) }.relativize(source).toString()
        val rawRequestScopeLaunch = Regex("""requestScope\s*\.\s*(?:launch|async|future)\s*(?:\(|\{)""")
        assertEquals(
            listOf(
                "execution/QPlanExecutionStrategy.kt",
                "resolution/CoroutineTaskDispatcher.kt",
                "resolution/FieldCheckerTask.kt",
                "resolution/FieldResolverTask.kt",
                "resolution/TypeCheckerTask.kt",
                "resolvers/resolver21/CoroutineFieldCheckerTask.kt",
                "resolvers/resolver21/CoroutineFieldResolverTask.kt",
                "resolvers/resolver21/CoroutineTypeCheckerTask.kt",
            ),
            allSources.filter { source -> rawRequestScopeLaunch.containsMatchIn(source.readText()) }
                .map(::relativePath)
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

        val mutationDispatch = Regex("""\.\s*dispatchMutationOrchestration\s*\(""")
        assertEquals(
            listOf("resolution/Resolver.kt", "resolvers/resolver21/CoroutineResolve.kt"),
            allSources.filter { mutationDispatch.containsMatchIn(it.readText()) }.map(::relativePath).sorted(),
        )
        val orderedFieldDispatch = checkerDispatch("dispatchMutationField")
        assertEquals(
            listOf("resolution/framework/MutationOrchestrationTask.kt"),
            allSources.filter { orderedFieldDispatch.containsMatchIn(it.readText()) }.map(::relativePath).sorted(),
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
            listOf("resolution/OrchestrationTask.kt", "resolvers/resolver21/CoroutineOrchestrationTask.kt"),
            allSources.filter { source -> fieldCheckerDispatch.containsMatchIn(source.readText()) }
                .map(::relativePath)
                .sorted(),
        )

        val typeCheckerDispatch = checkerDispatch("dispatchTypeChecker")
        assertEquals(
            listOf("resolution/OrchestrationTask.kt", "resolvers/resolver21/CoroutineOrchestrationTask.kt"),
            allSources.filter { source -> typeCheckerDispatch.containsMatchIn(source.readText()) }
                .map(::relativePath)
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
