plugins {
    id("conventions.kotlin")
    id("test-app")
}

dependencies {
    implementation(project(":tenant:testapps:resolver:schema"))

    testImplementation(libs.graphql.java)
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(project(":tenant:testapps:fixtures"))
    testImplementation(project(":tenant:testapps:resolver:tenants:tenant1"))
    testImplementation(project(":tenant:testapps:resolver:tenants:tenant2"))
    testImplementation(project(":tenant:testapps:resolver:tenants:tenant3"))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
}
