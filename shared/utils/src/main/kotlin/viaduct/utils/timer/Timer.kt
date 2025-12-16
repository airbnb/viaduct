package viaduct.utils.timer

class Timer {
    private val timings = mutableListOf<String>()
    private val context = mutableListOf<String>()

    fun <T> time(
        taskName: String,
        block: () -> T
    ): T {
        context.add(taskName)
        val start = System.currentTimeMillis()
        val result = block()
        val end = System.currentTimeMillis()
        timings.add("${context.joinToString(" > ")}: ${end - start}")
        context.removeLast()
        return result
    }

    fun reportViaException(): Unit = throw RuntimeException("Timings (ms):\n${timings.joinToString("\n")}")
}
