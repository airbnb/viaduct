package viaduct.engine.api.testing

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResolverTestDslDocumentTest {
    @Test
    fun `parses declarations and strips fixture directives without constructing a world`() {
        val document = ResolverTestDslDocument.parse(
            """
            extend type Query {
              value(seed: Int): Int @resolver(
                qf: "source(value: ${'$'}renamed)"
                variables: [{name: "renamed", fromArgument: ["seed"]}]
                script: "queryValue.source"
              ) @checker(inputs: [{name: "policy", of: "source", vars: {names: ["n"], script: "{'n': 7}"}}], script: "allow")
              source(value: Int): Int
            }
            type User implements Node @nodeResolver(ids: ["one"], isSelective: false, script: "{'score': 7}") @checker(script: "deny") {
              id: ID!
              score: Int
            }
            """.trimIndent(),
        )
        val field = document.fieldDefinitions.single()
        assertEquals("Query" to "value", field.typeName to field.fieldName)
        assertEquals("queryValue.source", field.script)
        assertEquals(DslVariable("renamed", DslVariableSource.ARGUMENT, listOf("seed")), field.variables.single())
        assertTrue(field.isSelective)
        assertEquals(DslNodeResolver("User", listOf("one"), "{'score': 7}", false), document.nodeDefinitions.single())
        val input = document.checkerDefinitions.first().inputs.single()
        assertEquals("policy", input.name)
        assertEquals(DslVariablesProvider(setOf("n"), "{'n': 7}"), input.variablesProvider)
        assertEquals("deny", document.checkerDefinitions.last().script)
        assertTrue(document.schemaSDL.contains("interface Node"))
        assertTrue(document.schemaSDL.contains("source(value: Int)"))
        for (directive in listOf("resolver", "nodeResolver", "checker")) assertFalse(document.schemaSDL.contains("@$directive"))
    }
}
