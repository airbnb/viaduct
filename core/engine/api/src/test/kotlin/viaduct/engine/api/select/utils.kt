package viaduct.engine.api.select

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Node
import org.junit.jupiter.api.Assertions.assertEquals
import viaduct.engine.api.ParsedSelections

fun assertNodesEqual(
    expected: Node<*>?,
    actual: Node<*>?
) {
    assertEquals(AstPrinter.printAstCompact(expected), AstPrinter.printAstCompact(actual))
}

fun assertParsedSelectionsEqual(
    expected: ParsedSelections,
    actual: ParsedSelections?
) {
    assertEquals(expected.typeName, actual?.typeName)
    assertNodesEqual(expected.selections, actual?.selections)
    assertEquals(expected.fragmentMap.keys, actual?.fragmentMap?.keys)
    expected.fragmentMap.forEach { name, expDef ->
        val actDef = actual?.fragmentMap?.get(name)
        assertNodesEqual(expDef, actDef)
    }
}

fun Document.render(): String = AstPrinter.printAstCompact(this)
