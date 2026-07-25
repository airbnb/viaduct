package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime.MatSource
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatLedger
import viaduct.engine.runtime.mat.MatPath
import viaduct.graphql.utils.collectVariableReferences
import viaduct.graphql.utils.rawValue

class MatParametersTest {
    @Test
    fun `ledger source is retained`() {
        val parameters = mkExecutionParameters(
            "extend type Query { x:Int }",
            "Query" to "x",
            "{ x }",
        )
        val ledger = MatLedgerImpl(Mat.Null)

        val result = createAtRoot(
            parameters = parameters,
            ledger = ledger,
        )

        assertSame(ledger, result.ledger)
    }

    @Test
    fun `ledger source filters requested shape`() {
        val parameters = mkExecutionParameters(
            "extend type Query { x:Int y:Int }",
            "Query" to "x",
            "{ x }",
        )

        val result = createAtRoot(
            parameters = parameters,
            terminalShape = KeyTree.build(parameters) {
                field("Query", key("x"))
                field("Query", key("y"))
            },
            matFilter = { _, key, _ -> key.name == "x" },
        )

        assertEquals(
            KeyTree.build(parameters) {
                field("Query", key("x"))
            },
            result.requestedShape,
        )
    }

    @Test
    fun `ledger source preserves parameters`() {
        val parameters = mkExecutionParameters(
            "extend type Query { x:Int }",
            "Query" to "x",
            "{ x }",
        )

        val result = createAtRoot(parameters)

        assertSame(parameters, result.parameters)
    }

    @Test
    fun `ledger source uses root path`() {
        val parameters = mkExecutionParameters(
            "extend type Query { x:Int }",
            "Query" to "x",
            "{ x }",
        )
        val queryType = parameters.engineExecutionContext.activeSchema.schema.queryType

        val result = createAtRoot(parameters)

        assertEquals(MatPath(queryType), result.path)
    }

    @Test
    fun `ledger source preserves root node id`() {
        val parameters = mkExecutionParameters(
            "extend type Query { x:Int }",
            "Query" to "x",
            "{ x }",
        )

        val result = createAtRoot(
            parameters = parameters,
            rootNodeId = "Query:1",
        )

        assertEquals("Query:1", result.rootNodeId)
    }

    @Test
    fun `unbacked source throws`() {
        val parameters = mkExecutionParameters(
            "extend type Query { x:Int }",
            "Query" to "x",
            "{ x }",
        )
        val queryType = parameters.engineExecutionContext.activeSchema.schema.queryType

        val thrown = assertThrows<IllegalStateException> {
            MatParameters.create(
                objectResult = ObjectEngineResultImpl.newForType(queryType),
                terminalShape = KeyTree.empty,
                terminalParameters = parameters,
            )
        }

        assertEquals(
            "MatParameters requires a ledger-backed OER, found no backing at Query",
            thrown.message,
        )
    }

    @Test
    fun `embedded sources order path segments from root`() {
        val parameters = mkExecutionParameters(
            schemaSDL = "extend type Query { a:A } type A { b:B } type B { x:Int }",
            coordinate = "B" to "x",
            query = "{ a { b { x } } }",
        ) {
            fieldWithValue(
                "Query" to "a",
                ResolvedEngineObjectData(
                    checkNotNull(schema.schema.getObjectType("A")),
                    mapOf(
                        "b" to ResolvedEngineObjectData(
                            checkNotNull(schema.schema.getObjectType("B")),
                            emptyMap(),
                        )
                    ),
                ),
            )
        }
        val schema = parameters.engineExecutionContext.activeSchema
        val queryType = schema.schema.queryType
        val aType = checkNotNull(schema.schema.getObjectType("A"))
        val bType = checkNotNull(schema.schema.getObjectType("B"))
        val aSegment = MatPath.Segment(aType, ObjectEngineResult.Key("a"))
        val bSegment = MatPath.Segment(bType, ObjectEngineResult.Key("b"))

        val result = createEmbedded(parameters, aSegment, bSegment)

        assertEquals(
            MatPath(queryType, listOf(aSegment, bSegment)),
            result.path,
        )
    }

    @Test
    fun `embedded source wraps terminal shape`() {
        val parameters = mkExecutionParameters(
            schemaSDL = "extend type Query { f:F } type F { x:Int }",
            coordinate = "F" to "x",
            query = "{ f { x } }",
        ) {
            fieldWithValue(
                "Query" to "f",
                ResolvedEngineObjectData(
                    checkNotNull(schema.schema.getObjectType("F")),
                    emptyMap(),
                ),
            )
        }
        val segment = MatPath.Segment(
            parameters.currentObjectEngineResult.type,
            ObjectEngineResult.Key("f"),
        )

        val result = createEmbedded(
            parameters,
            segment,
            terminalShape = KeyTree.build(parameters) {
                field("F", key("x"))
            },
        )

        assertEquals(
            KeyTree.build(parameters) {
                field("Query", key("f")) {
                    field("F", key("x"))
                }
            },
            result.requestedShape,
        )
    }

    @Test
    fun `embedded source rebuilds parameters at root`() {
        val parameters = mkExecutionParameters(
            schemaSDL = "extend type Query { f:F } type F { x:Int }",
            coordinate = "F" to "x",
            query = "{ f { x } }",
        ) {
            fieldWithValue(
                "Query" to "f",
                ResolvedEngineObjectData(
                    checkNotNull(schema.schema.getObjectType("F")),
                    emptyMap(),
                ),
            )
        }
        val segment = MatPath.Segment(
            parameters.currentObjectEngineResult.type,
            ObjectEngineResult.Key("f"),
        )

        val result = createEmbedded(parameters, segment)

        assertEquals(
            KeyTree.build(parameters) {
                field("Query", key("f")) {
                    field("F", key("x"))
                }
            },
            result.parameters.queryPlan.keyTree(result.parameters),
        )
        assertSame(ExecutionOrigin.Root, result.parameters.executionOrigin)
    }

    @Test
    fun `embedded source inlines ancestor variable references`() {
        val outerParameters = mkExecutionParameters(
            schemaSDL = "extend type Query { f(arg:Int!):Foo } type Foo { x:Int }",
            coordinate = "Foo" to "x",
            query = "query(\$v:Int! = 1) { f(arg:\$v) { x } }",
        ) {
            fieldWithValue(
                "Query" to "f",
                ResolvedEngineObjectData(
                    checkNotNull(schema.schema.getObjectType("Foo")),
                    emptyMap(),
                ),
            )
        }
        val childPlan = outerParameters.queryPlan.copy(
            selectionSet = outerParameters.selectionSet,
        )
        val childParameters = outerParameters.forChildPlan(
            childPlan,
            CoercedVariables.of(mapOf("v" to 2)),
            outerParameters.targetForChildPlan(childPlan),
        )

        val matParams = createEmbedded(
            parameters = childParameters,
            MatPath.Segment(
                outerParameters.currentObjectEngineResult.type,
                ObjectEngineResult.Key("f", arguments = mapOf("arg" to 1)),
            ),
            terminalShape = KeyTree.build(childParameters) {
                field("Foo", key("x"))
            },
        )

        val rebuiltField = matParams.parameters.selectionSet.selections.single() as QueryPlan.CollectedField
        assertEquals(
            1,
            rebuiltField.mergedField.arguments.single().value.rawValue(),
        )
        rebuiltField.mergedField.singleField.collectVariableReferences().shouldBeEmpty()
    }

    private fun createAtRoot(
        parameters: ExecutionParameters,
        terminalShape: KeyTree = KeyTree.empty,
        ledger: MatLedger = MatLedgerImpl(Mat.Null),
        matFilter: KeyTreeFilter = KeyTreeFilter.KeepAll,
        rootNodeId: String? = null,
    ): MatParameters {
        val queryType = parameters.engineExecutionContext.activeSchema.schema.queryType
        return MatParameters.create(
            objectResult = ObjectEngineResultImpl.newForType(
                queryType,
                MatSource.Ledger(
                    ledger = ledger,
                    matFilter = matFilter,
                    rootNodeId = rootNodeId,
                ),
            ),
            terminalShape = terminalShape,
            terminalParameters = parameters,
        )
    }

    private fun createEmbedded(
        parameters: ExecutionParameters,
        vararg segments: MatPath.Segment,
        terminalShape: KeyTree = KeyTree.empty,
    ): MatParameters {
        val schema = parameters.engineExecutionContext.activeSchema
        val queryType = schema.schema.queryType
        var objectResult = ObjectEngineResultImpl.newForType(
            queryType,
            MatSource.Ledger(MatLedgerImpl(Mat.Null)),
        )

        for (segment in segments) {
            objectResult = ObjectEngineResultImpl.newForType(
                segment.type,
                MatSource.Embedded(objectResult, segment),
            )
        }

        return MatParameters.create(
            objectResult = objectResult,
            terminalShape = terminalShape,
            terminalParameters = parameters,
        )
    }
}
