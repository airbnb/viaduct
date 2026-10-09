package viaduct.java.runtime.featureapp.mutationcapability;

import viaduct.api.testing.TestSchema;
import viaduct.java.api.testing.FeatureAppTestContractBase;

@TestSchema(
    """
    extend type Query {
      attemptString: Int @resolver
      attemptAnnotated: Int @resolver
    }
    extend type Mutation {
      increment: Int @resolver
      nested: Int @resolver
      payload: Payload @resolver
      operations: Operations
    }
    type Operations @namespaceType {
      deeper: DeepOperations
    }
    type DeepOperations @namespaceType {
      run: Int @resolver
    }
    type Payload {
      attempt: Int @resolver
    }
    type UnsafeNode implements Node @resolver {
      id: ID!
      value: String
    }
    """)
public abstract class MutationCapabilityContract extends FeatureAppTestContractBase {}
