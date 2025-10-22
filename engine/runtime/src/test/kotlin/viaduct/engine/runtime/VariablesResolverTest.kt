package viaduct.engine.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest

@ExperimentalCoroutinesApi
class VariablesResolverTest {
    @Test
    fun `variables provider -- const`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar(x: Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _ -> mapOf("varx" to 3) }
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
        MockTenantModuleBootstrapper("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { ctx -> mapOf("varx" to ctx.arguments.getAs<Int>("y") * 2) }
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
        MockTenantModuleBootstrapper("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _ -> mapOf("varx" to 2, "extra" to 3) }
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
        MockTenantModuleBootstrapper("extend type Query { foo: Int!, bar(x:Int): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _ -> mapOf("varx" to null) }
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
        MockTenantModuleBootstrapper("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$varx)") {
                        variables("varx") { _ -> emptyMap<String, Any?>() }
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
        MockTenantModuleBootstrapper("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:\$x)") {
                        variables("x") { _ -> mapOf("x" to 2) }
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
    fun `invalid variable reference`() =
        assertThrows<Exception> {
            MockTenantModuleBootstrapper("extend type Query { foo: Int!, bar(x:Int!): Int! }") {
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
