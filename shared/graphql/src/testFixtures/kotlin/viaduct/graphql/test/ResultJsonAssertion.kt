package viaduct.graphql.test

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import graphql.ExecutionResult
import kotlin.test.assertEquals

/**
 * Assert that this result serializes to same value as [expectedJson].
 *
 * @param expectedJson a JSON string. The string may use some short-hand conventions,
 *  including unquoted object keys, trailing commas, and comments
 */
fun ExecutionResult.assertJson(expectedJson: String) = this.toSpecification().assertJson(expectedJson)

fun Map<String, *>.assertJson(expectedJson: String) {
    val expected = try {
        mapper.readValue<Map<String, Any?>>(expectedJson)
    } catch (e: Exception) {
        throw IllegalArgumentException("Cannot parse expected JSON", e)
    }
    assertEquals(expected, this)
}

// configure an ObjectMapper that allows parsing compact JSON
private val mapper: ObjectMapper = ObjectMapper()
    .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
    .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
    .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
    .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
