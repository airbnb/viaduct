package viaduct.tenant.runtime.execution.idof

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals
import viaduct.graphql.test.assertMatches
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Contract test for the @idOf directive.
 *
 * Defines the SDL and assertions for:
 * - @idOf on field arguments validates and decodes the global ID type
 * - @idOf on input type fields
 * - Polymorphic interface arguments with @idOf
 * - Error messages for syntactically-incorrect global IDs
 * - Error messages for non-entity type IDs passed to an entity interface resolver
 * - Error messages for wrong entity type IDs
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    extend type Query {
      "Decode global ID from input; for bobID return User(name=\"Bob\", cohost=Alice)"
      userFromInput(id: HostID): User @resolver
      "Decode global ID; for aliceID return User(id=aliceID, name=\"Alice\")"
      userFromArgument(id: ID! @idOf(type: "User")): User @resolver
      "Polymorphic: for aliceID return User(name=\"Alice\", cohost=Bob); for BadType ID throw \"Non-entity\"; for BadEntityType ID throw \"user entities\""
      entityFromID(id: ID! @idOf(type: "Entity")): Entity @resolver
    }

    input HostID {
      id: ID! @idOf(type: "User")
    }

    interface Entity implements Node {
      id: ID!
      lastModified: DateTime
    }

    type User implements Entity & Node @resolver {
      id: ID!
      lastModified: DateTime
      name: String
      cohostID: ID @idOf(type: "User")
      "Return the user's cohost: Alice→Bob, Bob→Alice"
      cohost: User @resolver
    }

    type BadType implements Node {
      id: ID!
    }

    type BadEntityType implements Entity & Node {
      id: ID!
      lastModified: DateTime
    }
"""
)
abstract class IdOfContractTest : KotlinFeatureAppTestContractBase() {
    private val codec = GlobalIDCodecDefault

    protected val aliceID: String get() = codec.serialize("User", "alice@yahoo.com")
    protected val bobID: String get() = codec.serialize("User", "bob@hotmail.com")
    protected val badID: String get() = codec.serialize("BadType", "123")
    protected val badEntityID: String get() = codec.serialize("BadEntityType", "123")

    @Test
    fun `idOf directive works when a valid user id is used`() {
        execute(
            query = """
                query {
                    userFromArgument(id: "$aliceID") {
                        id
                        name
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "userFromArgument" to {
                    "id" to aliceID
                    "name" to "Alice"
                }
            }
        }
    }

    @Test
    fun `id from input-type works`() {
        execute(
            query = """
                query {
                    userFromInput(id: { id: "$bobID" }) {
                        ...on User {
                            name
                            cohost {
                               name
                            }
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "userFromInput" to {
                    "name" to "Bob"
                    "cohost" to {
                        "name" to "Alice"
                    }
                }
            }
        }
    }

    @Test
    fun `polymorphic arguments work`() {
        execute(
            query = """
                query {
                    entityFromID(id: "$aliceID") {
                        ...on User {
                            name
                            cohost {
                               name
                            }
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "entityFromID" to {
                    "name" to "Alice"
                    "cohost" to {
                        "name" to "Bob"
                    }
                }
            }
        }
    }

    @Test
    fun `idOf directive throws the correct error message when a syntactically-incorrect global ID`() {
        execute(
            query = """
                query {
                    userFromArgument(id: "123") {
                        id
                        name
                    }
                }
            """.trimIndent()
        ).assertMatches {
            "data" to {
                "userFromArgument" to null
            }
            "errors" to arrayOf(
                {
                    "message" to ".*viaduct.errors.TenantUsageException.*"
                    "path" to listOf("userFromArgument")
                    "extensions" to {
                        "classification" to "DataFetchingException"
                    }
                }
            )
        }
    }

    @Test
    fun `polymorphic given non-entity`() {
        execute(
            query = """
                query {
                    entityFromID(id: "$badID") {
                        ...on User {
                            name
                            cohost {
                               name
                            }
                        }
                    }
                }
            """.trimIndent()
        ).assertMatches {
            "errors" to arrayOf(
                {
                    "message" to ".*IllegalArgumentException.*Non-entity.*"
                    "path" to listOf("entityFromID")
                }
            )
        }
    }

    @Test
    fun `polymorphic given non-user`() {
        execute(
            query = """
                query {
                    entityFromID(id: "$badEntityID") {
                        ...on User {
                            name
                            cohost {
                               name
                            }
                        }
                    }
                }
            """.trimIndent()
        ).assertMatches {
            "errors" to arrayOf(
                {
                    "message" to ".*IllegalArgumentException.*user entities.*"
                    "path" to listOf("entityFromID")
                }
            )
        }
    }
}
