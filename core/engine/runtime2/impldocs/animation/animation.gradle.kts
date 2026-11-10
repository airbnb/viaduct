val animationSources = layout.projectDirectory.dir("impldocs/animation")
val animationDirectory = layout.buildDirectory.dir("animations")
val animationRecordings = layout.buildDirectory.dir("resolution/recordings")
val animationSourceSets = extensions.getByType<SourceSetContainer>()
val animationTestClass = "viaduct.engine.runtime2.resolution.ResolverContractTest"
val animationTest = providers.gradleProperty("animationTest").orElse(
    "$animationTestClass.omits unselected passive fields from selective resolver outputs",
)
val eastereggTests = listOf(
    "omits unselected passive fields from selective resolver outputs",
    "closes and orders transitive sibling resolver demand",
    "resolves descendant demand before its consuming sibling",
    "resolves recursive demand introduced by an object fragment",
    "query fragment resolution is transitive",
    "shared exact Query key retains distinct owner projections",
    "selective Query producer receives exact successor demand once",
    "chases node and root-field references in one publication",
    "chases a passive reference through an independent Query input",
).map { "$animationTestClass.$it" }

fun Test.recordAnimations(selectedTests: List<String>) {
    group = "development"
    testClassesDirs = animationSourceSets["test"].output.classesDirs
    classpath = animationSourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "2g"
    maxParallelForks = 1
    filter { selectedTests.forEach { includeTestsMatching(it) } }
    outputs.doNotCacheIf("Resolution recordings are untracked artifacts") { true }
    systemProperty("viaduct.runtime2.resolutionRecording.directory", animationRecordings.get().asFile.absolutePath)
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    doFirst {
        require(selectedTests.all { test -> test.none { it == '*' || it == '?' } }) {
            "Animation tests must identify ordinary @Test methods: class.method"
        }
    }
}

fun Exec.renderAnimations(selectedTests: List<String>) {
    group = "development"
    doFirst {
        commandLine(
            listOf(
                "python3",
                animationSources.file("animate.py").asFile.absolutePath,
                "--recordings",
                animationRecordings.get().asFile.absolutePath,
                "--output",
                animationDirectory.get().asFile.absolutePath,
            ) + selectedTests.flatMap { listOf("--test", it) },
        )
    }
}

fun Exec.serveAnimationGallery(heading: String) {
    group = "development"
    doFirst {
        val port = providers.gradleProperty("animationPort").orElse("8765").get().toInt()
        require(port in 1..65535) { "animationPort must be between 1 and 65535" }
        animationDirectory.get().asFile.mkdirs()
        logger.lifecycle(
            """
            |$heading: http://localhost:$port/
            |
            |If this build was executed on a remote workstation, run this command on your local machine:
            |
            |  ssh -L $port:localhost:$port -N <username>@<workstation>
            |
            |Replace <username> and <workstation> with your remote login details. Keep the SSH connection open, then open http://localhost:$port/ in your local browser.
            |
            |Press Ctrl-C in the Gradle terminal to stop the web server.
            """.trimMargin(),
        )
        commandLine(
            "python3",
            "-m",
            "http.server",
            port.toString(),
            "--bind",
            "127.0.0.1",
            "--directory",
            animationDirectory.get().asFile,
        )
    }
}

val recordAnimation = tasks.register<Test>("recordAnimation") {
    description = "Records the contract test selected by -PanimationTest=class.method (defaults to output projection)."
    recordAnimations(listOf(animationTest.get()))
}

tasks.register<Exec>("animate") {
    description = "Records one test and generates its HTML animation under build/animations (requires Python 3)."
    dependsOn(recordAnimation)
    renderAnimations(listOf(animationTest.get()))
}

tasks.register<Exec>("animationViewer") {
    description = "Serves build/animations at localhost:8765; override with -PanimationPort (requires Python 3)."
    serveAnimationGallery("Animation viewer")
}

val recordEasteregg = tasks.register<Test>("recordEasteregg") {
    description = "Records the supported debugger-walkthrough examples in one test execution."
    recordAnimations(eastereggTests)
}

val animateEasteregg = tasks.register<Exec>("animateEasteregg") {
    description = "Builds animations and the gallery for the debugger-walkthrough examples."
    dependsOn(recordEasteregg)
    renderAnimations(eastereggTests)
}

tasks.register<Exec>("easteregg") {
    description = "Records and animates debugger examples, then serves their gallery at localhost:8765."
    dependsOn(animateEasteregg)
    serveAnimationGallery("Find your easter egg")
}
