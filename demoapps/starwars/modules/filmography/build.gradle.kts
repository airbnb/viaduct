plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("filmography")
}

dependencies {
    // No additional dependencies needed for Micronaut
}
