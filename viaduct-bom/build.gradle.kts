plugins {
    `java-platform`
    `maven-publish`
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    name.set("Bill of Materials")
    description.set("The Viaduct BOM module holds the dependency constraints for all Viaduct modules.")
}

dependencies {
    constraints {
        // Engine modules
        api("com.airbnb.viaduct:engine-api:${version}")
        api("com.airbnb.viaduct:engine-runtime:${version}")
        api("com.airbnb.viaduct:engine-wiring:${version}")

        // Service modules
        api("com.airbnb.viaduct:service-api:${version}")
        api("com.airbnb.viaduct:service-runtime:${version}")
        api("com.airbnb.viaduct:service-wiring:${version}")

        // Tenant modules
        api("com.airbnb.viaduct:tenant-api:${version}")
        api("com.airbnb.viaduct:tenant-runtime:${version}")

        // Shared modules
        api("com.airbnb.viaduct:shared-apiannotations:${version}")
        api("com.airbnb.viaduct:shared-arbitrary:${version}")
        api("com.airbnb.viaduct:shared-dataloader:${version}")
        api("com.airbnb.viaduct:shared-utils:${version}")
        api("com.airbnb.viaduct:shared-logging:${version}")
        api("com.airbnb.viaduct:shared-deferred:${version}")
        api("com.airbnb.viaduct:shared-graphql:${version}")
        api("com.airbnb.viaduct:shared-viaductschema:${version}")
        api("com.airbnb.viaduct:shared-invariants:${version}")
        api("com.airbnb.viaduct:shared-codegen:${version}")
        api("com.airbnb.viaduct:shared-mapping:${version}")

        // Snipped modules
        api("com.airbnb.viaduct:snipped-errors:${version}")
    }

    // codegen
    constraints {
        api("com.airbnb.viaduct:tenant-codegen:${version}")
    }

    // Test fixtures
    constraints {
        api("com.airbnb.viaduct:engine-api:${version}:test-fixtures")
        api("com.airbnb.viaduct:engine-runtime:${version}:test-fixtures")
        api("com.airbnb.viaduct:service-api:${version}:test-fixtures")
        api("com.airbnb.viaduct:tenant-api:${version}:test-fixtures")
        api("com.airbnb.viaduct:tenant-runtime:${version}:test-fixtures")
    }
}
