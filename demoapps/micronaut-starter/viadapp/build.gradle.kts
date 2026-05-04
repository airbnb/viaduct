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
}
