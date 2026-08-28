package com.example.starwars.service.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

/** Keeps empty GraphQL introspection arrays distinct from null values. */
@Singleton
public final class JacksonConfig implements BeanCreatedEventListener<ObjectMapper> {
  @Override
  public ObjectMapper onCreated(BeanCreatedEvent<ObjectMapper> event) {
    ObjectMapper mapper = event.getBean();
    mapper.setDefaultPropertyInclusion(
        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
    return mapper;
  }
}
