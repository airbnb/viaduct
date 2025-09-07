plugins {
    kotlin("jvm") version "1.9.24" // TODO - why doesn't it work with "1.8.22"
    id("viaduct-application")
    application
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    implementation("com.airbnb.viaduct:runtime:0.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.9.10")
    implementation(project(":mymodule"))
}

application {
    mainClass.set("com.example.viadapp.ViaductApplicationKt")
}
