package com.example.viadapp

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class HelloWorldTest {
    @Test
    fun `Query Hello World`(): Unit =
        testApplication {
            application {
                module()
            }

            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(
                    """
                {
                    "query":"query HelloWorld { greeting author }"
                }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldEqualJson """
            {
              "data": {
                "greeting": "Hello, World!",
                "author": "Brian Kernighan"
              }
            }
            """.trimIndent()
        }

    @Test
    fun `Error in Query Empty Body`(): Unit =
        testApplication {
            application {
                module()
            }

            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(
                    """
                {
                    "query":"query HelloWorld { }"
                }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldEqualJson """
            {
              "errors": [
                {
                  "message": "Invalid syntax with offending token '}' at line 1 column 20",
                  "locations": [
                    {
                      "line": 1,
                      "column": 20
                    }
                  ],
                  "extensions": {
                    "classification": "InvalidSyntax"
                  }
                }
              ],
              "data": null
            }
            """.trimIndent()
        }

    @Test
    fun `Error in Query With Non Existing Field`(): Unit =
        testApplication {
            application {
                module()
            }

            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(
                    """
                {
                    "query":" "
                }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldEqualJson """
            {
              "errors": [
                {
                  "message": "Invalid syntax with offending token '<EOF>' at line 1 column 2",
                  "locations": [
                    {
                      "line": 1,
                      "column": 2
                    }
                  ],
                  "extensions": {
                    "classification": "InvalidSyntax"
                  }
                }
              ],
              "data": null
            }
            """.trimIndent()
        }

    @Test
    fun `Error Missing Query`(): Unit =
        testApplication {
            application {
                module()
            }

            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(
                    """
                {
                    "query":"query HelloWorld { thisIsNotAQuery }"
                }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldEqualJson """
            {
              "errors": [
                {
                  "message": "Validation error (FieldUndefined@[thisIsNotAQuery]) : Field 'thisIsNotAQuery' in type 'Query' is undefined",
                  "locations": [
                    {
                      "line": 1,
                      "column": 20
                    }
                  ],
                  "extensions": {
                    "classification": "ValidationError"
                  }
                }
              ],
              "data": null
            }
            """.trimIndent()
        }

    @Test
    fun `Error Thrown from Tenant Resolver`(): Unit =
        testApplication {
            application {
                module()
            }

            val response = client.post("/graphql") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(
                    """
                {
                    "query":"query ThrowException { throwException }"
                }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldEqualJson """
            {
              "errors": [
                {
                  "message": "java.lang.IllegalStateException: This is a resolver error",
                  "locations": [
                    {
                      "line": 1,
                      "column": 24
                    }
                  ],
                  "path": [
                    "throwException"
                  ],
                  "extensions": {
                    "fieldName": "throwException",
                    "parentType": "Query",
                    "operationName": "ThrowException",
                    "classification": "DataFetchingException"
                  }
                }
              ],
              "data": {
                "throwException": null
              }
            }
            """.trimIndent()
        }
}
