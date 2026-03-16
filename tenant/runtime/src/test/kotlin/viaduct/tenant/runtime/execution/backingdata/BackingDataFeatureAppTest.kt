@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.backingdata

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.backingdata.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.backingdata.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class BackingDataFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
    | #START_SCHEMA
    | #directive @visibility(level: String!) on FIELD_DEFINITION
    |
    | extend type Query {
    |  foo: Foo @resolver
    | }
    |
    | type Foo {
    |   iValue: Int @resolver
    |   sValue: String @resolver
    |   backingDataValue: BackingData
    |     #@visibility(level:"private")
    |     @resolver
    |     @backingData(class: "featureapps.example.BackingDataValue")
    | }
    | #END_SCHEMA
        """.trimMargin()

    // Tenant provided resolvers

    @Resolver
    class Query_FooResolver : QueryResolvers.Foo() {
        override suspend fun resolve(ctx: Context) = Foo.Builder(ctx).build()
    }

    @Resolver
    class Foo_BackingDataValueResolver : FooResolvers.BackingDataValue() {
        // In real life this function would likely reach out to an external
        // service that would return a complex object whose content could be
        // picked apart by resolves for various non-private fields.
        override suspend fun resolve(ctx: Context) = EXPECTED_BACKING_DATA
    }

    @Resolver("backingDataValue")
    class Foo_IValueResolver : FooResolvers.IValue() {
        override suspend fun resolve(ctx: Context) = ctx.objectValue.get<BackingDataValue>("backingDataValue", BackingDataValue::class).i
    }

    @Resolver(
        """
        fragment _ on Foo {
            backingDataValue
         }
        """
    )
    class Foo_SValueResolver : FooResolvers.SValue() {
        override suspend fun resolve(ctx: Context) = ctx.objectValue.get<BackingDataValue>("backingDataValue", BackingDataValue::class).s
    }

    // Test Cases Start here

    @Test
    fun `Backing data is resolved and available to other resolvers`() {
        execute(
            query =
                """
                query backing_data_resolved_available_to_other_resolvers {
                    foo {
                      sValue
                      iValue
                    }
                 }
                """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
                    "iValue" to EXPECTED_BACKING_DATA.i
                    "sValue" to EXPECTED_BACKING_DATA.s
                }
            }
        }
    }

    @Test
    fun `Resolver includes a backing data fields in its required selections validation error`() {
        execute(
            query = """
                    query TestQuery {
                        foo {
                            backingDataValue {
                                iValue,
                                sValue
                            }
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Validation error (SubselectionNotAllowed@[foo/backingDataValue]) : Subselection not allowed on leaf type " +
                        "'BackingData' of field 'backingDataValue'"
                    "locations" to arrayOf(
                        {
                            "line" to 3
                            "column" to 9
                        }
                    )
                    "extensions" to {
                        "classification" to "ValidationError"
                    }
                }
            )
            "data" to null
        }
    }

    @Test
    fun `Resolver includes a backing data type in its required selections serialize error`() {
        execute(
            query = """
                    query TestQuery {
                        foo {
                            backingDataValue
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "serialize should not be called for BackingData scalar type. This is a no-op."
                    "locations" to arrayOf(
                        {
                            "line" to 3
                            "column" to 9
                        }
                    )
                    "path" to listOf("foo", "backingDataValue")
                    "extensions" to {
                        "classification" to "VIADUCT_INTERNAL_ENGINE_EXCEPTION"
                    }
                }
            )
            "data" to {
                "foo" to {
                    "backingDataValue" to null
                }
            }
        }
    }

    companion object {
        val EXPECTED_BACKING_DATA =
            BackingDataValue(10, "Hello, World!")
    }
}

// Feature test app file specific test data
data class BackingDataValue(
    val i: Int,
    val s: String,
)
