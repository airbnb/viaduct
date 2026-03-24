@file:Suppress("MatchingDeclarationName")

package viaduct.codegen.st

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.io.Writer
import org.stringtemplate.v4.AutoIndentWriter
import org.stringtemplate.v4.STGroupString
import org.stringtemplate.v4.STWriter
import org.stringtemplate.v4.misc.ErrorBuffer

/** Represents the text string as computed by applying
 *  a model to an ST group template.
 */
class STContents(
    /** String containing an ST group template. */
    private val stGroupString: String,
    /** The model-data referenced by that string. */
    private val model: Any,
) {
    /** Write the text represented by this object to a file.
     *  Throws an error if the template can't be executed.
     */
    fun write(dst: File) {
        FileOutputStream(dst).use { fileOut ->
            BufferedWriter(OutputStreamWriter(fileOut)).use { flushableOut ->
                write(flushableOut)
            }
        }
    }

    /** Render the text represented by this object to a String.
     *  Throws an error if the template can't be executed.
     */
    override fun toString(): String = StringWriter().also(::write).toString()

    private fun write(writer: Writer) {
        val template = STGroupString(stGroupString).getInstanceOf("main")
        template.add("mdl", model)
        val out = AutoIndentWriter(writer)
        out.setLineWidth(STWriter.NO_WRAP)
        val errs = ErrorBuffer()
        template.write(out, errs)
        if (errs.errors.isNotEmpty()) throw RuntimeException("$errs")
    }
}

/** Takes a signature and template text and creates a well-formatted
 *  template (trimming indent from a template text).
 *  `stTemplate("main(mdl)", "   class <mdl.name>")` becomes
 *  ```
 *  main(mdl) ::= <<
 *  class <mdl.name>
 *  >>
 *  ```
 */
fun stTemplate(
    templateSig: String,
    templateText: String
): String = "$templateSig ::= <<\n" + templateText.trimIndent() + "\n>>\n"

/** Uses a widely used signature, "main(mdl)", for the template text. */
fun stTemplate(templateText: String): String = stTemplate("main(mdl)", templateText)
