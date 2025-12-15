# Introduction

Thank you for considering contributing to Viaduct! We’re glad you’re here.

Following these guidelines helps to communicate that you respect the time of the developers managing and developing this open source project. In return, they should reciprocate that respect in addressing your issue, assessing changes, and helping you finalize your pull requests.

Viaduct is an open source project, and we love to receive contributions from our community — you! There are many ways to contribute, from writing tutorials or blog posts, improving the documentation, submitting bug reports and feature requests or writing code which can be incorporated into Viaduct itself.

Please use the discussion board for support questions.

# Ground Rules

* Adhere to our [code of conduct](https://airbnb.io/codeofconduct).
* Changes must be adequately documented and tested.
* Pull requests must use our PR template (excluding screenshot section).
* We welcome contributions ranging from tutorials to documentation improvements to bug reports and feature requests. We also welcome contributions to Viaduct itself.
* Major changes should first be discussed with an Issue. Be transparent and get community feedback.
* Pull requests should be as small as possible. Make one change at a time.
* At the same time, “gardening” is welcome. No need to make a separate pull request if you (eg) fix an unrelated typo or clear up a variable name along the way.
* By contributing to this repository, you agree that your contributions will be licensed under the Apache License, Version 2.0 ([LICENSE](LICENSE))

# Your First Contribution

Unsure where to begin contributing to Viaduct? You can start by looking through these beginner and help-wanted issues:

* Beginner issues - issues which should only require a few lines of code and a test or two.
* Help wanted issues - issues which are a bit more involved than beginner issues.
* Both issue lists are sorted by total number of comments. While not perfect, the number of comments is a reasonable proxy for the impact a given change will have.

At this point, you're ready to make your changes! Feel free to ask for help; everyone is a beginner at first.

If a maintainer asks you to "rebase" your PR, they're saying that a lot of code has changed, and that you need to update your branch so it's easier to merge.

# Getting started

1. Create your own fork of the code.
2. Do the changes in your fork.
3. Ensure your change includes tests and that all tests pass.
4. If you like the change and think the project could use it:
    * Be sure you have followed the code style for the project.
    * Note the Viaduct Code of Conduct.
    * Open a pull request against the main Viaduct repository.

# How to report a bug
## Security Disclosures
Any security issues should be submitted directly to the [Airbnb bug bounty program](https://hackerone.com/airbnb). In order to determine whether you are dealing with a security issue, ask yourself these two questions:

* Can I access something that's not mine, or something I shouldn't have access to?
* Can I disable something for other people?

If the answer to either of those two questions are "yes", then you're probably dealing with a security issue. Note that even if you answer "no" to both questions, you may still be dealing with a security issue, so if you're unsure, please direct questions to our bug bounty program.

## Filing an issue

When filing an issue, you will be asked to answer the following questions:
* Who is the bug affecting?
* What is affected by this bug?
* When does this occur?
* Where on the platform does it happen?
* How do we replicate the issue?
* What is the expected behavior?

# How to suggest a feature or enhancement

Start a [discussion](https://github.com/airbnb/viaduct/discussions) on GitHub which describes the feature you would like to see, why you need it, and how it should work. The community will discuss your idea there and reach consensus on a way forward.

# Code review process

The core team looks at Pull Requests on a regular basis in a weekly triage meeting.

After feedback has been given we expect responses within two weeks. After two weeks we may close the pull request if it isn't showing any activity.

## Branch rules

Pushes to `main` are not allowed. All changes must come through pull requests. Release tag creation is also restricted to repository maintainers.

# Community

If you have questions about the contribution process or want to discuss specific issues, please start a [discussion](https://github.com/airbnb/viaduct/discussions) on GitHub.

# Building Locally

## Plugins

To build and publish all Viaduct artifacts to your local Maven repository:

```bash
./gradlew publishToMavenLocal -x :tools:publishToMavenLocal
```

This will publish all Viaduct libraries and Gradle plugins to your local Maven repository. You can then use these artifacts in your own projects by adding `mavenLocal()` to your repositories list. The version of the artifacts will be `0.0.0-SNAPSHOT`.

## Binary compatibility validation (BCV)

Viaduct uses the Kotlin Binary Compatibility Validator Gradle plugin
(`org.jetbrains.kotlinx.binary-compatibility-validator`) to track and enforce
the public binary API of selected modules.

BCV works by generating and checking `.api` signature files for modules that
apply the BCV convention plugin `id("conventions.bcv-api")`, like `:tenant:api` and `:service:api`.

Developers should amend those `.api` files when making intentional executing :

```bash
./gradlew runApiDump
```

## Tests

Run tests with:

```bash
./gradlew check
```

### Demo Apps

To build and test demo apps:

```bash
./gradlew :cli-starter:build :cli-starter:test :starwars:build :starwars:test
```

# Release Process

Releases are listed [here](https://github.com/airbnb/viaduct/releases).

## Who is responsible for a release?

Releases can only be performed by Airbnb staff as the process uses internal Airbnb infrastructure. If you are an external contributor and would like to help with a release, please reach out to the core team via a GitHub discussion.

## Versioning

Viaduct adheres to [Semantic Versioning](https://semver.org/).

## Cadence

Until we release Viaduct 1.0, we are releasing on the following schedule:
* Publishing gradle plugin weekly on Monday at 10AM UTC as a new minor version.
* Publishing gradle plugin snapshots after each build using the time and the commit hash.
* Publishing patches when [PATCH] and [VIADUCT] are in the title of the pull request.

## Release Artifacts

Release artifacts are published to the Gradle Plugin Portal and Maven Central. Releases are performed by internal Airbnb infrastructure. See [gradle-plugin-major-release.yml](_infra/ci/jobs/gradle-plugin-major-release.yml), [gradle-plugin-snapshot-release.yml](_infra/ci/jobs/gradle-plugin-snapshot-publish.yml), and [gradle-plugin-weekly-release.yml](_infra/ci/jobs/gradle-plugin-weekly-release.yml) for current process.

### Maven Central

Runtime libraries are published to Maven Central. Artifacts are grouped under the [`com.airbnb.viaduct`](https://central.sonatype.com/namespace/com.airbnb.viaduct) group ID. The [Viaduct BOM](https://search.maven.org/artifact/com.airbnb.viaduct/bom) is the recommended way to manage versions of Viaduct dependencies.

### Gradle Plugin Portal

* [application-gradle-plugin](https://plugins.gradle.org/plugin/com.airbnb.viaduct.application-gradle-plugin)
* [module-gradle-plugin](https://plugins.gradle.org/plugin/com.airbnb.viaduct.module-gradle-plugin)

## Snapshots

Snapshots are only published to Maven Central. To use snapshots versions of the Gradle plugins, add the following to your `settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    maven {
      url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}
```
