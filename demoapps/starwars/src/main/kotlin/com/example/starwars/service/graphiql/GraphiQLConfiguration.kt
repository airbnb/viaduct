package com.example.starwars.service.graphiql

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse

/**
 * Minimal GraphiQL Web interface to interact with the Viaduct-powered GraphQL API.
 *
 * This router function serves the GraphiQL interface at the http://localhost:8080/graphiql endpoint.
 */
@Configuration
class GraphiQLConfiguration {
    @Bean
    @Order(0)
    fun graphiQlRouterFunction(): RouterFunction<ServerResponse> {
        val resource = ClassPathResource("graphiql/index.html")
        return RouterFunctions.route()
            .GET("/graphiql") { _ ->
                ServerResponse.ok().body(resource)
            }
            .build()
    }
}
