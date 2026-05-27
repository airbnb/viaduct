plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Build Time Tools")
    description.set("Fat jar bundling all Viaduct build-time tools (codegen and serve) for plugin tool classpaths")
}

dependencies {
    api(libs.viaduct.tenant.codegen)
}