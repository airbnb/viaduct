package viaduct.java.runtime.featureapp.tenantbootstrap;

import viaduct.api.testing.TestSchema;
import viaduct.java.runtime.featureapp.FeatureAppTestContractBase;

@TestSchema(
    """
    extend type Query {
      greeting: String @resolver
    }
    """)
public abstract class TenantBootstrapContract extends FeatureAppTestContractBase {}
