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

// Shape the published POM for the fat-plugin JAR:
// - strip viaduct.shared.* deps (those coordinates are not published; their classes are bundled)
// - promote their non-viaduct transitives as explicit runtime deps so consumers can resolve them
//
// afterEvaluate is required for timing: bundled(...) declarations in the applying project's
// dependencies block run after this convention plugin applies, so bundled cannot be resolved
// until evaluation completes. promotedDeps is captured as a plain List<Triple<String,String,String>>
// (config-cache serializable) so pom.withXml only does XML manipulation — no live Gradle objects
// are captured in the task action.
afterEvaluate {
    // NOTE: filters all com.airbnb.viaduct.* groups (not just .shared) because bundled classes
    // are included via the viaduct/** JAR merge — any viaduct transitive is also bundled.
    // Uses resolutionResult (not resolvedConfiguration): ResolvedComponentResult is a value type
    // the configuration cache can serialize; ResolvedConfiguration is not.
    val promotedDeps: List<Triple<String, String, String>> =
        configurations["bundled"].incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { !it.group.startsWith("com.airbnb.viaduct") }
            .map { Triple(it.group, it.name, it.version) }

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

                        promotedDeps.forEach { (group, name, version) ->
                            depsContainer.appendNode("dependency").apply {
                                appendNode("groupId", group)
                                appendNode("artifactId", name)
                                appendNode("version", version)
                                appendNode("scope", "runtime")
                            }
                        }
                    }
            }
        }
}
