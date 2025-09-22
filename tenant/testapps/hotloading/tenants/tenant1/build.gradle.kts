plugins {
    id("conventions.kotlin-without-tests")
    id("test-tenant")
}

viaductTenant {
    create("tenant1") {
        packageName.set("viaduct.testapps.hotloading")
        schemaDirectory("${project.rootDir}/tenant/testapps/hotloading/tenants/tenant1/src/main/resources/viaduct/testapps/hotloading/tenant1")
        schemaDirectory("${project.rootDir}/tenant/testapps/hotloading/tenants/tenant2/src/main/resources/viaduct/testapps/hotloading/tenant2")
        schemaProjectPath.set(":tenant:testapps:hotloading")
        schemaName.set("hotloading")
    }
}

dependencies {
    implementation(project(":tenant:tenant-api"))
    implementation(project(":tenant:tenant-runtime"))
    implementation(libs.graphql.java)
}
