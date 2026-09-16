plugins {
    `kotlin-dsl`
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(project(":common"))

    testImplementation(gradleTestKit())
    testImplementation(project(":application"))
    testImplementation(project(":settings"))
    testImplementation(libs.kotest.assertions.core.jvm)
}

gradlePlugin {
    isAutomatedPublishing = false

    plugins {
        create("viaductMetamodule") {
            id = "${libs.versions.pluginIdPrefix.get()}.metamodule-gradle-plugin"
            implementationClass = "viaduct.gradle.ViaductMetamodulePlugin"
        }
    }
}

viaductPublishing {
    name.set("Metamodule Gradle Plugin")
    description.set("Tenant-API-neutral Gradle infrastructure for Viaduct module projects.")
}
