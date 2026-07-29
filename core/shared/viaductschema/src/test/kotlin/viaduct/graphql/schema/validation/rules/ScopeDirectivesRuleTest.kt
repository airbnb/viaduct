package viaduct.graphql.schema.validation.rules

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class ScopeDirectivesRuleTest {
    private val preamble = """
        scalar BackingData
        directive @backingData(class: String!) on FIELD_DEFINITION
        directive @namespaceType on OBJECT
        directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
        directive @tenantLocal on FIELD_DEFINITION
    """.trimIndent()

    private fun validate(sdl: String): List<SchemaValidationError> {
        val fullSdl = "$preamble\n$sdl"
        val typeRegistry = SchemaParser().parse(fullSdl)
        UnExecutableSchemaGenerator.makeUnExecutableSchema(typeRegistry)
        return SchemaValidator(listOf(listOf(ScopeDirectivesRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry(typeRegistry))
    }

    @Test
    fun `valid - scope directives are internally consistent`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                o1: O1
                o2: O2
                union: Union
            }

            interface Interface @scope(to: ["viaduct", "listing-block"]) {
                interfaceField: String
            }

            type O1 @scope(to: ["*"]) {
                f1: Int
            }

            extend type O1 @scope(to: ["viaduct"]) {
                f2: Int
            }

            type O2 @scope(to: ["viaduct", "viaduct:private", "viaduct:public"]) {
                f1: String
            }

            extend type O2 implements Interface @scope(to: ["viaduct:private"]) {
                interfaceField: String
            }

            union Union @scope(to: ["viaduct", "viaduct:public", "listing-block"]) = O1

            extend union Union @scope(to: ["viaduct:private", "viaduct", "viaduct:public"]) = O2
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - tenant-local fields may return scalars or BackingData`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                visible: String
                nullableScalar: String @tenantLocal
                nonNullScalar: Int! @tenantLocal
                scalarList: [Boolean!]! @tenantLocal
                backingData: BackingData @backingData(class: "MyData") @tenantLocal
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - tenant-local fields may not return non-scalar types`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                visible: String
                objectValue: ObjectValue @tenantLocal
                interfaceValue: InterfaceValue @tenantLocal
                unionValue: UnionValue @tenantLocal
                enumValue: EnumValue @tenantLocal
            }

            type ObjectValue {
                value: String
            }

            interface InterfaceValue {
                value: String
            }

            type InterfaceImplementation implements InterfaceValue {
                value: String
            }

            union UnionValue = ObjectValue | InterfaceImplementation

            enum EnumValue {
                VALUE
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.TENANT_LOCAL_FIELD_TYPE_NOT_SCALAR,
            ValidationErrorCodes.TENANT_LOCAL_FIELD_TYPE_NOT_SCALAR,
            ValidationErrorCodes.TENANT_LOCAL_FIELD_TYPE_NOT_SCALAR,
            ValidationErrorCodes.TENANT_LOCAL_FIELD_TYPE_NOT_SCALAR
        )
        errors.map { it.message } shouldContainExactlyInAnyOrder listOf(
            "Field Query.objectValue has @tenantLocal but returns non-scalar type ObjectValue. " +
                "@tenantLocal fields may only return scalar or BackingData types.",
            "Field Query.interfaceValue has @tenantLocal but returns non-scalar type InterfaceValue. " +
                "@tenantLocal fields may only return scalar or BackingData types.",
            "Field Query.unionValue has @tenantLocal but returns non-scalar type UnionValue. " +
                "@tenantLocal fields may only return scalar or BackingData types.",
            "Field Query.enumValue has @tenantLocal but returns non-scalar type EnumValue. " +
                "@tenantLocal fields may only return scalar or BackingData types."
        )
    }

    @Test
    fun `invalid - interface fields may not be tenant-local`() {
        val errors = validate(
            """
            interface Named @scope(to: ["viaduct"]) {
                name: String @tenantLocal
                description: String
            }

            type Query @scope(to: ["viaduct"]) {
                user: User
            }

            type User implements Named @scope(to: ["viaduct"]) {
                id: ID
                name: String
                description: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.TENANT_LOCAL_INTERFACE_FIELD
        )
        errors.single().message shouldBe
            "Field Named.name has @tenantLocal but is declared on an interface. " +
            "@tenantLocal is not allowed on interface fields."
    }

    @Test
    fun `invalid - object fields inherited from interfaces may not be tenant-local`() {
        val errors = validate(
            """
            interface Named @scope(to: ["viaduct"]) {
                name: String
            }

            type Query @scope(to: ["viaduct"]) {
                user: User
            }

            type User implements Named @scope(to: ["viaduct"]) {
                id: ID
                name: String @tenantLocal
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.TENANT_LOCAL_IMPLEMENTED_INTERFACE_FIELD
        )
        errors.single().message shouldBe
            "Field User.name has @tenantLocal but implements an interface field. " +
            "@tenantLocal is not allowed on fields inherited from interfaces."
    }

    @Test
    fun `invalid - child interface fields inherited from interfaces may not be tenant-local`() {
        val errors = validate(
            """
            interface Node @scope(to: ["viaduct"]) {
                id: ID
            }

            interface Resource implements Node @scope(to: ["viaduct"]) {
                id: ID @tenantLocal
                name: String
            }

            type Query @scope(to: ["viaduct"]) {
                resource: Resource
            }

            type Image implements Resource & Node @scope(to: ["viaduct"]) {
                id: ID
                name: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.TENANT_LOCAL_INTERFACE_FIELD,
            ValidationErrorCodes.TENANT_LOCAL_IMPLEMENTED_INTERFACE_FIELD
        )
    }

    @Test
    fun `invalid - extension scopes cannot expand base type scopes`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                o1: O1
            }

            type O1 @scope(to: ["viaduct"]) {
                f1: Int
            }

            extend type O1 @scope(to: ["viaduct", "viaduct:public"]) {
                f2: Int
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.SCOPE_EXTENSION_EXPANDS_BASE
        )
        errors.map { it.message } shouldContainExactly listOf(
            "Extension definition on type O1 cannot expand scope from [viaduct] to [viaduct, viaduct:public]"
        )
    }

    @Test
    fun `invalid - interface must have a field in every declared scope`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                value: String
            }

            interface IPresentationContainer
                @scope(to: ["viaduct", "viaduct:public", "viaduct:internal-tools"])

            extend interface IPresentationContainer @scope(to: ["viaduct"]) {
                data: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.OBJECT_OR_INTERFACE_SCOPE_WITHOUT_FIELDS,
            ValidationErrorCodes.OBJECT_OR_INTERFACE_SCOPE_WITHOUT_FIELDS
        )
        errors.map { it.message } shouldContainExactly listOf(
            "interface IPresentationContainer declares scope viaduct:public but has no fields in that scope",
            "interface IPresentationContainer declares scope viaduct:internal-tools but has no fields in that scope"
        )
    }

    @Test
    fun `invalid - object must have a field in every declared scope`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                object: O1
            }

            type O1 @scope(to: ["viaduct", "viaduct:public"])

            extend type O1 @scope(to: ["viaduct"]) {
                viaductOnly: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.OBJECT_OR_INTERFACE_SCOPE_WITHOUT_FIELDS
        )
        errors.map { it.message } shouldContainExactly listOf(
            "type O1 declares scope viaduct:public but has no fields in that scope"
        )
    }

    @Test
    fun `invalid - tenant-local fields do not satisfy scoped field requirement`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                object: O1
            }

            type O1 @scope(to: ["viaduct", "viaduct:public"]) {
                internalOnly: String @tenantLocal
            }

            extend type O1 @scope(to: ["viaduct"]) {
                viaductOnly: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.OBJECT_OR_INTERFACE_SCOPE_WITHOUT_FIELDS
        )
        errors.map { it.message } shouldContainExactly listOf(
            "type O1 declares scope viaduct:public but has no fields in that scope"
        )
    }

    @Test
    fun `valid - fields with out-of-scope return types satisfy scoped field requirement`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                parent: Parent
            }

            type Child @scope(to: ["viaduct"]) {
                value: String
            }

            type Parent @scope(to: ["viaduct", "viaduct:public"]) {
                child: Child
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - implements-only extension does not need to declare fields`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                object: O1
            }

            interface I1 @scope(to: ["viaduct"]) {
                name: String
            }

            type O1 @scope(to: ["viaduct", "viaduct:public"]) {
                name: String
            }

            extend type O1 implements I1 @scope(to: ["viaduct"])
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - union extension scopes must match union and member intersection`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                union: Union
            }

            type O1 @scope(to: ["viaduct", "viaduct:public"]) {
                f1: Int
            }

            type O2 @scope(to: ["viaduct"]) {
                f1: Int
            }

            type O3 @scope(to: ["listing-block"]) {
                f1: Int
            }

            union Union @scope(to: ["viaduct", "listing-block"]) = O1

            extend union Union @scope(to: ["viaduct:private", "viaduct", "listing-block"]) = O2 | O3
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.UNION_EXTENSION_SCOPE_INTERSECTION_MISMATCH,
            ValidationErrorCodes.UNION_EXTENSION_SCOPE_INTERSECTION_MISMATCH
        )
        errors.map { it.message } shouldContainExactly listOf(
            "Extension definition on union type Union has scopes [viaduct:private, viaduct, listing-block] that " +
                "is not the intersection of Union scopes [viaduct, listing-block] and O2 scopes [viaduct]",
            "Extension definition on union type Union has scopes [viaduct:private, viaduct, listing-block] that " +
                "is not the intersection of Union scopes [viaduct, listing-block] and O3 scopes [listing-block]"
        )
    }

    @Test
    fun `invalid - implemented interface extension scopes must be within record and interface intersection`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                o1: O1
            }

            interface I1 @scope(to: ["viaduct"]) {
                f1: Int
                f2: Boolean
            }

            type O1 @scope(to: ["viaduct", "viaduct:public", "listing-block"]) {
                f1: Int
            }

            extend type O1 implements I1 @scope(to: ["viaduct", "listing-block"]) {
                f2: Boolean
                f3: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.IMPLEMENTED_INTERFACE_EXTENSION_SCOPE_MISMATCH
        )
        errors.map { it.message } shouldContainExactly listOf(
            "Extension definition on O1 that implements interface I1 has scopes [viaduct, listing-block] that " +
                "is not a subset of the intersection of O1 scopes [viaduct, viaduct:public, listing-block] " +
                "and I1 scopes [viaduct]"
        )
    }

    @Test
    fun `invalid - fields required by implemented interfaces must be in implementation scopes`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                o1: O1
            }

            interface I1 @scope(to: ["viaduct", "viaduct:public", "user-block"]) {
                f1: Int
                f2: Boolean
                f3: String
            }

            type O1 @scope(to: ["viaduct", "viaduct:public", "listing-block"]) {
                f1: Int
            }

            extend type O1 implements I1 @scope(to: ["viaduct", "viaduct:public"]) {
                f2: Boolean
            }

            extend type O1 @scope(to: ["viaduct"]) {
                f3: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactly listOf(
            ValidationErrorCodes.IMPLEMENTED_INTERFACE_FIELD_SCOPE_MISSING
        )
        errors.map { it.message } shouldContainExactly listOf(
            "O1.f3 is required to implement I1, but is not in scope viaduct:public"
        )
    }

    @Test
    fun `valid - object extensions with scope declarations are accepted`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                user: User
            }

            type User @scope(to: ["viaduct"]) {
                name: String
            }

            extend type Query @scope(to: ["viaduct"]) {
                viewer: User
            }

            extend type User @scope(to: ["viaduct"]) {
                displayName: String
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - interface extensions with scope declarations are accepted`() {
        val errors = validate(
            """
            interface Node @scope(to: ["viaduct"]) {
                id: ID
            }

            interface User @scope(to: ["viaduct"]) {
                name: String
            }

            type Query @scope(to: ["viaduct"]) {
                guest: Guest
            }

            extend interface User implements Node @scope(to: ["viaduct"]) {
                id: ID
                displayName: String
            }

            type Guest implements User & Node @scope(to: ["viaduct"]) {
                id: ID
                name: String
                displayName: String
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - tenant-local-only object extensions do not require scope declarations`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                user: User
            }

            type User @scope(to: ["viaduct"]) {
                name: String
            }

            extend type Query {
                internalViewerId: ID @tenantLocal
            }

            extend type User {
                internalOnly: String @tenantLocal
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - tenant-local interface extension fields and their object implementations are rejected`() {
        val errors = validate(
            """
            interface User @scope(to: ["viaduct"]) {
                name: String
            }

            type Query @scope(to: ["viaduct"]) {
                guest: Guest
            }

            extend interface User {
                internalOnly: String @tenantLocal
            }

            type Guest implements User @scope(to: ["viaduct"]) {
                name: String
                internalOnly: String @tenantLocal
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.TENANT_LOCAL_INTERFACE_FIELD,
            ValidationErrorCodes.TENANT_LOCAL_IMPLEMENTED_INTERFACE_FIELD
        )
    }

    @Test
    fun `invalid - object extensions with non-tenant-local fields require scope declarations`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                user: User
            }

            type User @scope(to: ["viaduct"]) {
                name: String
            }

            extend type Query {
                viewer: User
            }

            extend type User {
                displayName: String
                internalOnly: String @tenantLocal
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING,
            ValidationErrorCodes.OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING
        )
        errors.map { it.message } shouldContainExactlyInAnyOrder listOf(
            "Extension definition on type Query must declare @scope because it adds non-tenant-local field(s): [viewer].",
            "Extension definition on type User must declare @scope because it adds non-tenant-local field(s): [displayName]."
        )
    }

    @Test
    fun `invalid - interface extensions with non-tenant-local fields require scope declarations`() {
        val errors = validate(
            """
            interface User @scope(to: ["viaduct"]) {
                name: String
            }

            type Query @scope(to: ["viaduct"]) {
                guest: Guest
            }

            extend interface User {
                displayName: String
            }

            type Guest implements User @scope(to: ["viaduct"]) {
                name: String
                displayName: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING
        )
        errors.map { it.message } shouldContainExactlyInAnyOrder listOf(
            "Extension definition on interface User must declare @scope because it adds non-tenant-local field(s): [displayName]."
        )
    }

    @Test
    fun `invalid - interface extensions adding implemented interfaces require scope declarations`() {
        val errors = validate(
            """
            interface Node @scope(to: ["viaduct"]) {
                id: ID
            }

            interface User @scope(to: ["viaduct"]) {
                name: String
            }

            type Query @scope(to: ["viaduct"]) {
                guest: Guest
            }

            extend interface User implements Node {
                id: ID
            }

            type Guest implements User & Node @scope(to: ["viaduct"]) {
                id: ID
                name: String
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING
        )
        errors.map { it.message } shouldContainExactlyInAnyOrder listOf(
            "Extension definition on interface User must declare @scope because it adds " +
                "non-tenant-local field(s): [id] and implemented interface(s): [Node]."
        )
    }

    @Test
    fun `invalid - namespace object extensions are not exempt from scope declarations`() {
        val errors = validate(
            """
            type Query @scope(to: ["viaduct"]) {
                userFactory: UserFactory
            }

            type UserFactory @namespaceType @scope(to: ["viaduct"]) {
                create: String
            }

            extend type UserFactory {
                find: String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING
        errors[0].message shouldBe
            "Extension definition on type UserFactory must declare @scope because it adds non-tenant-local field(s): [find]."
    }
}
