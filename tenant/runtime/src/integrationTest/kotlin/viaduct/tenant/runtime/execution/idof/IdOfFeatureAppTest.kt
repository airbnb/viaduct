@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.idof

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.graphql.test.assertMatches
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class IdOfFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        |#START_SCHEMA
        | extend type Query {
        |   userFromInput(id: HostID): User @resolver
        |   userFromArgument(id: ID! @idOf(type: "User")): User @resolver
        |   entityFromID(id: ID! @idOf(type: "Entity")): Entity @resolver
        | }
        |
        | input HostID {
        |   id: ID! @idOf(type: "User")
        | }
        |
        | interface Entity implements Node {
        |   id: ID!
        |   lastModified: DateTime
        | }
        |
        | type User implements Entity & Node @resolver {
        |   id: ID!
        |   lastModified: DateTime
        |   name: String
        |   cohostID: ID @idOf(type: "User")
        |   cohost: User @resolver
        | }
        |
        | type BadType implements Node {
        |   id: ID!
        | }
        |
        | type BadEntityType implements Entity & Node {
        |   id: ID!
        |   lastModified: DateTime
        | }
        |#END_SCHEMA
    """.trimMargin()

    val aliceID = createGlobalIdString(User.Reflection, "alice@yahoo.com")
    val bobID = createGlobalIdString(User.Reflection, "bob@hotmail.com")
    val badID = createGlobalIdString(BadType.Reflection, "123")
    val badEntityID = createGlobalIdString(BadEntityType.Reflection, "123")

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
                    "message" to ".*viaduct.api.ViaductFrameworkException.*"
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
