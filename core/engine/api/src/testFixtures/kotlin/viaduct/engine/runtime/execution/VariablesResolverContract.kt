package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs

interface VariablesResolverContract : QueryExecutionContract {
    @Test
    fun `constant variable provider`() {
        assertEquals(mapOf("foo" to 30), executeQuery(constantProviderModule(), "{ foo }").toSpecification()["data"])
    }

    @Test
    fun `variable provider uses parent field argument`() {
        assertEquals(mapOf("foo" to 30), executeQuery(parentArgumentModule(), "{ foo(y: 1) }").toSpecification()["data"])
    }

    @Test
    fun `variable provider returns null value`() {
        assertEquals(mapOf("foo" to 10), executeQuery(nullProviderModule(), "{ foo }").toSpecification()["data"])
    }

    @Test
    fun `variable name may match unbound field argument`() {
        // This test defines a variable provider with a name that overlaps with a field argument.
        // The field argument is not bound to a variable, so this is allowed.
        assertEquals(mapOf("foo" to 30), executeQuery(unboundArgumentModule(), "{ foo }").toSpecification()["data"])
    }

    @Test
    fun `variables provider -- returns extra variables`() {
        assertThrows<IllegalStateException> {
            executeQuery(providerModule(mapOf("varx" to 2, "extra" to 3)), "{foo}")
        }
    }

    @Test
    fun `variables provider -- does not return declared variable value`() {
        assertThrows<IllegalStateException> {
            executeQuery(providerModule(emptyMap()), "{foo}")
        }
    }

    @Test
    fun `invalid variable reference`() {
        assertThrows<Exception> {
            EngineTestModule("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
                field("Query" to "foo") {
                    resolver {
                        objectSelections("bar(x:\$invalid)")
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                    }
                }
                field("Query" to "bar") {
                    resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
                }
            }
        }
    }

    @Test
    fun `variables are coerced`() {
        assertQuery(coercionModule("varx"), "{ foo }", "{data: {foo: 25}}")
    }

    @Test
    fun `variables resolver rss without a selection reference is missing from query plan index`() {
        val variableResolverCalls = AtomicInteger()
        assertQuery(unusedProviderModule(variableResolverCalls), "{ a }", "{data: {a: 1}}")
        assertEquals(0, variableResolverCalls.get())
    }

    @Test
    fun `variables resolver throwing surfaces as error at resolver field`() {
        // Covers the catch (e: Exception) branch in FieldResolver.launchQueryPlan, which
        // propagates the exception so the resolver's subsequent await on that slot
        // re-throws the variable-resolution error at the resolver's field.
        val result = executeQuery(throwingProviderModule(), "{ foo }")
        assertEquals(mapOf("foo" to null), result.getData<Any?>())
        assertEquals(1, result.errors.size)
        val error = result.errors[0]
        assertEquals(listOf("foo"), error.path)
        assertTrue(error.message.contains("variable resolver boom"))
    }

    @Test
    fun `variable resolver required selection with aliased typename does not hang`() {
        assertQueryWithTimeout(aliasedTypenameModule(), "{ query { flag } }", "{data: {query: {flag: true}}}")
    }

    fun constantProviderModule() =
        EngineTestModule("extend type Query { foo: Int, bar(x: Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> mapOf("varx" to 3) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 } }
            }
        }

    fun parentArgumentModule() =
        EngineTestModule("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { ctx, _ -> mapOf("varx" to ctx.arguments.getAs<Int>("y") * 2) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }

    fun nullProviderModule() =
        EngineTestModule("extend type Query { foo: Int!, bar(x:Int): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> mapOf("varx" to null) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int?>("x")?.let { 1 } ?: 2 } }
            }
        }

    fun unboundArgumentModule() =
        EngineTestModule("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$x)") {
                        variables("x") { _, _ -> mapOf("x" to 2) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }

    fun providerModule(values: Map<String, Any?>) =
        EngineTestModule("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> values }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }

    fun coercionModule(queryVariableName: String) =
        EngineTestModule("extend type Query { foo: Int, bar(x: [Int!]): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> mapOf("varx" to 2) }
                    }
                    querySelections("bar(x:\$$queryVariableName)") {
                        variables(queryVariableName) { _, _ -> mapOf(queryVariableName to 3) }
                    }
                    fn { _, obj, q, _, _ -> obj.fetchAs<Int>("bar") + q.fetchAs<Int>("bar") }
                }
            }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<List<Int>>("x").sum() * 5 } }
            }
        }

    fun unusedProviderModule(variableResolverCalls: AtomicInteger) =
        EngineTestModule("extend type Query { a: Int, b: Int }") {
            field("Query" to "a") {
                resolver {
                    objectSelections("b @include(if: false) @skip(if: \$skipB)") {
                        variables("skipB", rss = createRSS("Query", "b")) { _, _ ->
                            variableResolverCalls.incrementAndGet()
                            mapOf("skipB" to false)
                        }
                    }
                    fn { _, _, _, _, _ -> 1 }
                }
            }
        }

    fun throwingProviderModule() =
        EngineTestModule("extend type Query { foo: Int, bar(x: Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> throw RuntimeException("variable resolver boom") }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") }
                }
            }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") } }
            }
        }

    fun aliasedTypenameModule() =
        MockTenantModuleBootstrapper("extend type Query { flag:Boolean, query:Query, skipNested:Boolean }") {
            field("Query" to "query") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Query")!!, emptyMap())
                    }
                }
            }
            fieldWithValue("Query" to "skipNested", true)
            field("Query" to "flag") {
                resolver {
                    objectSelections("query @skip(if: \$skipNested) { __typename }") {
                        variables(
                            "skipNested",
                            rss = createRSS("Query", "query { ignored: __typename, skipNested }")
                        ) { ctx, _ ->
                            val skipNested = ctx.objectData
                                .getAs<EngineObjectData.Sync>("query")
                                .getAs<Boolean>("skipNested")
                            mapOf("skipNested" to skipNested)
                        }
                    }
                    fn { _, _, _, _, _ -> true }
                }
            }
        }.toEngineTestModule()

    fun aliasedTypenameAlternativeModule() =
        MockTenantModuleBootstrapper("extend type Query { flag:Boolean, query:Query }") {
            field("Query" to "query") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Query")!!, emptyMap())
                    }
                }
            }
            field("Query" to "flag") {
                resolver {
                    objectSelections(
                        """
                        query { ignored: __typename }
                        query @skip(if: ${'$'}skipNested) { __typename }
                        """.trimIndent()
                    ) {
                        variables("skipNested") { _, _ -> mapOf("skipNested" to true) }
                    }
                    fn { _, _, _, _, _ -> true }
                }
            }
        }.toEngineTestModule()
}
