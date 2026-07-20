package viaduct.tenant.runtime.execution.syncaccess

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for synchronous object and query value access via
 * [viaduct.api.context.FieldExecutionContext.getObjectValue] and
 * [viaduct.api.context.BaseFieldExecutionContext.getQueryValue].
 *
 * These methods provide synchronously-accessible versions of objectValue and queryValue
 * where all required selections have been eagerly resolved upfront.
 *
 * For each test the resolver doc-comment specifies the exact implementation required.
 */
@TestSchema(
    """
    extend type Query {
      "Return a Widget with x=42"
      widget: Widget @resolver
      "Return a Company with companyName=\"Airbnb\""
      company: Company @resolver
      "Return an Organization with name=\"Engineering\""
      organization: Organization @resolver
      "Return \"SyncConfig\""
      config: String @resolver
      multiplier: Int @resolver
    }

    type Widget implements Node {
      id: ID!
      x: Int
      "Use objectValueFragment(x) via getObjectValue; return \"Sync access: x=<x>\""
      xLabel: String @resolver
      "Use queryValueFragment(config) via getQueryValue; return \"Sync query access: config=<config>\""
      configLabel: String @resolver
      "Use objectValueFragment(x) and queryValueFragment(multiplier); return \"Sync combined: <x*multiplier>\""
      combined: String @resolver
    }

    type Container {
      "Return a Bar with value=\"NestedValue\""
      bar: Bar @resolver
      "Use objectValueFragment(bar { value }) via getObjectValue; return \"Sync nested: bar.value=<bar.value>\""
      label: String @resolver
    }

    type Company {
      parent: Organization @parent
      companyName: String!
      "Return a User"
      user: User @resolver
    }

    type Organization {
      name: String!
      "Return a Company with companyName=\"Airbnb\""
      company: Company @resolver
    }

    type User {
      parent: Company @parent
      "Use objectValueFragment(parent { companyName }) via getObjectValue; return the parent Company.companyName"
      parentCompanyName: String @resolver
      "Use objectValueFragment(parent { parent { name } }) via getObjectValue; return the grandparent Organization.name"
      parentOrganizationName: String @resolver
    }

    extend type Query {
      "Return a Container"
      container: Container @resolver
    }

    type Bar {
      value: String @resolver
    }
"""
)
abstract class SyncObjectValueAccessContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `getObjectValue returns data from objectValueFragment`() {
        execute("{ widget { xLabel } }").assertEquals {
            "data" to { "widget" to { "xLabel" to "Sync access: x=42" } }
        }
    }

    @Test
    fun `getQueryValue returns data from queryValueFragment`() {
        execute("{ widget { configLabel } }").assertEquals {
            "data" to { "widget" to { "configLabel" to "Sync query access: config=SyncConfig" } }
        }
    }

    @Test
    fun `getObjectValue and getQueryValue work together`() {
        execute("{ widget { combined } }").assertEquals {
            "data" to { "widget" to { "combined" to "Sync combined: 420" } }
        }
    }

    @Test
    fun `getObjectValue with nested object access`() {
        execute("{ container { label } }").assertEquals {
            "data" to { "container" to { "label" to "Sync nested: bar.value=NestedValue" } }
        }
    }

    @Test
    fun `getObjectValue with parent field accesses already fetched parent fields`() {
        execute("{ company { companyName user { parentCompanyName } } }").assertEquals {
            "data" to {
                "company" to {
                    "companyName" to "Airbnb"
                    "user" to { "parentCompanyName" to "Airbnb" }
                }
            }
        }
    }

    @Test
    fun `getObjectValue with parent field fetches parent fields missing from operation`() {
        execute("{ company { user { parentCompanyName } } }").assertEquals {
            "data" to { "company" to { "user" to { "parentCompanyName" to "Airbnb" } } }
        }
    }

    @Test
    fun `getObjectValue with nested parent field accesses already fetched grandparent fields`() {
        execute("{ organization { name company { user { parentOrganizationName } } } }").assertEquals {
            "data" to {
                "organization" to {
                    "name" to "Engineering"
                    "company" to {
                        "user" to { "parentOrganizationName" to "Engineering" }
                    }
                }
            }
        }
    }

    @Test
    fun `getObjectValue with nested parent field fetches grandparent fields missing from operation`() {
        execute("{ organization { company { user { parentOrganizationName } } } }").assertEquals {
            "data" to {
                "organization" to {
                    "company" to {
                        "user" to { "parentOrganizationName" to "Engineering" }
                    }
                }
            }
        }
    }
}
