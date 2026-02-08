[![Build and Test (Gradle)](https://github.com/airbnb/viaduct/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/airbnb/viaduct/actions/workflows/build-and-test.yml)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.airbnb.viaduct/bom)](https://central.sonatype.com/namespace/com.airbnb.viaduct)
[![Gradle Plugin Portal Version](https://img.shields.io/gradle-plugin-portal/v/com.airbnb.viaduct.application-gradle-plugin)](https://plugins.gradle.org/u/viaduct-maintainers)

<p align="center">
  <a href="https://viaduct.airbnb.tech/">
    <img src=".github/viaduct_logo.jpg" alt="Viaduct logo" width="200">
  </a>
</p>
<p align="center">
    <b>Viaduct is a GraphQL-based system that provides a unified interface for accessing and interacting with any data source.</b>
</p>
<p align="center">
    See the <a href="https://viaduct.airbnb.tech/docs/">User Manual</a> for deployment instructions and end user documentation.
</p>


> [!WARNING]
> The Viaduct engine is in production, at scale, at Airbnb where it has proven reliable. The developer API of Viaduct is under active development. In [our roadmap](https://viaduct.airbnb.tech/roadmap) we indicate which parts of the API are more or less subject to future change.  This is a good time to join the project and influence the direction that this API takes!

# Vision

Viaduct is an open source data-oriented service mesh. As an open source initiative, Viaduct is committed to fostering an inclusive and collaborative community where external developers can contribute, innovate, and help shape the future of data-oriented development.

Three principles have guided Viaduct since day one and still anchor the project: a central schema served by hosted business logic via a re-entrant API.
* Central Schema: Viaduct serves a single, integrated schema connecting all of your domains across your company---the central schema.  While that schema is developed in a decentralized manner by many teams, it’s one, highly connected graph.
* Hosted Business Logic: Teams should host their business logic directly in Viaduct.  This runs counter to what many consider to be best practices in GraphQL, which is that GraphQL servers should be a thin layer over microservices that host the real business logic.  Viaduct is a serverless platform for hosting business logic, allowing developers to focus on writing business logic rather than on operational issues.
* Re-entrancy: At the heart of Viaduct's developer experience is what we call re-entrancy: Logic hosted on Viaduct composes with other logic hosted on Viaduct by issuing GraphQL fragments and queries.  Re-entrancy is crucial for maintaining modularity in a large codebase and avoiding classic monolith hazards.

This vision embodies our commitment to creating a thriving open source project that not only meets internal Airbnb needs but also provides value to the wider developer community in building powerful, scalable applications with ease and confidence.

## Getting Started

To get you started with Viaduct, we have a created a number of small demonstration applications you can play with.  You can find these at [github.com/viaduct-dev](https://github.com/viaduct-dev).  To get started with the simplest of these, make a local clone of the [CLI starter](https://github.com/viaduct-dev/cli-starter):

```shell
git clone https://github.com/viaduct-dev/cli-starter.git
```

In the root of that cloned repo, type:

```shell
./gradlew -q run --args="'{ greeting }'"
```

and you should see:

```shell
{
  "data" : {
    "greeting" : "Hello, World!"
  }
}
```

To continue on from here, see our [Getting Started](https://viaduct.airbnb.tech/docs/getting_started/) guide.

## Contributing

Learn about development for Viaduct:

* [Contribution process](CONTRIBUTING.md)
* [Security policy](SECURITY.md)

Further information in the [contribution guide](CONTRIBUTING.md) includes different roles, like contributors, reviewers, and maintainers, related processes, and other aspects.

## Security

See the project [security policy](SECURITY.md) for
information about reporting vulnerabilities.

## Build requirements

* Mac OS X or Linux
* JDK 11+, 64-bit
