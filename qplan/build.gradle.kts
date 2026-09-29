plugins {
    id("buildroot.orchestration")
}

data class DocumentationLabel(
    val kind: String,
    val label: String,
    val file: File,
    val line: Int,
)

val checkDocumentationLabels =
    tasks.register("checkDocumentationLabels") {
        group = "verification"
        description = "Checks claim and invariant labels and writes their generated catalog."

        val documentationFiles =
            fileTree(rootDir) {
                include("claims.md")
                include("**/*.kt")
                exclude("**/build/**")
                exclude("**/.gradle/**")
                exclude("**/.kotlin/**")
            }
        val catalogFile =
            layout.buildDirectory.file("reports/documentation-labels.txt")

        inputs.files(documentationFiles)
        outputs.file(catalogFile)

        doLast {
            val labelPattern = "[a-z0-9]+(?:-[a-z0-9]+)*"
            val claimPattern = Regex("""^\*\*\[($labelPattern)]\*\* .+""")
            val invariantPattern =
                Regex("""^\s*\*\s+### Invariant: ($labelPattern)\s*$""")
            val invariantHeadingPattern = Regex("""### Invariants?\b""")
            val markdownHeadingPattern = Regex("""^\s*\*\s+#{1,6}\s+.+$""")
            val kdocTagPattern = Regex("""^\s*\*\s+@\w+.*$""")
            val labels = mutableListOf<DocumentationLabel>()
            val errors = mutableListOf<String>()

            val claimsFile = rootProject.file("claims.md")
            claimsFile.readLines().forEachIndexed { index, line ->
                val match = claimPattern.matchEntire(line)
                if (match != null) {
                    labels +=
                        DocumentationLabel(
                            kind = "claim",
                            label = match.groupValues[1],
                            file = claimsFile,
                            line = index + 1,
                        )
                } else if (line.startsWith("**[")) {
                    errors +=
                        "${claimsFile.relativeTo(rootDir)}:${index + 1}: malformed claim declaration"
                }
            }

            documentationFiles
                .filter { it.extension == "kt" }
                .forEach { file ->
                    val lines = file.readLines()
                    var inKdoc = false
                    var seenKdocTag = false
                    lines.forEachIndexed { index, line ->
                        if (line.contains("/**")) {
                            inKdoc = true
                            seenKdocTag = false
                        }

                        val match = invariantPattern.matchEntire(line)
                        if (match != null) {
                            if (!inKdoc) {
                                errors +=
                                    "${file.relativeTo(rootDir)}:${index + 1}: invariant heading is not in KDoc"
                            }
                            if (seenKdocTag) {
                                errors +=
                                    "${file.relativeTo(rootDir)}:${index + 1}: invariant follows a KDoc tag"
                            }
                            labels +=
                                DocumentationLabel(
                                    kind = "invariant",
                                    label = match.groupValues[1],
                                    file = file,
                                    line = index + 1,
                                )

                            val bodyExists =
                                lines
                                    .drop(index + 1)
                                    .takeWhile {
                                        !it.contains("*/") &&
                                            !markdownHeadingPattern.matches(it) &&
                                            !kdocTagPattern.matches(it)
                                    }.any { bodyLine ->
                                        bodyLine
                                            .replaceFirst(Regex("""^\s*\*\s?"""), "")
                                            .isNotBlank()
                                    }
                            if (!bodyExists) {
                                errors +=
                                    "${file.relativeTo(rootDir)}:${index + 1}: invariant has no body"
                            }
                        } else if (invariantHeadingPattern.containsMatchIn(line)) {
                            errors +=
                                "${file.relativeTo(rootDir)}:${index + 1}: malformed invariant heading"
                        }

                        if (inKdoc && kdocTagPattern.matches(line)) seenKdocTag = true
                        if (line.contains("*/")) {
                            inKdoc = false
                            seenKdocTag = false
                        }
                    }
                }

            labels
                .groupBy { it.label }
                .filterValues { it.size > 1 }
                .forEach { (label, occurrences) ->
                    errors +=
                        "duplicate documentation label '$label': " +
                        occurrences.joinToString { occurrence ->
                            "${occurrence.file.relativeTo(rootDir)}:${occurrence.line}"
                        }
                }

            if (errors.isNotEmpty()) {
                throw GradleException(errors.sorted().joinToString("\n"))
            }

            val output = catalogFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(
                labels
                    .sortedBy { it.label }
                    .joinToString(separator = "\n", postfix = "\n") {
                        "${it.label}\t${it.kind}\t${it.file.relativeTo(rootDir)}:${it.line}"
                    },
            )
        }
    }

tasks.named("check") {
    dependsOn(checkDocumentationLabels)
}

subprojects {
    plugins.withId("java-test-fixtures") {
        dependencies.add("testFixturesImplementation", libs.junit)
    }

    tasks.matching { it.name == "check" || it.name == "test" }.configureEach {
        dependsOn(rootProject.tasks.named("checkDocumentationLabels"))
    }
}
