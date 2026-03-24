package viaduct.tenant.runtime.execution.mapping

import java.time.LocalDate
import java.time.Month
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.context.nodeFor
import viaduct.api.mapping.GRTDomain
import viaduct.api.mapping.JsonDomain
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.mapping.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.mapping.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.mapping.resolverbases.UserResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class MappingFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | extend type Query {
        |   "example resolver that converts from synchronous GRT data"
        |   syncGrtToJson: String! @resolver
        |
        |   "example resolver that parses synchronous input data to a GRT"
        |   inputJsonToGrt(json: String!): User! @resolver
        |
        |   user: User! @resolver
        | }
        |
        | type User implements Node @resolver {
        |     id: ID!
        |     name: String!
        |     birthYear: Int! @resolver
        |     dob: Date!
        | }
        | #END_SCHEMA
        """.trimMargin()

    @Suppress("unused")
    @Resolver
    class UserNodeResolver : NodeResolvers.User() {
        override suspend fun resolve(ctx: Context): User =
            User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, "1"))
                .name("Frodo Baggins")
                .dob(LocalDate.of(1954, Month.SEPTEMBER, 22))
                .build()
    }

    @Suppress("unused")
    @Resolver("fragment _ on User { dob }")
    class UserBirthYearResolver : UserResolvers.BirthYear() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.getDob().year
    }

    @Suppress("unused")
    @Resolver
    class QueryUserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User = ctx.nodeFor("1")
    }

    @Suppress("unused")
    @Resolver
    class QuerySyncGrtToJsonResolver : QueryResolvers.SyncGrtToJson() {
        override suspend fun resolve(ctx: Context): String {
            // configure an object mapper that transforms GRT values into Json strings
            val mapper = GRTDomain(ctx).mapperTo(JsonDomain(ctx))

            // create a synchronous GRT value
            val user = User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, "1"))
                .name("Frodo Baggins")
                .dob(LocalDate.of(1954, Month.SEPTEMBER, 22))
                .birthYear(1954)
                .build()

            // return the GRT transformed into a json string
            return mapper(user)
        }
    }

    @Suppress("unused")
    @Resolver
    class QueryInputJsonToGrtResolver : QueryResolvers.InputJsonToGrt() {
        override suspend fun resolve(ctx: Context): User {
            // configure an object mapper that transforms strings into GRT values
            val mapper = JsonDomain.forType(ctx, User.Reflection).mapperTo(GRTDomain(ctx))

            // return the result of mapping the json input value through the mapper
            return mapper(ctx.arguments.json) as User
        }
    }

    @Test
    fun `syncGrtToJson -- converts synchronous output values`() {
        execute("{ syncGrtToJson }")
            .assertEquals {
                "data" to {
                    "syncGrtToJson" to """{"id":"VXNlcjox","name":"Frodo Baggins","dob":"1954-09-22","birthYear":1954,"__typename":"User"}"""
                }
            }
    }

    @Test
    fun `inputJsonToGrt -- converts synchronous input values`() {
        execute(
            """
                query (${'$'}json: String!) {
                  inputJsonToGrt(json: ${'$'}json) {
                    id
                    name
                    dob
                    birthYear
                  }
                }
            """.trimIndent(),
            mapOf("json" to """{"id":"VXNlcjox","name":"Frodo Baggins","dob":"1954-09-22","birthYear":1954}""")
        ).assertEquals {
            "data" to {
                "inputJsonToGrt" to {
                    "id" to "VXNlcjox"
                    "name" to "Frodo Baggins"
                    "dob" to "1954-09-22"
                    "birthYear" to 1954
                }
            }
        }
    }
}
