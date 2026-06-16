package viaduct.engine.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest

@ExperimentalCoroutinesApi
class VariablesResolverTest {
    @Test
    fun `variables provider -- const`() =
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
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{ foo }").assertJson("{data: {foo: 30}}")
        }

    @Test
    fun `variables provider -- transform dependent field arg`() =
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
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo(y:1)}").assertJson("{data: {foo: 30}}")
        }

    @Disabled("Disabled until validation of variables-provider behavior is in engine.")
    @Test
    fun `variables provider -- returns extra variables`() =
        EngineTestModule("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> mapOf("varx" to 2, "extra" to 3) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 }
                }
            }
        }.runFeatureTest {
            assertThrows<IllegalStateException> {
                runQuery("{foo}")
            }
        }

    @Test
    fun `variables provider -- returns null value`() =
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
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int?>("x")?.let { 1 } ?: 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}").assertJson("{data: {foo:10}}")
        }

    @Disabled("Disabled until validation of variables-provider behavior is in engine.")
    @Test
    fun `variables provider -- does not return declared variable value`() =
        EngineTestModule("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> emptyMap<String, Any?>() }
                    }
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 }
                }
            }
        }.runFeatureTest {
            assertThrows<IllegalStateException> {
                runQuery("{foo}")
            }
        }

    @Test
    fun `variables provider -- variable name overlaps with unbound field arg`() =
        // this test defines a variable provider that defines a variable with a name that overlaps with
        // a field argument. The field argument is not bound to a variable, so this is allowed
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
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}").assertJson("{data: {foo: 30}}")
        }

    @Disabled("Disabled until validation of variables-provider behavior is in engine.")
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
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 }
                    }
                }
            }
        }
    }

    @Test
    fun `variables are coerced`() {
        EngineTestModule("extend type Query { foo: Int, bar(x: [Int!]): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> mapOf("varx" to 2) }
                    }
                    querySelections("bar(x:\$varx)") {
                        variables("varx") { _, _ -> mapOf("varx" to 3) }
                    }
                    fn { _, obj, q, _, _ -> obj.fetchAs<Int>("bar") + q.fetchAs<Int>("bar") }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<List<Int>>("x").sum() * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{ foo }").assertJson("{data: {foo: 25}}")
        }
    }

    @Test
    fun `variables resolver rss without a selection reference is missing from query plan index`() {
        var variableResolverCalls = 0
        EngineTestModule(
            "extend type Query { a: Int, b: Int }"
        ) {
            field("Query" to "a") {
                resolver {
                    objectSelections("b @include(if: false) @skip(if: ${"$"}skipB)") {
                        variables(
                            "skipB",
                            rss = createRSS("Query", "b")
                        ) { _, _ ->
                            variableResolverCalls++
                            mapOf("skipB" to false)
                        }
                    }
                    fn { _, _, _, _, _ -> 1 }
                }
            }
        }.runFeatureTest {
            runQuery("{ a }").assertJson("{data: {a: 1}}")
            assertEquals(0, variableResolverCalls)
        }
    }

    @Test
    fun `variables resolver throwing surfaces as error at resolver field`() {
        // Covers the catch (e: Exception) branch in FieldResolver.launchQueryPlan, which
        // propagates the exception so the resolver's subsequent
        // await on that slot re-throws the variable-resolution error at the resolver's field.
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
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ foo }")
            assertEquals(mapOf("foo" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("foo"), error.path)
            assertTrue(error.message.contains("variable resolver boom"))
        }
    }
}
