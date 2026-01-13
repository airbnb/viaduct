package viaduct.graphql.schema.test

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import viaduct.graphql.schema.ViaductSchema

@Suppress("ktlint:standard:indent")
abstract class ViaductSchemaSubtypeContract {
    /** Override this with the ViaductSchema class you want to test. */
    abstract fun getSchemaClass(): KClass<*>

    /** If true, skips tests for `.extensions` fields, which are sometimes delegated
     *  without being wrapped.
     */
    open val skipExtensionTests: Boolean = false

    private val missingClasses = mutableListOf<String>()

    /** If your version of BridgeSchema doesn't implement its classes in the
     *  standard manner, you can supply this table directly.
     */
    open val classes: Map<String, KClass<*>> =
        run {
            val result = mutableMapOf<String, KClass<*>>()
            for (className in MANDATORY_CLASS_NAMES) {
                getSchemaClass()
                    .nestedClasses
                    .firstOrNull { it.simpleName == className }
                    ?.let { result[className] = it }
                    ?: missingClasses.add(className)
            }
            for (className in OPTIONAL_CLASS_NAMES) {
                (
                    getSchemaClass().nestedClasses.firstOrNull { it.simpleName == className }
                        ?: ViaductSchema::class.nestedClasses.firstOrNull { it.simpleName == className }
                )?.let { result[className] = it }
            }
            result
        }

    @Test
    fun `no missing classes`() {
        assertTrue(noMissingClasses(), "Missing classes: $missingClasses")
    }

    fun noMissingClasses() = missingClasses.isEmpty()

    fun kclass(name: String): KClass<*> = classes[name]?.let { it } ?: throw AssertionError("No class $name in ${getSchemaClass()}")

    // From Arg

    @Test
    @EnabledIf("noMissingClasses")
    fun `containingDef of DirectiveArg is expected subtype of Directive`() = assertIsSubtype(returnType("DirectiveArg.containingDef"), "Directive")

    @Test
    @EnabledIf("noMissingClasses")
    fun `containingDef of FieldArg is expected subtype of Field`() = assertIsSubtype(returnType("FieldArg.containingDef"), "Field")

    @Test
    @EnabledIf("noMissingClasses")
    fun `args of HasArgs are of the expected subtype`() {
        assertIsSubtype(returnType("HasArgs.args").elementType(), "Arg") // args: Iterable<Args>
    }

    // From BridgeSchema

    @Test
    @EnabledIf("noMissingClasses")
    fun `types map of schemaClass has expected type params`() {
        val expectedType =
            Map::class.createType(
                arguments =
                    listOf(
                        KTypeProjection(KVariance.INVARIANT, String::class.starProjectedType),
                        KTypeProjection(KVariance.INVARIANT, kclass("Directive").starProjectedType)
                    )
            )
        assertIsSubtype(returnType("SubjectClass.directives"), expectedType)
    }

    @Test
    @EnabledIf("noMissingClasses")
    fun `entries of schameClass are of the expected subtype`() {
        val expectedType =
            Map::class.createType(
                arguments =
                    listOf(
                        KTypeProjection(KVariance.INVARIANT, String::class.starProjectedType),
                        KTypeProjection(KVariance.INVARIANT, kclass("TypeDef").starProjectedType)
                    )
            )
        assertIsSubtype(returnType("SubjectClass.types"), expectedType)
    }

    // From Def

    @Test
    @EnabledIf("noMissingClasses")
    fun `Def class hierarchy is correct`() {
        assertIsSubtype("Directive", "Def")
        assertIsSubtype("EnumValue", "Def")
        assertIsSubtype("HasArgs", "Def")
        assertIsSubtype("HasDefaultValue", "Def")
        assertIsSubtype("TypeDef", "Def")

        assertIsSubtype("DirectiveArg", "HasDefaultValue")
        assertIsSubtype("FieldArg", "HasDefaultValue")
        assertIsSubtype("Field", "HasDefaultValue")

        assertIsSubtype("Directive", "HasArgs")
        assertIsSubtype("Field", "HasArgs")

        assertIsSubtype("DirectiveArg", "Arg")
        assertIsSubtype("FieldArg", "Arg")

        assertIsSubtype("Record", "TypeDef")

        assertIsSubtype("Enum", "TypeDef")
        assertIsSubtype("Input", "Record")
        assertIsSubtype("Interface", "Record")
        assertIsSubtype("Object", "Record")
        assertIsSubtype("Scalar", "TypeDef")
        assertIsSubtype("Union", "TypeDef")
    }

    // From Directive

    @Test
    @EnabledIf("noMissingClasses")
    fun `args field of Directive are of the expected subtype`() {
        assertIsSubtype(returnType("Directive.args").elementType(), "DirectiveArg") // args: Iterable<DirectiveArg>
    }

    // From Enum

    @Test
    @EnabledIf("noMissingClasses")
    fun `Enum members have the expected subtype of EnumValue`() {
        assertIsSubtype(returnType("Enum.values").elementType(), "EnumValue") // values: Iterable<EnumValue>
        assertIsSubtype(returnType("Enum.value"), "EnumValue", nullable = true)
    }

    fun `Enum extensions have the expected subtype of Extension`() = assertExtensionsSubtypes("Enum", "EnumValue")

    // From EnumValue

    @Test
    @EnabledIf("noMissingClasses")
    fun `containingEnum field of EnumValue has expected Enum type`() = assertIsSubtype(returnType("EnumValue.containingDef"), "Enum")

    @Test
    @EnabledIf("noMissingClasses")
    fun `EnumValue containingExtension has the expected subtype of Extension`() = assertContainingExtensionSubtypes("Enum", "EnumValue")

    // From Field

    @Test
    @EnabledIf("noMissingClasses")
    fun `containingDef of Field is expected subtype of Record`() = assertIsSubtype(returnType("Field.containingDef"), "Record")

    @Test
    @EnabledIf("noMissingClasses")
    fun `containingExtension of Field is expected subtype of Extension`() = assertContainingExtensionSubtypes("Record", "Field")

    @Test
    @EnabledIf("noMissingClasses")
    fun `args field of Field are of the expected subtype`() {
        assertIsSubtype(returnType("Field.args").elementType(), "FieldArg") // args: Iterable<FieldArg>
    }

    // From HasDefaultValue

    @Test
    @EnabledIf("noMissingClasses")
    fun `containingDef of HasDefaultValue is expected subtype of Def`() = assertIsSubtype(returnType("HasDefaultValue.containingDef"), "Def")

    @Test
    @EnabledIf("noMissingClasses")
    fun `type field of HasDefaultValue is expected subtype of TypeExpr`() {
        assertIsSubtype(returnType("HasDefaultValue.type"), "TypeExpr")
    }

    // From Input

    @Test
    @EnabledIf("noMissingClasses")
    fun `Input extensions have the expected subtype of Extension`() = assertExtensionsSubtypes("Input", "Field")

    // From Interface

    @Test
    @EnabledIf("noMissingClasses")
    fun `Interface extensions have the expected subtype of Extension`() = assertExtensionsSubtypes("Interface", "Field")

    // From Object

    @Test
    @EnabledIf("noMissingClasses")
    fun `Object extensions have the expected subtype of Extension`() = assertExtensionsSubtypes("Object", "Field")

    // From Record

    @Test
    @EnabledIf("noMissingClasses")
    fun `elements of Recrod_fields are of the expected subtype`() = assertIsSubtype(returnType("Record.fields").elementType(), "Field") // fields: Iterable<Field>

    @Test
    @EnabledIf("noMissingClasses")
    fun `return types of Record_field functions are of the expected subtype`() {
        // Deal with:
        //    fun field(name: String): Field?
        //    fun field(path: List<TypeName>): Field
        val fieldGetters = kclass("Record").members.filter { it.name == "field" }
        assertEquals(2, fieldGetters.size, "Expected two field getters ($fieldGetters)")
        fieldGetters.forEach {
            val isNullable = it.parameters[1].type.isSubtypeOf(String::class.starProjectedType)
            assertIsSubtype(it.returnType, "Field", nullable = isNullable)
        }

        assertIsSubtype(returnType("Record.supers").elementType(), "Interface") // supers: Iterable<Interface>
        assertIsSubtype(returnType("Record.unions").elementType(), "Union") // unions: Iterable<Unions>
    }

    @Test
    @EnabledIf("noMissingClasses")
    fun `elements of Record_supers are of the expected subtype`() = assertIsSubtype(returnType("Record.supers").elementType(), "Interface") // supers: Iterable<Interface>

    @Test
    @EnabledIf("noMissingClasses")
    fun `elements of Record_unions are of the expected subtype`() = assertIsSubtype(returnType("Record.unions").elementType(), "Union") // unions: Iterable<Union>

    // None from Scalar

    // From TypeDef

    @Test
    @EnabledIf("noMissingClasses")
    fun `asTypeExpr of TypeDef is expected subtype`() = assertIsSubtype(returnType("TypeDef.asTypeExpr"), "TypeExpr")

    @Test
    @EnabledIf("noMissingClasses")
    fun `possibleObjectTypes of TypeDef is expected subtype`() = assertIsSubtype(returnType("TypeDef.possibleObjectTypes").elementType(), "Object")

    // From TypeExpr

    @Test
    @EnabledIf("noMissingClasses")
    fun `baseTypeDef of TypeExpr is expected subtype`() = assertIsSubtype(returnType("TypeExpr.baseTypeDef"), "TypeDef")

    // From Union

    @Test
    @EnabledIf("noMissingClasses")
    fun `Union extensions have the expected subtype of Extension`() = assertExtensionsSubtypes("Union", "Object")

    companion object {
        val MANDATORY_CLASS_NAMES =
            listOf(
                "Arg",
                "FieldArg",
                "Def",
                "Directive",
                "DirectiveArg",
                "Enum",
                "EnumValue",
                "Field",
                "HasArgs",
                "HasDefaultValue",
                "Input",
                "Interface",
                "Object",
                "Record",
                "Scalar",
                "TypeDef",
                "TypeExpr",
                "Union"
            )
        val OPTIONAL_CLASS_NAMES =
            listOf(
                "AppliedDirective",
                "Extension",
                "ExtensionWithSupers",
                "HasExtensions",
                "HasExtensionsWithSupers"
            )

        fun KType.elementType() =
            run {
                assertTrue(this.isSubtypeOf(Iterable::class.starProjectedType), "$this is not Iterable")
                this.arguments[0].type!!
            }

        fun assertIsSubtype(
            subtype: KType,
            supertype: KType
        ): Unit = assertTrue(subtype.isSubtypeOf(supertype), "$subtype is not subtype of $supertype")
    }

    fun returnType(coord: String): KType {
        val (typeName, fieldName) = coord.split(".").let { Pair(it[0], it[1]) }
        val kc = if (typeName == "SubjectClass") getSchemaClass() else kclass(typeName)
        return kc.members.firstOrNull { it.name == fieldName }?.returnType
            ?: throw IllegalArgumentException(
                "No field $fieldName in $typeName (${classes[typeName]}, ${classes[typeName]!!.members})"
            )
    }

    fun assertIsSubtype(
        ksubtype: KType,
        supertype: String,
        nullable: Boolean = false,
        arguments: List<KTypeProjection>? = null
    ) {
        val ksuperclass = kclass(supertype)
        val ksupertype =
            when (arguments) {
                null -> ksuperclass.starProjectedType.withNullability(nullable)
                else -> kclass(supertype).createType(nullable = nullable, arguments = arguments)
            }
        assertIsSubtype(ksubtype, ksupertype)
    }

    fun assertIsSubtype(
        subtype: String,
        vararg supertypes: String
    ): Unit =
        supertypes.forEach {
            assertIsSubtype(kclass(subtype).starProjectedType, it)
        }

    fun assertExtensionsSubtypes(
        containerType: String,
        memberType: String
    ) {
        if (skipExtensionTests) return
        assertIsSubtype(
            returnType("$containerType.extensions").elementType(),
            "Extension",
            arguments =
                listOf(
                    KTypeProjection(KVariance.INVARIANT, kclass(containerType).starProjectedType),
                    KTypeProjection(KVariance.INVARIANT, kclass(memberType).starProjectedType)
                )
        )
    }

    fun assertContainingExtensionSubtypes(
        containerType: String,
        memberType: String
    ) {
        if (skipExtensionTests) return
        assertIsSubtype(
            returnType("$memberType.containingExtension"),
            "Extension",
            arguments =
                listOf(
                    KTypeProjection(KVariance.INVARIANT, kclass(containerType).starProjectedType),
                    KTypeProjection(KVariance.INVARIANT, kclass(memberType).starProjectedType)
                )
        )
    }
}
