package conventions

import viaduct.gradle.internal.repoRoot
import java.net.URI
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
}

dokka {
    // Shared configuration for documented modules goes here
    moduleVersion.set(project.version.toString())
    moduleName.set(displayName(project))

    val repoRootProject = repoRoot()

    pluginsConfiguration.html {
        homepageLink = "https://airbnb.io/viaduct"
        customStyleSheets.from(repoRootProject.file("docs/kdoc-styles.css"))
        customAssets.from(repoRootProject.file("docs/assets/icons/logo-only-white.svg"))
        footerMessage = "&copy; 2025 Airbnb, Inc."
    }

    dokkaPublications.html {
        failOnWarning.set(true)
        suppressObviousFunctions.set(true)
        suppressInheritedMembers.set(true)
        outputDirectory.set(repoRootProject.dir("docs/static/apis/" + project.name))
    }

    dokkaPublications.javadoc {
        suppressObviousFunctions.set(true)
        suppressInheritedMembers.set(true)
    }

    dokkaSourceSets.configureEach {
        if (layout.projectDirectory.file("module.md").asFile.exists()) {
            includes.from(layout.projectDirectory.file("module.md"))
        }

        documentedVisibilities.set(
            setOf(
                VisibilityModifier.Public,
                VisibilityModifier.Protected,
            )
        )

        sourceLink {
            localDirectory.set(repoRootProject)
            remoteUrl.set(
                URI(
                    "https://github.com/airbnb/viaduct/tree/v" + project.version.toString()
                )
            )
            remoteLineSuffix.set("#L")
        }

        perPackageOption {
            matchingRegex.set(".*internal.*")
            suppress.set(true)
        }
    }
}

fun displayName(project: Project): String {
    return "Viaduct " + project.name
        .replace("api", "API")
        .replace("-", " ")
        .split(" ").joinToString(" ") { it.replaceFirstChar { it2 -> it2.uppercase() }  }
}
