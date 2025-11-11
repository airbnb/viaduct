// tag::gradle-config[37] build gradle of viaduct.
// tag::plugins-config[7] How plugins for viaduct are setup.
plugins {
    // Use a Kotlin version compatible with your Gradle version
    // Gradle 8.x: Kotlin 1.9.x or 2.x | Gradle 9.x: Kotlin 2.0+
    kotlin("jvm") version "1.9.24"
    alias(libs.plugins.viaduct.application)
    alias(libs.plugins.viaduct.module)
    application
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

viaductModule {
    modulePackageSuffix.set("resolvers")
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

    implementation("ch.qos.logback:logback-classic:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.9.10")

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("org.junit.platform:junit-platform-launcher:1.9.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
}

application {
    mainClass.set("com.example.viadapp.ViaductApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
