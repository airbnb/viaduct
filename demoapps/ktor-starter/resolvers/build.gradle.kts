plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("resolvers")
    // Disable automatic BOM/dependency injection - we manage dependencies explicitly
    applyBOM.set(false)
}

dependencies {
    api(libs.viaduct.api)
    implementation(libs.viaduct.runtime)
}
