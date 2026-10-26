plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-module")
}

tasks.register("publishPlugins") {
    dependsOn(":settings:publishPlugins")
    dependsOn(":conventions:publishPlugins")
    dependsOn(":application:publishPlugins")
    dependsOn(":module:publishPlugins")
    dependsOn(":module-java:publishPlugins")
}
