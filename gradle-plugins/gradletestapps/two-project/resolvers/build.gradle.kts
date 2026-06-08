plugins {
    id("conventions.kotlin")
    id("conventions.ksp")
    id("com.airbnb.viaduct.module-gradle-plugin")
}

viaductModule {
    modulePackageSuffix.set("resolvers")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
}
