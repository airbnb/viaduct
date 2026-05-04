plugins {
    `java-library`
    kotlin("jvm")
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("resolvers")
}

dependencies {
    api(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    implementation(libs.logback.classic)
}
