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
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.toSchemaScopeInfo

val DEFAULT_SCOPE_ID = "default"
val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA_ID = SchemaId.Scoped("publicSchema", setOf(DEFAULT_SCOPE_ID))
val EXTRAS_SCHEMA_ID = SchemaId.Scoped("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))

/**
 *  Scans for all classes annotated with [Resolver] and registers them as Spring beans.
 */
class ResolverBeanDefinitionRegistrar : ImportBeanDefinitionRegistrar {
    override fun registerBeanDefinitions(
        importingClassMetadata: AnnotationMetadata,
        registry: BeanDefinitionRegistry
    ) {
        val scanner = ClassPathBeanDefinitionScanner(registry, false)

        // Add filter to include only classes annotated with @Resolver
        scanner.addIncludeFilter(AnnotationTypeFilter(Resolver::class.java))

        // Scan the base package where your resolvers are located
        scanner.scan("viaduct.demoapp")
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
            // Register two schemas: one with the "extras" scope and one without
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    DEFAULT_SCHEMA_ID.toSchemaScopeInfo(),
                    EXTRAS_SCHEMA_ID.toSchemaScopeInfo(),
                ),
                packagePrefix = "viaduct.demoapp", // Scan the entire viaduct.demoapp package for graphqls resources
                resourcesIncluded = ".*\\.graphqls"
            ),
            // The list of tenenats that we want to support
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "viaduct.demoapp", // Scan the entire viaduct.demoapp package for tenant-specific code
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
