package com.example.viadapp.injector

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import viaduct.service.BasicViaductFactory
import viaduct.service.TenantRegistrationInfo

@Configuration
class ViaductConfiguration {
    @Autowired
    lateinit var codeInjector: SpringTenantCodeInjector

    @Bean
    fun viaductService() =
        BasicViaductFactory.create(
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "com.example.viadapp",
                tenantCodeInjector = codeInjector
            )
        )

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
