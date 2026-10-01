import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask

plugins {
    base
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    version.set("22.14.0")
    download.set(true)
}

val npmCi =
    tasks.register<NpmTask>("npmCi") {
        group = "build setup"
        description = "Installs the locked GraphQL specification renderer."
        args.set(listOf("ci"))

        inputs.files("package.json", "package-lock.json")
        outputs.dir("node_modules")
    }

val renderedSpec = layout.buildDirectory.file("spec/index.html")

val renderSpec =
    tasks.register<NpxTask>("renderSpec") {
        group = "documentation"
        description = "Renders the vendored GraphQL specification as HTML."
        dependsOn(npmCi)

        command.set("spec-md")
        args.set(
            listOf(
                "--metadata",
                "spec/metadata.json",
                "spec/GraphQL.md",
            ),
        )

        inputs.files(
            fileTree("spec") { include("**/*.md") },
            "spec/metadata.json",
            "LICENSE.md",
        )
        outputs.file(renderedSpec)

        lateinit var htmlOutput: java.io.OutputStream
        execOverrides {
            renderedSpec.get().asFile.parentFile.mkdirs()
            htmlOutput = renderedSpec.get().asFile.outputStream()
            standardOutput = htmlOutput
        }
        doLast {
            htmlOutput.close()
        }
    }

tasks.named("check") {
    dependsOn(renderSpec)
}

tasks.named("build") {
    dependsOn(renderSpec)
}
