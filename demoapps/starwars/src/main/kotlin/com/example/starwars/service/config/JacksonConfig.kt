package com.example.starwars.service.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.event.BeanCreatedEvent
import io.micronaut.context.event.BeanCreatedEventListener
import jakarta.inject.Singleton

/**
 * Configures Jackson ObjectMapper to ensure GraphQL introspection responses are spec-compliant.
 *
 * Problem: Micronaut's default Jackson configuration was converting empty arrays to null
 * in GraphQL introspection responses, which violates the GraphQL spec. The spec requires
 * fields like `directive.args` to be `[__InputValue!]!` (non-nullable array), but Micronaut
 * was returning `null` for directives with no arguments.
 *
 * Solution: Configure Jackson to use JsonInclude.Include.ALWAYS for empty arrays,
 * ensuring they are serialized as `[]` not `null`.
 */
@Singleton
class JacksonConfig : BeanCreatedEventListener<ObjectMapper> {
    override fun onCreated(event: BeanCreatedEvent<ObjectMapper>): ObjectMapper {
        val mapper = event.bean

        // Ensure empty arrays are serialized as [] not null
        // This is critical for GraphQL introspection which requires args to be a non-nullable array
        // Use NON_NULL to: include empty arrays (they're not null), exclude null values
        mapper.setDefaultPropertyInclusion(
            JsonInclude.Value.construct(
                JsonInclude.Include.NON_NULL,
                JsonInclude.Include.NON_NULL
            )
        )

        return mapper
    }
}
