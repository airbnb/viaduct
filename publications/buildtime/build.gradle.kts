plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Build Time Tools")
    description.set("Fat jar bundling Viaduct build-time code generation tools for plugin tool classpaths")
}

dependencies {
    api(libs.viaduct.tenant.codegen)
}
