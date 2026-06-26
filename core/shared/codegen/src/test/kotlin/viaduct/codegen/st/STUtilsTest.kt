package viaduct.codegen.st

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class STUtilsTest {
    private data class NameModel(val name: String)

    @Test
    fun `STContents -- renders LF regardless of platform line separator`() {
        // Counterfactual guard: STUtils must force "\n" when constructing AutoIndentWriter.
        // The no-arg AutoIndentWriter(writer) constructor captures System.getProperty("line.separator")
        // at construction time, so by overriding it to CRLF here this test fails on ANY OS (not just
        // Windows) if STUtils reverts to the platform default. We save/restore the property so this
        // override does not leak into other tests sharing the JVM.
        val original = System.lineSeparator()
        System.setProperty("line.separator", "\r\n")
        try {
            val contents = STContents(
                stTemplate("class <mdl.name>\nfield <mdl.name>"),
                NameModel("Foo")
            )

            val rendered = contents.toString()

            rendered.shouldNotContain("\r")
            rendered.shouldContain("\n")
        } finally {
            System.setProperty("line.separator", original)
        }
    }

    @Test
    fun `STContents -- toString`() {
        val contents = STContents(stTemplate("{<mdl>}"), "FOO")
        assertEquals("{FOO}", contents.toString())
    }

    @Test
    fun `STContents -- indentation`() {
        val contents = STContents(
            stTemplate(
                """
                {
                  <mdl; separator="\n">
                }
            """
            ),
            listOf("a", "b", "c")
        )
        val exp = """
            {
              a
              b
              c
            }
        """.trimIndent()
        contents.toString().replace("\r\n", "\n") shouldBe exp.replace("\r\n", "\n")
    }

    @Test
    fun `STContents -- write File`() {
        val contents = STContents(stTemplate("{<mdl>}"), "FOO")
        val f = File.createTempFile("test", null).also { it.deleteOnExit() }
        contents.write(f)
        assertEquals("{FOO}", f.readText())
    }

    @Test
    fun `stTemplate`() {
        val tmpl = stTemplate("{<mdl>}")
        val exp = """
            main(mdl) ::= <<
            {<mdl>}
            >>

        """.trimIndent()
        assertEquals(exp, tmpl)
    }

    @Test
    fun `stTemplate -- with templateSig`() {
        val tmpl = stTemplate("fn(x)", "{<x>}")
        val exp = """
            fn(x) ::= <<
            {<x>}
            >>

        """.trimIndent()
        assertEquals(exp, tmpl)
    }
}
