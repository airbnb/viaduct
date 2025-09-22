plugins {
    id("conventions.kotlin-without-tests")
    id("test-tenant")
}

viaductTenant {
    create("tenant2") {
        packageName.set("viaduct.testapps.schemaregistration")
        schemaDirectory(
            "${project.rootDir}/tenant/testapps/schemaregistration/tenants/tenant1/src/main/resources/viaduct/testapps/schemaregistration/tenant1"
        )
        schemaDirectory(
            "${project.rootDir}/tenant/testapps/schemaregistration/tenants/tenant2/src/main/resources/viaduct/testapps/schemaregistration/tenant2"
        )
        schemaProjectPath.set(":tenant:testapps:schemaregistration")
        schemaName.set("schemaregistration")
    }
}

dependencies {
    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.tenant.runtime)
    implementation(libs.graphql.java)
}
