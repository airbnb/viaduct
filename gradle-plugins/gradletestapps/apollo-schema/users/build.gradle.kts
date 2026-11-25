plugins {
    id("conventions.kotlin")
    id("conventions.ksp")
    id("com.airbnb.viaduct.module-kotlin-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
}
