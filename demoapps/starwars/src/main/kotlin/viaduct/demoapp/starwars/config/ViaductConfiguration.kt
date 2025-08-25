package viaduct.demoapp.starwars.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import org.springframework.core.type.filter.AnnotationTypeFilter
import viaduct.api.Resolver
import viaduct.service.api.Viaduct
import viaduct.service.runtime.StandardViaduct
import viaduct.service.runtime.ViaductSchemaRegistryBuilder
import viaduct.tenant.runtime.bootstrap.ViaductTenantAPIBootstrapper

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
        StandardViaduct.Builder()
            .withTenantAPIBootstrapperBuilder(
                ViaductTenantAPIBootstrapper.Builder()
                    .tenantCodeInjector(codeInjector)
                    .tenantPackagePrefix("viaduct.demoapp.starwars")
            )
            .withSchemaRegistryBuilder(
                ViaductSchemaRegistryBuilder()
                    .withFullSchemaFromResources("viaduct.demoapp.starwars", ".*\\.graphqls")
                    .registerScopedSchema(SCHEMA_ID, setOf("default"))
                    .registerScopedSchema(SCHEMA_ID_WITH_EXTRAS, setOf("default", EXTRAS_SCOPE_ID))
            ).build()
}
