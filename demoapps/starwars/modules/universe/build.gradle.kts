plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)  // Add this
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("universe")
}

dependencies {
    // Import Viaduct BOM for version management
    implementation(platform("com.airbnb.viaduct:viaduct-bom:${libs.versions.viaduct.get()}"))

    // When part of composite build: use individual Viaduct modules from repo
    // When standalone: use shaded jar from Maven Central
    if (gradle.parent != null) {
        // Use individual modules when part of composite build
        implementation("com.airbnb.viaduct:engine-api")
        implementation("com.airbnb.viaduct:engine-runtime")
        implementation("com.airbnb.viaduct:tenant-api")
        implementation("com.airbnb.viaduct:tenant-runtime")
    } else {
        // Use shaded jar when standalone
        implementation("com.airbnb.viaduct:viaduct-shaded::runtime")
    }

    implementation(libs.micronaut.inject)
    kapt(libs.micronaut.inject.java)
    kapt(libs.micronaut.inject.kotlin)
}
