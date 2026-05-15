plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("viadapp")
}

dependencies {
    api(libs.viaduct.api)
    implementation(project(":common"))

    // Micronaut DI: make resolvers available as beans for injection via MicronautTenantModuleBootstrapper
    ksp(libs.micronaut.inject.kotlin)
    implementation(libs.micronaut.inject)
}
