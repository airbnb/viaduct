plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Java Build Time Tools")
    description.set(
        "Fat jar bundling the Viaduct Java GRT code generator and registry-extractor annotation " +
            "processor for plugin tool and annotation-processor classpaths",
    )
}

dependencies {
    api(libs.viaduct.javaapi.codegen)

    // The registry-extractor APT, for javac's annotationProcessor path. Bundled rather than
    // depended on directly: its own dependencies (javaapi-api, tenant-codegen) are internal
    // coordinates that are not published individually.
    api(libs.viaduct.javaapi.registry.apt)
}
