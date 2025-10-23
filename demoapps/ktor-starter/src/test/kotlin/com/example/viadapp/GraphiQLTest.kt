package com.example.viadapp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class GraphiQLTest {
    @Test
    fun `GraphiQL endpoint returns HTML`(): Unit =
        testApplication {
            application {
                module()
            }

            val response = client.get("/graphiql")

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "<!doctype html>"
            body shouldContain "GraphiQL"
            body shouldContain "/graphql"
        }
}
