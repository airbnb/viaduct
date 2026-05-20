---
title: Schema Change Management
description: Best practices for managing schema changes in Viaduct.
---


## Schema Change Management

### Key Principles for Schema Changes

1. **Backward Compatibility:** Any schema change must ensure backward compatibility unless explicitly intended otherwise. Additive changes, such as adding new fields, are usually safe, while subtractive changes (like removing fields) often break client operations
2. **Hierarchy of Compatibility:** Schema changes fall into multiple categories based on compatibility:
    * **Wire & Compilation Breaking Changes:** These changes impact runtime behavior and client code compilation. For example, removing fields or changing field types can create runtime errors for existing clients
    * **Compilation-Only Breaking Changes:** These affect code generation and compilation without impacting runtime compatibility. An example is adding a new enum value
3. **Schema Evolution:** Schema changes are typically done within the constraints of the Directed Acyclic Graph (DAG) structure of schema modules. This ensures there are no circular dependencies between modules and maintains consistency during incremental updates
4. **Strict Validation:** Automated validations, such as CI checks, detect incompatible schema changes during the pull request phase. These validations reference existing operations and client versions to assess impact. TODO how would an architect set up such a CI job?
5. **Schema Freeze and Deprecation:** Fields being deprecated can be annotated with `@deprecated` tags and remain in the schema until older client versions are no longer in use.

### Examples of Practical Schema Updates:

1. Extending Types: Adding new fields to an existing type via `extend type`. For example:
    ```graphql
    extend type User {
      friends: [User]
    }
    ```

2. Deprecations: Annotating fields slated for removal with `@deprecated` and updating client-side queries to avoid referencing these fields
3. Modifications in Input Types: Converting an input type field from non-nullable (`String!`) to nullable (`String`) is considered safe

