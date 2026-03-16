@file:Suppress("ForbiddenImport")
@file:OptIn(VisibleForTest::class)

package viaduct.engine.runtime.select

import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import io.kotest.property.Arb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.apiannotations.VisibleForTest
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.arbitrary.graphql.graphQLDocument
import viaduct.engine.api.select.ParsedSelectionsImpl

class ArbitraryEngineSelectionSetImplTest : KotestPropertyBase() {
    @Test
    fun `arbitrary selection sets can be fully traversed`(): Unit =
        runBlocking {
            val schema = """
            extend type Query { x:Int, y(a:Int):Int, obj:Obj }
            type Obj implements Node { id:ID!, a:Int, b:Int, obj:Obj }
        """.asViaductSchema

            val factory = EngineSelectionSetFactoryImpl(schema)

            Arb.graphQLDocument(schema)
                .checkAll { doc ->
                    val parsed = ParsedSelectionsImpl(
                        typeName = "Query",
                        selections = doc.getFirstDefinitionOfType(OperationDefinition::class.java).get().selectionSet,
                        fragmentMap = doc.getDefinitionsOfType(FragmentDefinition::class.java)
                            .associateBy { it.name }
                    )

                    val ss = factory.engineSelectionSet(parsed, emptyMap()) as EngineSelectionSetImpl
                    traverseSelectionSet(ss)
                }
        }

    private fun traverseSelectionSet(ess: EngineSelectionSetImpl) {
        for (selection in ess.traversableSelections()) {
            val subEss = ess.selectionSetForSelection(
                selection.typeCondition,
                selection.selectionName
            )
            traverseSelectionSet(subEss)
        }
    }
}
