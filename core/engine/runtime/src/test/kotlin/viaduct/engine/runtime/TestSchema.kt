package viaduct.engine.runtime

// language=GraphQL
val testSchema = """
    type Query {
        a: A
        b: B!
        c: C
        d: D
        i: I
        j: J
        k: K
        u: U
    }

    type Mutation {
        updateA(x: Int!): A
    }

    type Subscription {
        subscribeToA(x: String!): A
    }

    interface I {
        i1: String
        i2(a: String, b: String!, c: Int!): String
        i3: Boolean
        i4: A
    }

    interface J implements I {
        i1: String
        i2(a: String, b: String!, c: Int!): String
        i3: Boolean
        i4: A
        j1: String
        j2(a: String, b: String!, c: Int!): String
        j3: Boolean
    }

    interface K {
        k1: String
        k2: Int
    }

    union U = A | B | D

    type A {
        a1: String
            @privacy(customChecker: "com.airbnb.rewritertests.Aa1PolicyChecker")
        a2: Int
        a3(a: Int!, b: String!, c: [String!]!): Boolean
        a4: String!
        a5: Int!
        a6: Boolean!
        a7(x: String!, y: String!): I
        a8: String @resolver
        a9: B @resolver
        a10: [[U]]
    }

    type B implements I @privacy(customChecker: "com.airbnb.rewritertests.BPolicyChecker") {
        i1: String @privacy(delegateToParent: true)
        i2(a: String, b: String!, c: Int!): String @privacy(delegateToParent: true)
        i3: Boolean @privacy(delegateToParent: true)
        i4: A @privacy(delegateToParent: true)
        b1: Int @privacy(delegateToParent: true)
        b2: String! @privacy(delegateToParent: true) @resolver
        b3: A @privacy(delegateToParent: true)
        b4(a: String, b: Int): A @resolver
        b5(x: Int): String
    }

    type C implements I & J {
        i1: String
        i2(a: String, b: String!, c: Int!): String
        i3: Boolean
        i4: A
        j1: String
        j2(a: String, b: String!, c: Int!): String @privacy(customChecker: "com.airbnb.rewritertests.Cj2PolicyChecker")
        j3: Boolean
        c1: String
        c2: Int!
        c3: Boolean
    }

    type D implements I & K {
        i1: String
        i2(a: String, b: String!, c: Int!): String
        i3: Boolean
        i4: A
        k1: String
        k2: Int
        d1: String
        d2: Int! @privacy(customChecker: "com.airbnb.rewritertests.Dd2PolicyChecker")
        d3: Boolean
        d4: D
        d5: E
    }

    enum E {
        E1
        E2
        E3
     }

    schema {
        query: Query
        mutation: Mutation
        subscription: Subscription
    }

    directive @privacy(
        customChecker: String
        delegateToParent: Boolean
    ) on OBJECT| FIELD_DEFINITION

    directive @resolver on FIELD_DEFINITION
"""
