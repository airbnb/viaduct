# Viaduct OSS Infrastructure Runbook

This document explains how to administer the infrastructure used by the
Viaduct OSS project.

## Github

Administration is handled by Airbnb's open source committee.

## CircleCI

We use Github Actions to run Viaduct's public [CI
jobs](https://github.com/airbnb/viaduct/actions).

## Gradle Plugin Portal

Plugins are published via the `viaduct-maintainers` account owned by
Airbnb. https://plugins.gradle.org/u/viaduct-maintainers

## Maven Central/Sonatype

Access to Airbnb's Sonatype namespace is controlled via Airbnb's Github
organization. Only members of the Airbnb Github organization can access
the namespace.

## Copybara

Viaduct has dual homes: Github and Airbnb's internal monorepo. We use
[Copybara](https://github.com/google/copybara) to sync changes between
the two source trees. Copybara runs on internal Airbnb infrastructure
and is not accessible to outside contributors.

