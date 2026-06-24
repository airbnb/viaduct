plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-module")
}

tasks.register("publishPlugins") {
    dependsOn(":application:publishPlugins")
    dependsOn(":module:publishPlugins")
}
