import viaduct.gradle.internal.repoRoot
import viaduct.graphiql.GraphiQLHtmlCustomizer

plugins {
    id("conventions.kotlin")
    id("conventions.dokka")
}

viaductPublishing {
    name.set("Viaduct Service Wiring")
    description.set("Bindings between the tenant and engine runtimes.")
}

dependencies {
    implementation(libs.viaduct.service.api)
    implementation(libs.graphql.java)


    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.tenant.wiring)
    implementation(libs.viaduct.shared.apiannotations)

    implementation(libs.viaduct.service.runtime)
    testImplementation(testFixtures(libs.viaduct.engine.api))
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(repoRoot().dir("docs/static/apis/service"))
        includes.from(layout.projectDirectory.file("module.md"))
    }
    pluginsConfiguration.html {
        customStyleSheets.from(repoRoot().file("docs/kdoc-service-styles.css"))
    }
}

/**
 * Regenerates the GraphiQL HTML from the official CDN example with Viaduct customizations.
 *
 * The generated file is committed to source control at src/main/resources/graphiql/index.html.
 * This task only needs to be run when upgrading GraphiQL or changing customizations.
 *
 * To upgrade GraphiQL:
 * 1. Update graphiqlGitTag below to the new release (e.g., "graphiql@5.3.0")
 * 2. Run: ./gradlew :core:service:service-wiring:generateGraphiQLHtml
 * 3. Review the changes and commit
 */
val generateGraphiQLHtml by tasks.registering {
    group = "generation"
    description = "Regenerate GraphiQL HTML from official repository (run manually when upgrading)"

    // GraphiQL release tag to use - update this to upgrade
    val graphiqlGitTag = "graphiql@5.2.1"

    val outputFile = layout.projectDirectory.file("src/main/resources/graphiql/index.html")

    doLast {
        val sourceUrl = "https://raw.githubusercontent.com/graphql/graphiql/$graphiqlGitTag/examples/graphiql-cdn/index.html"

        logger.lifecycle("Downloading GraphiQL HTML from: $sourceUrl")
        logger.lifecycle("Applying Viaduct customizations...")

        val customizer = GraphiQLHtmlCustomizer(
            sourceUrl = sourceUrl,
            outputFile = outputFile.asFile
        )
        customizer.customize()

        logger.lifecycle("")
        logger.lifecycle("GraphiQL HTML generated at: ${outputFile.asFile}")
        logger.lifecycle("Based on GraphiQL release: $graphiqlGitTag")
        logger.lifecycle("")
        logger.lifecycle("Remember to commit the generated file to source control.")
    }
}
