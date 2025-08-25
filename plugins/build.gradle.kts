plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

val isMavenLocal = gradle.parent?.startParameter?.taskNames?.any { it.contains("publishToMavenLocal", true) } ?: false
project.version = libs.versions.project.map { if (isMavenLocal) "$it-SNAPSHOT" else it }.get()

// These are the plugins we're publishing externally
// (viaduct-feature-app is not published: it's for internal testing purposes)
gradlePlugin {
    plugins {
        create("viaductSchema") {
            id = "viaduct-schema"
            implementationClass = "viaduct.gradle.schema.ViaductSchemaPlugin"
        }
        create("viaductClassDiff") {
            id = "viaduct-classdiff"
            implementationClass = "viaduct.gradle.classdiff.ViaductClassDiffPlugin"
        }
        create("viaductTenant") {
            id = "viaduct-tenant"
            implementationClass = "viaduct.gradle.tenant.ViaductTenantPlugin"
        }
        create("viaductApp") {
            id = "viaduct-app"
            implementationClass = "viaduct.gradle.app.ViaductAppPlugin"
        }
        create("viaductSettings") {
            id = "viaduct-settings"
            implementationClass = "viaduct.gradle.settings.ViaductSettingsPlugin"
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications {
        create<MavenPublication>("pluginJar") {
            from(components["java"])

            artifact(sourcesJar.get())

            artifactId = "plugins"
            version = project.version.toString()
        }
    }

    repositories {
        mavenLocal()
    }
}
