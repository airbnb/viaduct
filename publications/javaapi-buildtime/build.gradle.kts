plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Java Build Time Tools")
    description.set("Fat jar bundling the Viaduct Java GRT code generator for plugin tool classpaths")
}

dependencies {
    api(libs.viaduct.javaapi.codegen)
}
