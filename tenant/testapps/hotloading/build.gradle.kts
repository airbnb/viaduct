plugins {
    id("conventions.kotlin")
    id("test-app")
}

dependencies {
    implementation(project(":tenant:testapps:hotloading:schema"))

    testImplementation(libs.graphql.java)
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(project(":tenant:testapps:fixtures"))
    testImplementation(project(":tenant:testapps:hotloading:tenants:tenant1"))
    testImplementation(project(":tenant:testapps:hotloading:tenants:tenant2"))
}
