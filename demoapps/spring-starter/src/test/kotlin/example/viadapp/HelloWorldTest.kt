package com.example.viadapp

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

@SpringBootTest(classes = [Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HelloWorldTest(
    @Autowired val restTemplate: TestRestTemplate
) {
    @Test
    fun `Query Hello World`() {
        val headers = HttpHeaders().apply {
            setAccept(listOf(MediaType.APPLICATION_JSON))
            setContentType(MediaType.APPLICATION_JSON)
        }

        val request = HttpEntity(
            """
            {
                "query":"
                    query HelloWorld {
                        greeting
                        author
                    }
                "
            }
            """.trimIndent().replace("\n", ""),
            headers
        )

        val response = restTemplate.postForEntity("/graphql", request, String::class.java)

        response.statusCode shouldBe HttpStatus.OK
        response.body!! shouldEqualJson """
            {
              "data": {
                "greeting": "Hello, World!",
                "author": "Brian Kernighan"
              }
            }
        """.trimIndent()
    }

    @Test
    fun `Error in Query Empty Body`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("accept", "application/json")
            set("content-type", "application/json")
        }

        val request = HttpEntity(
            """
                {
                    "query":"
                        |query HelloWorld { }
                    "
                }
            """.trimMargin().replace("\n", ""),
            headers
        )
        val response = restTemplate.postForEntity("/graphql", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        response.body!! shouldEqualJson
            """
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
            """
    }

    @Test
    fun `Error in Query With Non Existing Field`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("accept", "application/json")
            set("content-type", "application/json")
        }

        val request = HttpEntity(
            """
                {
                    "query":" "
                }
            """.trimIndent().replace("\n", ""),
            headers
        )

        val response = restTemplate.postForEntity("/graphql", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        response.body!! shouldEqualJson
            """
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
    fun `Error Missing Query`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("accept", "application/json")
            set("content-type", "application/json")
        }

        val request = HttpEntity(
            """
                {
                    "query":"
                        query HelloWorld {
                            thisIsNotAQuery
                        }
                    "
                    }
            """.trimIndent().replace("\n", ""),
            headers
        )

        val response = restTemplate.postForEntity("/graphql", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        response.body!! shouldEqualJson
            """
            {
              "errors": [
                {
                  "message": "Validation error (FieldUndefined@[thisIsNotAQuery]) : Field 'thisIsNotAQuery' in type 'Query' is undefined",
                  "locations": [
                    {
                      "line": 1,
                      "column": 39
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
    fun `Error Thrown from Tenant Resolver`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("accept", "application/json")
            set("content-type", "application/json")
        }

        val request = HttpEntity(
            """
            {
                "query":"
                    query ThrowException {
                        throwException
                    }
                "
            }
            """.trimIndent().replace("\n", ""),
            headers
        )

        val response = restTemplate.postForEntity("/graphql", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        response.body!! shouldEqualJson
            """
            {
              "errors": [
                {
                  "message": "java.lang.IllegalStateException: This is a resolver error",
                  "locations": [
                    {
                      "line": 1,
                      "column": 43
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
