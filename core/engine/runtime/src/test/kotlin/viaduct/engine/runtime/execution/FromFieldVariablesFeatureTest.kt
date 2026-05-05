package viaduct.engine.runtime.execution

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariableCycleException
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.FieldUnbatchedResolverFn
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockLegacyTenantModuleBootstrapper
import viaduct.engine.api.mocks.MockLegacyTenantModuleBootstrapperDSL
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.engine.runtime.tenantloading.RequiredSelectionsCycleException

class FromFieldVariablesFeatureTest {
    @Test
    fun `from object field -- simple`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field -- variables used by field on non-root object`() =
        MockLegacyTenantModuleBootstrapper(
            """
                type Obj { x:Int, y(b:Int):Int, z:Int }
                extend type Query { obj:Obj }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Obj" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Obj" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Obj" to "z", 2)
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), emptyMap())
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ obj { x } }").assertJson("{data: {obj: {x: 30}}}")
        }

    @Test
    fun `from object field -- simple mutation field`() =
        MockLegacyTenantModuleBootstrapper(
            """
                extend type Mutation { x:Int, y(b:Int):Int, z:Int }
                extend type Query { empty:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Mutation" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Mutation" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Mutation" to "z", 2)
        }.runFeatureTest {
            runQuery("mutation { x }").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field -- selection is field with omitted arg and default value`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int, z(c:Int = 2):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("c") * 3 }
                }
            }
        }.runFeatureTest {
            runQuery("{ x }").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `from object field -- selection is field with arg`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int!, y(b:Int!):Int!, z(c:Int!):Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z(c:2)",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("c") * 3 }
                }
            }
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `from object field -- selection is field with omitted argument value`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int, z(c:Int):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 3
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 2 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> (args["c"] as? Int) ?: -1 }
                }
            }
        }.runFeatureTest {
            runQuery("{ x }").assertJson("{data: {x: -6}}")
        }

    @Test
    fun `from object field -- selection is aliased`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), myz:z",
                variables = listOf(FromObjectFieldVariable("b", "myz")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field -- selection is list-valued`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:[Int]):Int, z:[Int] }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<List<Int>>("b").fold(1) { acc, i -> acc * i }
                    }
                }
            }
            fieldWithValue("Query" to "z", listOf(2, 3, 5))
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `from object field -- single-field-multiple-variable -- multiple variables on required selection`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int, c:Int):Int, z:Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b, c:\$c), z, w",
                variables = listOf(
                    FromObjectFieldVariable("b", "z"),
                    FromObjectFieldVariable("c", "w"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("b") * args.getAs<Int>("c") * 5
                    }
                }
            }
            fieldWithValue("Query" to "z", 3)
            fieldWithValue("Query" to "w", 2)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `from object field -- single-field-multiple-variable -- multiple required selections with variables`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int, z(c:Int):Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z(c:\$c), w",
                variables = listOf(
                    FromObjectFieldVariable("b", "z"),
                    FromObjectFieldVariable("c", "w"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("c") * 3 }
                }
            }
            fieldWithValue("Query" to "w", 2)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `from object field -- selection traverses through object`() =
        MockLegacyTenantModuleBootstrapper(
            """
                extend type Query { x:Int, y(b:Int):Int, z:Obj }
                type Obj { w:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), mapOf("w" to 2))
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field -- selection traverses through null object`() =
        MockLegacyTenantModuleBootstrapper(
            """
                extend type Query { x:Int, y(b:Int):Int!, z:Obj }
                type Obj { w:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> ((args["b"] as? Int) ?: -1) * 3 }
                }
            }
            fieldWithValue("Query" to "z", null)
        }.runFeatureTest {
            runQuery("{ x }").assertJson("{data: {x: -15}}")
        }

    @Test
    fun `from object field -- selection traverses through union`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper(
                """
                    type Foo { value: String }
                    union Union = Foo
                    extend type Query { string1:String, hasArgs2(x:String):String, iface:Union }
                """.trimIndent()
            ) {
                fieldWithFromFieldVariables(
                    coord = "Query" to "string1",
                    objectSelectionsText = "hasArgs2(x:\$x), iface { ... on Foo { value } }",
                    variables = listOf(FromObjectFieldVariable("x", "iface.value")),
                ) { _, obj, _, _, _ ->
                    obj.fetchAs<String>("hasArgs2") + "."
                }
                field("Query" to "hasArgs2") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<String>("x") + "." }
                    }
                }
                field("Query" to "iface") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(schema.schema.getObjectType("Foo"), mapOf("value" to "FOO"))
                        }
                    }
                }
            }.runFeatureTest { }
        }.unwrapAs<InvalidVariableException>()

        assertEquals("x", err.variableName)
    }

    @Test
    fun `invalid from object field -- selection output type is not compatible with variable input type -- nullability mismatch`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int!):Int!, z:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "y(b:\$b), z",
                    variables = listOf(FromObjectFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> 0 }
                fieldWithValue("Query" to "y", 0)
                fieldWithValue("Query" to "z", null)
            }.runFeatureTest { }
        }.unwrapAs<InvalidVariableException>()

        assertEquals("b", err.variableName)
    }

    @Test
    fun `invalid from object field -- selection output type is not compatible with variable input type -- type mismatch`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int!):Int!, z:String! }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "y(b:\$b), z",
                    variables = listOf(FromObjectFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> 0 }
                fieldWithValue("Query" to "y", 0)
                fieldWithValue("Query" to "z", "")
            }.runFeatureTest { }
        }.unwrapAs<InvalidVariableException>()

        assertEquals("b", err.variableName)
    }

    @Test
    fun `from object field - same variable name used in operation variable and annotation variable`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x(a:Int):Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { args, obj, _, _, _ ->
                args.getAs<Int>("a") * obj.fetchAs<Int>("y")
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            fieldWithValue("Query" to "z", 3)
        }.runFeatureTest {
            runQuery(
                "query Q(\$vara:Int!) {x(a:\$vara)}",
                mapOf("vara" to 2),
            ).assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from object field - same variable name used in multiple selection sets`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y:Int, z(c:Int):Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "z(c:\$var), w",
                variables = listOf(FromObjectFieldVariable("var", "w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("z") * 5
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "y",
                objectSelectionsText = "z(c:\$var), w",
                variables = listOf(FromObjectFieldVariable("var", "w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("z") * 3
            }
            field("Query" to "z") {
                resolver {
                    fn { _, _, _, _, _ -> 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{x, y}").assertJson("{data: {x: 10, y: 6}}")
        }

    @Test
    fun `from object field -- variable used in conditional directive`() {
        var yResolved = false

        MockLegacyTenantModuleBootstrapper("extend type Query { x:String, y:Boolean, z:Boolean! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y @skip(if:\$z), z",
                variables = listOf(FromObjectFieldVariable("z", "z")),
            ) { _, obj, _, _, _ ->
                runCatching {
                    obj.fetchAs<Boolean>("y")
                }.exceptionOrNull()?.javaClass?.simpleName
            }
            field("Query" to "y") {
                resolver {
                    fn { _, _, _, _, _ ->
                        yResolved = true
                        true
                    }
                }
            }
            fieldWithValue("Query" to "z", true)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: \"UnsetFieldException\"}}")
        }

        assertFalse(yResolved)
    }

    @Test
    fun `invalid from object field -- variable depends on a field in its own subselections`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x(a:Int):Query, y:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "x(a:\$a) { y } ",
                    variables = listOf(FromObjectFieldVariable("a", "x.y")),
                ) { _, _, _, _, _ -> error("should not execute") }
                field("Query" to "y") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
            }.runFeatureTest { }
        }.unwrap()

        assertTrue(err is VariableCycleException)
    }

    @Test
    fun `invalid from object field -- variable selects a field that uses it`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x(a:Int):Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "x(a:\$a)",
                    variables = listOf(FromObjectFieldVariable("a", "x")),
                ) { _, _, _, _, _ -> error("should not execute") }
            }.runFeatureTest { }
        }.unwrap()

        assertTrue(err is VariableCycleException)
    }

    @Test
    fun `invalid from object field -- deadlock between 2 variables -- same selection set`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x(a:Int):Int, y(b:Int):Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "x(a:\$a), y(b:\$b)",
                    variables = listOf(
                        FromObjectFieldVariable("a", "y"),
                        FromObjectFieldVariable("b", "x"),
                    ),
                ) { _, _, _, _, _ -> error("should not execute") }
                field("Query" to "y") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
            }.runFeatureTest { }
        }.unwrap()

        assertTrue(err is VariableCycleException)
    }

    @Test
    fun `invalid from object field -- deadlock between 2 variables -- diff selection sets`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x(a:Int):Int, y(b:Int):Int, z:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "y(b:\$b), z",
                    variables = listOf(FromObjectFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> error("should not execute") }
                fieldWithFromFieldVariables(
                    coord = "Query" to "y",
                    objectSelectionsText = "x(a:\$a), z",
                    variables = listOf(FromObjectFieldVariable("a", "z")),
                ) { _, _, _, _, _ -> error("should not execute") }
            }.runFeatureTest { }
        }.unwrap()

        assertTrue(err is RequiredSelectionsCycleException)
    }

    @Test
    fun `invalid from query field -- path refers to missing selection`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    querySelectionsText = "y(b:\$b)",
                    variables = listOf(FromQueryFieldVariable("b", "invalidField")),
                ) { _, _, _, _, _ -> error("should not execute") }
                fieldWithValue("Query" to "y", 2)
            }.runFeatureTest { }
        }.unwrapAs<IllegalArgumentException>()

        assertTrue(checkNotNull(err.message).contains("No selections found for path"), err.message.orEmpty())
    }

    @Test
    fun `invalid from query field -- path ends on object`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int, z:Query, w:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    querySelectionsText = "y(b:\$b), z { w }",
                    variables = listOf(FromQueryFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> error("should not execute") }
                field("Query" to "y") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
                field("Query" to "z") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
            }.runFeatureTest { }
        }.unwrapAs<InvalidVariableException>()

        assertEquals("b", err.variableName)
    }

    @Test
    fun `from query field -- simple`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                querySelectionsText = "y(b:\$b), z",
                variables = listOf(FromQueryFieldVariable("b", "z")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from query field -- variables used by field on non-root object`() =
        MockLegacyTenantModuleBootstrapper(
            """
                type Obj { x:Int }
                extend type Query { obj:Obj y(b:Int):Int, z:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Obj" to "x",
                querySelectionsText = "y(b:\$b), z",
                variables = listOf(FromQueryFieldVariable("b", "z")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), emptyMap())
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ obj { x } }").assertJson("{data: {obj: {x: 30}}}")
        }

    @Test
    fun `from query field -- simple mutation field`() =
        MockLegacyTenantModuleBootstrapper(
            """
                extend type Mutation { x:Int, y(b:Int):Int }
                extend type Query { z:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Mutation" to "x",
                objectSelectionsText = "y(b:\$z)",
                querySelectionsText = "z",
                variables = listOf(FromQueryFieldVariable("z", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Mutation" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runFeatureTest {
            runQuery("mutation {x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from query field -- binds variable to query field with different name`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(a:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                querySelectionsText = "y(a:\$varz), z",
                variables = listOf(FromQueryFieldVariable("varz", "z")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("a") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }

    @Test
    fun `from query field -- returns null value`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int!, y(a:Int):Int!, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(a:\$z)",
                querySelectionsText = "z",
                variables = listOf(FromQueryFieldVariable("z", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 2
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> (args["a"] as? Int) ?: -1 }
                }
            }
            fieldWithValue("Query" to "z", null)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data:{x:-2}}")
        }

    @Test
    fun `from query field -- single-field-multiple-variable -- multiple variables on required selection`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(b:Int, c:Int):Int, z:Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                querySelectionsText = "y(b:\$b, c:\$c), z, w",
                variables = listOf(
                    FromQueryFieldVariable("b", "z"),
                    FromQueryFieldVariable("c", "w"),
                ),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("b") * args.getAs<Int>("c") * 5
                    }
                }
            }
            fieldWithValue("Query" to "z", 3)
            fieldWithValue("Query" to "w", 2)
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `invalid from query field -- variable name overlaps with object field variable`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { foo: String!, bar(x: String!): String! }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "bar",
                    objectSelectionsText = "fragment _ on Query { foo }",
                    querySelectionsText = "fragment _ on Query { foo }",
                    variables = listOf(
                        FromObjectFieldVariable("name", "foo"),
                        FromQueryFieldVariable("name", "foo"),
                    ),
                ) { _, _, _, _, _ -> "result" }
                fieldWithValue("Query" to "foo", "test")
            }.runFeatureTest { }
        }.unwrap()

        val msg = checkNotNull(err.message)
        assertTrue(msg.contains("name"), msg)
        assertTrue(
            msg.contains("unused variables") || msg.contains("duplicate bindings"),
            msg,
        )
    }

    @Test
    fun `invalid from query field -- variable name overlaps with argument variable`() {
        val err = assertThrows<Throwable> {
            MockLegacyTenantModuleBootstrapper("extend type Query { foo: String!, bar(name: String!): String! }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "bar",
                    querySelectionsText = "fragment _ on Query { foo }",
                    variables = listOf(
                        FromArgumentVariable("name", "name"),
                        FromQueryFieldVariable("name", "foo"),
                    ),
                ) { _, _, _, _, _ -> "result" }
                fieldWithValue("Query" to "foo", "test")
            }.runFeatureTest { }
        }.unwrap()

        val msg = checkNotNull(err.message)
        assertTrue(msg.contains("name"), msg)
        assertTrue(
            msg.contains("unused variables") || msg.contains("duplicate bindings"),
            msg,
        )
    }

    @Test
    fun `mixed variables -- from query field and from argument`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x(a:Int):Int, y(a:Int, b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(a:\$vara, b:\$varb)",
                querySelectionsText = "z",
                variables = listOf(
                    FromArgumentVariable("vara", "a"),
                    FromQueryFieldVariable("varb", "z"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("a") * args.getAs<Int>("b") * 5
                    }
                }
            }
            fieldWithValue("Query" to "z", 3)
        }.runFeatureTest {
            runQuery("{x(a:2)}").assertJson("{data: {x: 210}}")
        }

    @Test
    fun `mixed variables -- from query field and from object field`() =
        MockLegacyTenantModuleBootstrapper("extend type Query { x:Int, y(a:Int, b:Int):Int, z(w:Int):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(a:\$a, b:\$b), z1:z(w:7)",
                querySelectionsText = "z2:z(w:5)",
                variables = listOf(
                    FromObjectFieldVariable("a", "z1"),
                    FromQueryFieldVariable("b", "z2"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 11
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("a") * args.getAs<Int>("b") * 3
                    }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("w") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{x}").assertJson("{data: {x: 4620}}")
        }

    @Test
    fun `mixed variables -- non-root resolver uses fromObjectField in queryFragment`() =
        MockLegacyTenantModuleBootstrapper(
            """
                extend type Query { x(a:Int):Int, obj:Obj }
                type Obj { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), emptyMap())
                    }
                }
            }
            field("Query" to "x") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("a") * 5 }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Obj" to "x",
                objectSelectionsText = "y",
                querySelectionsText = "x(a:\$a)",
                variables = listOf(FromObjectFieldVariable("a", "y")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("x") * 3
            }
            fieldWithValue("Obj" to "y", 2)
        }.runFeatureTest {
            runQuery("{obj{x}}").assertJson("{data: {obj: {x: 30}}}")
        }
}

private fun MockLegacyTenantModuleBootstrapperDSL<Unit>.fieldWithFromFieldVariables(
    coord: Pair<String, String>,
    objectSelectionsText: String? = null,
    querySelectionsText: String? = null,
    variables: List<SelectionSetVariable> = emptyList(),
    resolveFn: FieldUnbatchedResolverFn,
) {
    val objectSelections = objectSelectionsText?.let { SelectionsParser.parse(coord.first, it) }
    val querySelections = querySelectionsText?.let { SelectionsParser.parse(queryType.name, it) }
    val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
        objectSelections = objectSelections,
        querySelections = querySelections,
        variables = variables,
        forChecker = false,
    )

    field(coord) {
        resolverExecutor {
            MockFieldUnbatchedResolverExecutor(
                objectSelectionSet = objectSelections?.let {
                    RequiredSelectionSet(it, variablesResolvers, forChecker = false)
                },
                querySelectionSet = querySelections?.let {
                    RequiredSelectionSet(it, variablesResolvers, forChecker = false)
                },
                resolverId = resolverId,
                unbatchedResolveFn = resolveFn,
            )
        }
    }
}

private tailrec fun Throwable.unwrap(): Throwable = cause?.takeIf { it !== this }?.unwrap() ?: this

private inline fun <reified T : Throwable> Throwable.unwrapAs(): T =
    unwrap() as? T
        ?: throw IllegalStateException("Expected ${T::class.simpleName} but got ${unwrap()::class.simpleName}")
