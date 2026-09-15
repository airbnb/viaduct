package viaduct.tenant.runtime.execution.accessorforms

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals
import viaduct.graphql.test.assertMatches

/**
 * Contract test for the three generated GRT accessor forms, `getXxx`, `getXxxOrThrow` and
 * `getXxxOrNull`, plus their alias-taking overloads.
 *
 * The forms differ only in how a failed read is reported, so each resolver below reads one field
 * through one or more forms and reports what it saw. Reading a field the resolver's fragment left
 * out is a tenant bug rather than a data failure, so every form raises it, including `getXxxOrNull`.
 *
 * Resolver doc-comments specify required implementations.
 */
@TestSchema(
    """
    extend type Query {
      "Return a Widget"
      widget: Widget @resolver
    }

    type Widget {
      "Return \"widget\""
      name: String @resolver
      "Return null"
      nickname: String @resolver
      "Always fail"
      broken: String @resolver

      "Use objectValueFragment(nickname); read nickname through all three forms and join them with \"|\", rendering null as \"null\""
      nullReads: String @resolver
      "Use objectValueFragment(name); return getNicknameOrThrow(), reading a field the fragment omits"
      unselectedStrictRead: String @resolver
      "Use objectValueFragment(name); return getNicknameOrNull(), reading a field the fragment omits"
      unselectedSoftRead: String @resolver
      "Use objectValueFragment(alias: name); read the alias through all three forms and join them with \"|\""
      aliasedReads: String @resolver
      "Use objectValueFragment(alias: name); return getNameOrThrow(), reading the unaliased selection"
      unaliasedRead: String @resolver
      "Build a Widget with only name set; return getNicknameOrNull() on it"
      builderUnsetRead: String @resolver
      "Classify strict, default, and soft reads of resolver, stored-field, framework, and cancellation failures"
      failureReads: String! @resolver
      "Classify strict, default, and soft reads of invalid non-null, list, and object values"
      invalidValueReads: String! @resolver

      requiredName: String!
      tags: [String]
      strictTags: [String!]
      child: Child
      abstractChild: ValueNode
      children: [Child]
      other: Other
    }

    interface ValueNode {
      value: String
    }

    type Child implements ValueNode {
      value: String
    }

    type Other {
      value: String
    }
"""
)
abstract class AccessorFormsContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `every form returns null for a selected field whose value is null`() {
        execute("{ widget { nullReads } }").assertEquals {
            "data" to { "widget" to { "nullReads" to "null|null|null" } }
        }
    }

    @Test
    fun `every form reads an aliased selection`() {
        execute("{ widget { aliasedReads } }").assertEquals {
            "data" to { "widget" to { "aliasedReads" to "widget|widget|widget" } }
        }
    }

    @Test
    fun `strict read of a field outside the fragment fails`() {
        execute("{ widget { unselectedStrictRead } }").assertMatches {
            "data" to { "widget" to { "unselectedStrictRead" to null } }
            "errors" to arrayOf(
                {
                    "message" to ".*UnsetFieldException.*nickname.*"
                    "path" to listOf("widget", "unselectedStrictRead")
                }
            )
        }
    }

    @Test
    fun `soft read of a field outside the fragment fails as well`() {
        execute("{ widget { unselectedSoftRead } }").assertMatches {
            "data" to { "widget" to { "unselectedSoftRead" to null } }
            "errors" to arrayOf(
                {
                    "message" to ".*UnsetFieldException.*nickname.*"
                    "path" to listOf("widget", "unselectedSoftRead")
                }
            )
        }
    }

    @Test
    fun `unaliased read of an aliased selection fails`() {
        execute("{ widget { unaliasedRead } }").assertMatches {
            "data" to { "widget" to { "unaliasedRead" to null } }
            "errors" to arrayOf(
                {
                    "message" to ".*UnsetFieldException.*name.*"
                    "path" to listOf("widget", "unaliasedRead")
                }
            )
        }
    }

    @Test
    fun `soft read of a field the builder never set fails`() {
        execute("{ widget { builderUnsetRead } }").assertMatches {
            "data" to { "widget" to { "builderUnsetRead" to null } }
            "errors" to arrayOf(
                {
                    "message" to ".*UnsetFieldException.*nickname.*"
                    "path" to listOf("widget", "builderUnsetRead")
                }
            )
        }
    }

    @Test
    fun `the bare and soft forms return null only for data-side failures`() {
        execute("{ widget { failureReads } }").assertEquals {
            "data" to {
                "widget" to {
                    "failureReads" to
                        "resolver=FieldFetchingException,null,null;" +
                        "stored=ErroneousFieldException,null,null;" +
                        "wrapped=FrameworkException,null,null;" +
                        "framework=FrameworkException,FrameworkException,FrameworkException;" +
                        "cancellation=CancellationException,CancellationException,CancellationException;" +
                        "wrappedCancellation=CancellationException,CancellationException,CancellationException"
                }
            }
        }
    }

    @Test
    fun `invalid value classification is identical across every accessor form`() {
        execute("{ widget { invalidValueReads } }").assertEquals {
            "data" to {
                "widget" to {
                    "invalidValueReads" to
                        "nonNull=TenantUsageException,TenantUsageException,TenantUsageException;" +
                        "listElement=TenantUsageException,TenantUsageException,TenantUsageException;" +
                        "list=TenantUsageException,TenantUsageException,TenantUsageException;" +
                        "object=TenantUsageException,TenantUsageException,TenantUsageException;" +
                        "concreteType=FrameworkException,FrameworkException,FrameworkException;" +
                        "interfaceType=FrameworkException,FrameworkException,FrameworkException;" +
                        "objectListElement=FrameworkException,FrameworkException,FrameworkException"
                }
            }
        }
    }
}
