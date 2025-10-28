plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("universe")
}

dependencies {
    // No additional dependencies needed for Micronaut
}
