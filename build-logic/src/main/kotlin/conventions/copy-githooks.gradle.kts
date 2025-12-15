package conventions

import org.gradle.api.tasks.Copy
import viaduct.gradle.internal.repoRoot
import java.io.File

/**
 * Installs the versioned git pre-commit hook from `.github/hooks/pre-commit`
 * into the repository's `.git/hooks/pre-commit`.
 *
 * This convention is intended to be applied on the root project.
 */
tasks.register<Copy>("installGitHooks") {
    group = "git hooks"
    description = "Install the versioned pre-commit hook into .git/hooks."

    val repoRootDir: File = project.repoRoot().get().asFile
    val gitDir = File(repoRootDir, ".git")
    val gitHooksDir = File(gitDir, "hooks")
    val sourceHook = File(repoRootDir, ".github/hooks/pre-commit")

    from(sourceHook)
    into(gitHooksDir)

    onlyIf {
        gitDir.isDirectory && sourceHook.isFile
    }

    fileMode = "0775".toInt(8)

    outputs.upToDateWhen { false }

    doLast {
        logger.lifecycle(
            "[installGitHooks] Installed pre-commit hook to " +
                "${gitHooksDir.absolutePath}/pre-commit"
        )
    }
}

/**
 * Wire `installGitHooks` into typical entry points so that developers
 * do not need to remember running it manually. Any `build` or `preBuild`
 * task in any project will depend on the root `installGitHooks` task.
 */
allprojects {
    tasks.matching { it.name == "build" || it.name == "preBuild" }.configureEach {
        dependsOn(rootProject.tasks.named("installGitHooks"))
    }
}
