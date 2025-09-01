<p align="center">
  <a href="https://viaduct.airbnb.tech">
    <img src=".github/viaduct_logo.jpg" alt="Viaduct logo" width="200">
  </a> 
</p>
<p align="center">
    <b>Viaduct is a GraphQL-based system that provides a unified interface for accessing and interacting with any data source.</b>
</p>
<p align="center">
    See the <a href="https://viaduct.airbnb.tech/docs/">User Manual</a> for deployment instructions and end user documentation.
</p>
 
# Vision

Viaduct is an open source data-oriented service mesh. As an open source initiative, Viaduct is committed to fostering an inclusive and collaborative community where external developers can contribute, innovate, and help shape the future of data-oriented development. 

Three principles have guided Viaduct since day one and still anchor the project: a central schema served by hosted business logic via a re-entrant API.
* Central Schema: Viaduct serves a single, integrated schema connecting all of your domains across your company---the central schema.  While that schema is developed in a decentralized manner by many teams, it’s one, highly connected graph.
* Hosted Business Logic: Teams should host their business logic directly in Viaduct.  This runs counter to what many consider to be best practices in GraphQL, which is that GraphQL servers should be a thin layer over microservices that host the real business logic.  Viaduct is a serverless platform for hosting business logic, allowing developers to focus on writing business logic rather than on operational issues.
* Re-entrancy: At the heart of Viaduct's developer experience is what we call re-entrancy: Logic hosted on Viaduct composes with other logic hosted on Viaduct by issuing GraphQL fragments and queries.  Re-entrancy is crucial for maintaining modularity in a large codebase and avoiding classic monolith hazards.

This vision embodies our commitment to creating a thriving open source project that not only meets internal Airbnb needs but also provides value to the wider developer community in building powerful, scalable applications with ease and confidence.

## Development

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
  