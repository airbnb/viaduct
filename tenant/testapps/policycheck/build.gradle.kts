plugins {
    id("conventions.kotlin")
    id("test-app")
}

dependencies {
    implementation(project(":tenant:testapps:policycheck:schema"))

    testImplementation(libs.graphql.java)
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(project(":tenant:testapps:fixtures"))
    testImplementation(project(":tenant:testapps:policycheck:tenants:tenant1"))
}
