import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.SigningExtension

// Apply standard Viaduct POM metadata to all Maven publications.
// pluginManager.withPlugin fires immediately if the plugin is already applied, or defers
// until it is — so this is order-independent with respect to plugin application.
// Inside the withPlugin callback `this` is AppliedPlugin, not Project, so we use
// project.extensions.getByType(...) to access project-level extensions explicitly.
pluginManager.withPlugin("maven-publish") {
    project.extensions.getByType(PublishingExtension::class.java)
        .publications.withType(MavenPublication::class.java).configureEach {
            pom {
                url.set("https://viaduct.airbnb.tech/")
                licenses { license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }}
                developers { developer {
                    id.set("airbnb"); name.set("Airbnb, Inc.")
                    email.set("viaduct-maintainers@airbnb.com")
                }}
                scm {
                    connection.set("scm:git:git://github.com/airbnb/viaduct.git")
                    developerConnection.set("scm:git:ssh://github.com/airbnb/viaduct.git")
                    url.set("https://github.com/airbnb/viaduct")
                }
            }
        }
}

// Configure in-memory PGP signing for all Viaduct publications.
val signingKeyId = findProperty("signingKeyId") as String?
val signingKey = findProperty("signingKey") as String?
val signingPassword = findProperty("signingPassword") as String?
pluginManager.withPlugin("signing") {
    val signingExt = project.extensions.getByType(SigningExtension::class.java)
    signingExt.useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    signingExt.setRequired { gradle.taskGraph.allTasks.any { it is PublishToMavenRepository } }
    pluginManager.withPlugin("maven-publish") {
        val publications = project.extensions.getByType(PublishingExtension::class.java).publications
        signingExt.sign(publications)
    }
}
