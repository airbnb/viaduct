plugins {
    id("conventions.viaduct-publishing") apply false
    id("buildroot.orchestration")
    id("buildroot.versioning")
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        pluginManager.apply("conventions.viaduct-publishing")
    }
}
