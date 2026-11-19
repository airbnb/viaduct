package conventions

import org.gradle.api.GradleException

/** Reject cached toolchains that predate the Java4Shell and compromised-CA fixes. */
fun requirePatchedJdk(
    runtimeVersion: String,
    installation: String
) {
    val version = Runtime.Version.parse(runtimeVersion)
    val minimum = when (version.feature()) {
        17 -> "17.0.21.1+2"
        21 -> "21.0.13.1+2"
        25 -> "25.0.5.1+2"
        else -> throw GradleException(
            "Unsupported build JDK $runtimeVersion at $installation; use patched JDK 17, 21, or 25."
        )
    }
    if (version < Runtime.Version.parse(minimum)) {
        throw GradleException(
            "Unpatched build JDK $runtimeVersion at $installation; install JDK $minimum or later " +
                "in this release line for the Java4Shell and compromised-CA fixes. " +
                "Gradle's daemon JDK and testJavaVersion do not upgrade installed toolchains."
        )
    }
}
