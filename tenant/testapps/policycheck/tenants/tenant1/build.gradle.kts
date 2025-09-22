plugins {
    id("conventions.kotlin-without-tests")
    id("test-tenant")
}

viaductTenant {
    create("tenant1") {
        packageName.set("viaduct.testapps.policycheck")
        schemaDirectory("${project.rootDir}/tenant/testapps/policycheck/tenants/tenant1/src/main/resources/viaduct/testapps/policycheck/tenant1")
        schemaProjectPath.set(":tenant:testapps:policycheck")
        schemaName.set("policycheck")
    }
}

dependencies {
    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.tenant.runtime)
    implementation(libs.graphql.java)
}
