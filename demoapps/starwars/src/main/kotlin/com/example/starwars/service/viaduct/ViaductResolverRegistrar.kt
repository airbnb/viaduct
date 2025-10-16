package com.example.starwars.service.viaduct

import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import org.springframework.core.type.filter.AnnotationTypeFilter
import viaduct.api.Resolver

/**
 * Scans and registers all the classes annotated with @Resolver ( [Resolver] ) as beans for the StarWars demo.
 *
 * Adding [Resolver] to the dependency injector allows [SpringTenantCodeInjector] to find actual classes in the context.
 */
class ViaductResolverRegistrar : ImportBeanDefinitionRegistrar {
    override fun registerBeanDefinitions(
        importingClassMetadata: AnnotationMetadata,
        registry: BeanDefinitionRegistry
    ) {
        val scanner = ClassPathBeanDefinitionScanner(registry, false)

        // Include only classes annotated with [viaduct.api.Resolver]
        scanner.addIncludeFilter(AnnotationTypeFilter(Resolver::class.java))

        // Scan the base package com.example.starwars searching for tenant resolvers.
        scanner.scan("com.example.starwars")
    }
}
