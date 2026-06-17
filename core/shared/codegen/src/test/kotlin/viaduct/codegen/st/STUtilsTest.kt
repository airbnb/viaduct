package viaduct.codegen.st

import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class STUtilsTest {
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
