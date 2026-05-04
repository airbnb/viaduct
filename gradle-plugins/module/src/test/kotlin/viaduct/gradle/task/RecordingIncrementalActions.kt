package viaduct.gradle.task

import java.io.File

/**
 * Recording fake of [IncrementalActions] for unit testing [processChanges].
 *
 * Records all actions taken and delegates [goneOrEmpty] to the real filesystem —
 * the test has already materialized the post-change state on disk, so real file
 * checks give correct answers.
 */
internal class RecordingIncrementalActions : IncrementalActions {
    private val _actions = mutableListOf<Action>()

    val actions: List<Action> get() = _actions

    override fun assembleConfig(descriptorRoot: File) {
        _actions.add(Action.Assembled)
    }

    override fun deleteConfig(pkg: String) {
        _actions.add(Action.Deleted(pkg))
    }

    sealed interface Action {
        object Assembled : Action {
            override fun toString() = "Assembled"
        }

        data class Deleted(val pkg: String) : Action
    }
}
