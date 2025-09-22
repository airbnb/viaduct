plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
}

tasks.register("publishPlugins") {
    dependsOn(":application-plugin:publishPlugins")
    dependsOn(":module-plugin:publishPlugins")
}
