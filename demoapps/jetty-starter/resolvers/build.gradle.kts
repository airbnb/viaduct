plugins {
    `java-library`
    kotlin("jvm")
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("resolvers")
}

dependencies {
    implementation(libs.logback.classic)
}