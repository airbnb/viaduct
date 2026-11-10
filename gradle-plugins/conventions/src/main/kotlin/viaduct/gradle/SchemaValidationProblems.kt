package viaduct.gradle

import graphql.GraphQLError
import java.io.File
import java.lang.reflect.InvocationTargetException
import org.gradle.api.Action
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.ProblemReporter
import org.gradle.api.problems.ProblemSpec
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity
import org.gradle.util.GradleVersion
import viaduct.graphql.schema.validation.SchemaValidationError

internal class SchemaValidationProblems(
    private val problems: Problems,
    private val sourceRoot: File,
    private val stage: String,
    private val finalContributors: List<String> = emptyList(),
) {
    private val group by lazy { ProblemGroup.create("viaduct-schema-validation", "Viaduct schema validation") }

    fun reportRule(
        provider: String,
        ruleId: String?,
        error: SchemaValidationError
    ) {
        val identity = ruleId ?: error.code
        report(identity, error.code, provider) {
            contextualLabel(error.message)
            details("$stage schema; provider=$provider; rule=$identity; coordinate=${error.location.path.joinToString(".")}" + contributorDetails())
            severity(if (error.severity == SchemaValidationError.Severity.ERROR) Severity.ERROR else Severity.WARNING)
            error.location.sourceLocation?.sourceName?.let { source -> fileLocation(sourceRoot.resolve(source).absolutePath) }
        }
    }

    fun reportStructural(error: GraphQLError) {
        report("graphql-structure", "GraphQL structural validation") {
            contextualLabel(error.message)
            details("$stage schema" + contributorDetails())
            severity(Severity.ERROR)
            error.locations?.firstOrNull()?.let { location ->
                location.sourceName?.let { source -> lineInFileLocation(sourceRoot.resolve(source).absolutePath, location.line, location.column) }
            }
        }
    }

    private fun report(
        identity: String,
        displayName: String,
        provider: String? = null,
        configure: ProblemSpec.() -> Unit
    ) {
        if (GradleVersion.current().baseVersion >= GradleVersion.version("8.13")) {
            val parent = provider?.let { ProblemGroup.create(it, it, group) } ?: group
            problems.reporter.report(
                ProblemId.create(identity, displayName, parent),
                object : Action<ProblemSpec> {
                    override fun execute(spec: ProblemSpec) = configure(spec)
                }
            )
        } else {
            LegacyProblems.report(problems.reporter, listOfNotNull(provider, identity).joinToString(":"), displayName, configure)
        }
    }

    private fun contributorDetails() = if (finalContributors.isEmpty()) "" else "; final contributors=${finalContributors.joinToString()}; central locations are context"
}

// Gradle removed these public incubating methods in 8.13, so they cannot be linked at compile time.
private object LegacyProblems {
    private val reporting by lazy { ProblemReporter::class.java.getMethod("reporting", Action::class.java) }
    private val id by lazy { ProblemSpec::class.java.getMethod("id", String::class.java, String::class.java) }

    fun report(
        reporter: ProblemReporter,
        identity: String,
        displayName: String,
        configure: ProblemSpec.() -> Unit
    ) {
        try {
            reporting.invoke(
                reporter,
                object : Action<ProblemSpec> {
                    override fun execute(spec: ProblemSpec) {
                        id.invoke(spec, identity, displayName)
                        configure(spec)
                    }
                }
            )
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
