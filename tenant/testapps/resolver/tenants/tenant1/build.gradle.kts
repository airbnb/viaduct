plugins {
    id("conventions.kotlin-without-tests")
    id("test-tenant")
}

viaductTenant {
    create("tenant1") {
        packageName.set("viaduct.testapps.resolver")
        schemaDirectory("${project.rootDir}/tenant/testapps/resolver/tenants/tenant1/src/main/resources/viaduct/testapps/resolver/tenant1")
        schemaDirectory("${project.rootDir}/tenant/testapps/resolver/tenants/tenant2/src/main/resources/viaduct/testapps/resolver/tenant2")
        schemaDirectory("${project.rootDir}/tenant/testapps/resolver/tenants/tenant3/src/main/resources/viaduct/testapps/resolver/tenant3")
        schemaProjectPath.set(":tenant:testapps:resolver")
        schemaName.set("resolver")
    }
}

dependencies {
    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.tenant.runtime)
    implementation(libs.graphql.java)
}
