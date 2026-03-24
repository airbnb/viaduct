package viaduct.utils.timer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimerTest {
    @Test
    fun testAssertResult() {
        val taskName = "task-name"
        val result = "task-value"
        val timer = Timer()
        val timerResult = timer.time(taskName) {
            result
        }
        assertEquals(result, timerResult)
    }

    @Test
    fun timerAssertResults() {
        val taskNames = List(5) { i -> "task-name$i" }
        val taskValues = List(5) { i -> "task-value$i" }

        val timer = Timer()
        val results = taskNames.mapIndexed { index, name ->
            timer.time(name) {
                taskValues[index]
            }
        }
        assertEquals(results, taskValues)
    }

    @Test
    fun timerAssertException() {
        val taskNames = List(10) { i -> "task-name$i" }
        val taskValues = List(10) { i -> "task-value$i" }

        val timer = Timer()
        val results = taskNames.mapIndexed { index, name ->
            timer.time(name) {
                taskValues[index]
            }
        }
        assertEquals(results, taskValues)
        kotlin.runCatching { timer.reportViaException() }.onFailure { exception ->
            assert(exception is RuntimeException)
            assert(!exception.message.isNullOrBlank())
            assert(exception.message != null)
            assert(exception.message!!.startsWith("Timings (ms):"))
            taskNames.forEach {
                assert(exception.message!!.contains(it))
            }

            taskValues.forEach {
                assert(!exception.message!!.contains(it))
            }
        }
    }
}
