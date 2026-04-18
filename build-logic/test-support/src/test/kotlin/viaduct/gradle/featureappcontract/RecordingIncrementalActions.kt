package viaduct.gradle.featureappcontract

import java.io.File

/**
 * Recording fake of [IncrementalActions] for unit testing [processChanges].
 *
 * Records all actions taken and delegates [goneOrEmpty] / [gone] to the
 * real filesystem — the test's [IncTestFS] has already materialized the
 * post-change state on disk, so real file checks give correct answers.
 */
internal class RecordingIncrementalActions : IncrementalActions {
    private val _actions = mutableListOf<ExpectedAction>()

    /** The ordered list of actions that were recorded. */
    val actions: List<ExpectedAction> get() = _actions

    override fun assembleForSchema(
        pkgPath: String,
        descriptorRoot: File,
        schemaFile: File
    ) {
        _actions.add(ExpectedAction.Assembled(pkgPath))
    }

    override fun deleteConfig(pkg: String) {
        _actions.add(ExpectedAction.Deleted(pkg))
    }

    override fun goneOrEmpty(dir: File): Boolean = !dir.exists() || dir.listFiles()?.isEmpty() != false

    override fun gone(file: File): Boolean = !file.exists()
}
