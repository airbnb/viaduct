plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
}

tasks.register("publishPlugins") {
    dependsOn(":plugins-application:publishPlugins")
    dependsOn(":plugins-module:publishPlugins")
}
