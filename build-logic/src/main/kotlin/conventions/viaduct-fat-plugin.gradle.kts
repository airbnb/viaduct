package conventions

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// `bundled` holds viaduct.shared/* library deps whose classes are merged directly into the plugin
// JAR. Third-party transitives (graphql-java, kotlin, etc.) stay as explicit POM deps so
// consumers resolve them from Maven Central at their own version.
val bundled: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

configurations.named("implementation") {
    extendsFrom(configurations["bundled"])
}

// Merge viaduct.* classes from bundled deps into the plugin JAR.
// Using a plain FileCollection (not a lambda) avoids the configuration-cache restriction on
// capturing a script object reference.
val bundledViaductClasses: FileCollection = files(
    configurations.named("bundled").map { cfg ->
        cfg.map { jar -> zipTree(jar).matching { include("viaduct/**") } }
    }
)

// Suppress Gradle module metadata: viaduct.shared.* classes are bundled into this JAR and absent
// from the published POM, so the .module file would produce unresolvable coordinates. Gradle falls
// back to the POM, which is correct.
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

tasks.named<Jar>("jar") {
    from(bundledViaductClasses)
    duplicatesStrategy = DuplicatesStrategy.WARN
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/INDEX.LIST")
}

afterEvaluate {
    // Promote non-viaduct transitive deps of `bundled` to direct implementation deps so they
    // appear in the published POM. Only viaduct.* classes are merged into the JAR; third-party
    // classes live on the consumer's classpath via normal dependency resolution.
    configurations["bundled"].resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
        val group = artifact.moduleVersion.id.group
        if (!group.startsWith("com.airbnb.viaduct")) {
            dependencies.add(
                "implementation",
                "${group}:${artifact.name}:${artifact.moduleVersion.id.version}",
            )
        }
    }

    // Strip viaduct.shared.* from the published POM: those classes are bundled in this JAR and
    // have no separate Maven artifact for consumers to download.
    project.extensions.getByType(PublishingExtension::class.java)
        .publications.withType(MavenPublication::class.java).configureEach {
            pom.withXml {
                (asNode().get("dependencies") as groovy.util.NodeList)
                    .filterIsInstance<groovy.util.Node>()
                    .forEach { depsContainer ->
                        val toRemove = (depsContainer.children() as groovy.util.NodeList)
                            .filterIsInstance<groovy.util.Node>()
                            .filter { dep ->
                                val groupId = ((dep.get("groupId") as groovy.util.NodeList)
                                    .firstOrNull() as? groovy.util.Node)?.text() ?: ""
                                groupId.startsWith("com.airbnb.viaduct.shared")
                            }
                        toRemove.forEach { depsContainer.remove(it) }
                    }
            }
        }
}
