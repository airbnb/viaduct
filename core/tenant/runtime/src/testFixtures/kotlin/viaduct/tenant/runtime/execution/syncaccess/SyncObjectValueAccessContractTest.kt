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
}
