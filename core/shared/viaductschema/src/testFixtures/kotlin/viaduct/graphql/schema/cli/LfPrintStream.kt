package viaduct.graphql.schema.cli

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/** PrintStream whose println() always writes '\n', not System.lineSeparator(). */
class LfPrintStream(out: OutputStream) :
    PrintStream(out, /* autoFlush = */ false, StandardCharsets.UTF_8) {
    override fun println() = write('\n'.code)

    override fun println(x: String?) {
        print(x)
        write('\n'.code)
    }
}

/** StringBuilder.appendLine equivalent that always uses '\n', not System.lineSeparator(). */
fun StringBuilder.appendLineLf(s: CharSequence): StringBuilder = append(s).append('\n')
