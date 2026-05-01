import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("com.gradleup.shadow")
    `maven-publish`
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    name.set("Java GRT Codegen")
    description.set("Fat jar of the Viaduct Java GRT source code generator for plugin tool classpaths")
}

dependencies {
    api(libs.viaduct.javaapi.codegen)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
    configurations = listOf(project.configurations.compileClasspath.get())
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("org/jetbrains/**")
}

tasks.named<Jar>("jar") {
    enabled = false
}

configurations {
    named("apiElements") {
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
    named("runtimeElements") {
        outgoing {
            artifacts.clear()
            artifact(tasks.shadowJar)
        }
    }
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        pom.withXml {
            val deps = asNode().get("dependencies") as groovy.util.NodeList
            deps.forEach { (it as groovy.util.Node).parent().remove(it) }
        }
    }
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
