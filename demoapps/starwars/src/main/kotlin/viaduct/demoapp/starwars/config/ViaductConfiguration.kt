package viaduct.demoapp.starwars.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.core.type.AnnotationMetadata
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import viaduct.api.Resolver
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.Viaduct

const val SCHEMA_ID = "publicSchema"
const val SCHEMA_ID_WITH_EXTRAS = "publicSchemaWithExtras"
const val EXTRAS_SCOPE_ID = "extras"

class ResolverBeanDefinitionRegistrar : ImportBeanDefinitionRegistrar {
    override fun registerBeanDefinitions(
        importingClassMetadata: AnnotationMetadata,
        registry: BeanDefinitionRegistry
    ) {
        val scanner = ClassPathBeanDefinitionScanner(registry, false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Resolver::class.java))
        scanner.scan("viaduct.demoapp.starwars.resolvers")
    }
}

@Configuration
@Import(ResolverBeanDefinitionRegistrar::class)
class ViaductConfiguration {
    @Autowired
    lateinit var codeInjector: SpringTenantCodeInjector

    @Bean
    fun viaductService(): Viaduct =
        BasicViaductFactory.create(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    SchemaScopeInfo(SCHEMA_ID, setOf("default")),
                    SchemaScopeInfo(SCHEMA_ID_WITH_EXTRAS, setOf("default", EXTRAS_SCOPE_ID))
                ),
                packagePrefix = "viaduct.demoapp.starwars",
                resourcesIncluded = ".*\\.graphqls"
            ),
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "viaduct.demoapp.starwars",
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
            .GET("/graphiql/") { _ ->
                ServerResponse.ok().body(resource)
            }
            .build()
    }
}
