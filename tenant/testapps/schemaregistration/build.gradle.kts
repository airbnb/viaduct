plugins {
    id("conventions.kotlin")
    id("test-app")
}

dependencies {
    implementation(project(":tenant:testapps:schemaregistration:schema"))

    testImplementation(libs.graphql.java)
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.viaduct.engine.api)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(project(":tenant:testapps:fixtures"))
    testImplementation(project(":tenant:testapps:schemaregistration:tenants:tenant1"))
    testImplementation(project(":tenant:testapps:schemaregistration:tenants:tenant2"))
}
