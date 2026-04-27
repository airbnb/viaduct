plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
}

tasks.register("publishPlugins") {
    dependsOn(":application:publishPlugins")
    dependsOn(":module:publishPlugins")
}
