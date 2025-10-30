package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.SchemaParser
import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.FilteredSchema
import viaduct.graphql.schema.SchemaFilter
import viaduct.graphql.schema.SchemaInvariantOptions
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkBridgeSchemaInvariants
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.invariants.InvariantChecker

// TODO - Gradle doesn't seem to like this -- strange!!
// import org.junit.jupiter.api.condition.EnabledIf

class FilteredSchemaTest {
    companion object {
        lateinit var unfilteredTestSchema: ViaductSchema

        private val testSchemaString =
            """
            directive @d1(a1: Int = 1) on OBJECT

            type Query {
                a: String
            }

            enum EnumKeep {
                A
                BRemove
                C
            }

            enum EnumRemove {
                A
                B
            }

            input InputKeep {
                fieldARemove: String
                fieldB: Int
                fieldC: [InputRemove]!
            }

            input InputRemove {
                fieldA: String
                fieldB: Int
            }

            interface InterfaceRemove {
                aRemove: EnumKeep
                b: EnumRemove
            }

            interface InterfaceKeep implements InterfaceRemove {
                aRemove: EnumKeep
                b: EnumRemove
                c: ObjectKeep
            }

            interface Interface {
                c: ObjectKeep
            }

            type ObjectKeep implements InterfaceKeep & InterfaceRemove & Interface {
                aRemove: EnumKeep
                b: EnumRemove
                c(a1: InputKeep, a2: Int = 2): ObjectKeep
                d: ObjectRemove
            }

            type ObjectRemove @d1 {
                a: String
            }

            union UnionKeep = ObjectKeep | ObjectRemove
            union UnionRemove = ObjectKeep | ObjectRemove

            interface AKeep { a: String }
            type OA implements AKeep { a: String }
            interface B implements AKeep { a: String }
            type OB implements B & AKeep { a: String }
            """.trimIndent()

        private val filteredTestSchemaString =
            """
            directive @d1(a1: Int = 1) on OBJECT

            type Query {
                a: String
            }

            enum EnumKeep {
                A
                C
            }

            input InputKeep {
                fieldB: Int
            }

            interface InterfaceKeep {
                c: ObjectKeep
            }

            interface Interface {
                c: ObjectKeep
            }

            type ObjectKeep implements InterfaceKeep {
                c(a1: InputKeep, a2: Int = 2): ObjectKeep
            }

            union UnionKeep = ObjectKeep

            interface AKeep { a: String }
            type OA implements AKeep { a: String }
            interface B implements AKeep { a: String }
            type OB implements AKeep { a: String }
            """.trimIndent()

        @BeforeAll
        @JvmStatic
        fun loadSchema() {
            unfilteredTestSchema = GJSchema.fromRegistry(SchemaParser().parse(testSchemaString))
        }
    }

    @Test
    fun `compare noop-filtered test schema against expected result`() {
        val noopFilteredSchema = unfilteredTestSchema.filter(NoopSchemaFilter())
        SchemaDiff(unfilteredTestSchema, noopFilteredSchema).diff().assertEmpty("\n")
    }

    @Test
    fun `invariant checks on filtered test schema - with schema invarient options - empty types`() {
        val schemaFilterProducingEmptyTypes = EmptyTypesSchemaFilter()

        // Verify with an ALLOW_EMPTY_TYPES option disabled
        try {
            InvariantChecker().also { check ->
                checkBridgeSchemaInvariants(unfilteredTestSchema.filter(EmptyTypesSchemaFilter()), check)
            }.assertEmpty("\n")
        } catch (_: AssertionError) {
            // Assertion error is expected here as the filtered schema has empty types
        }

        // Verify with an ALLOW_EMPTY_TYPES option enabled
        InvariantChecker().also { check ->
            checkBridgeSchemaInvariants(
                unfilteredTestSchema.filter(
                    schemaFilterProducingEmptyTypes,
                    SchemaInvariantOptions.ALLOW_EMPTY_TYPES
                ),
                check,
                SchemaInvariantOptions.ALLOW_EMPTY_TYPES
            )
        }.assertEmpty("\n")
    }

    @Test
    fun `invariant checks on filtered test schema`() {
        InvariantChecker().also { check ->
            checkBridgeSchemaInvariants(unfilteredTestSchema.filter(SuffixSchemaFilter("Remove", "Keep")), check)
        }.assertEmpty("\n")
    }

    @Test
    fun `compare filtered test schema against expected result`() {
        val filteredSchema = unfilteredTestSchema.filter(SuffixSchemaFilter("Remove", "Keep"))
        assertFalse(
            SchemaDiff(unfilteredTestSchema, filteredSchema).diff().isEmpty,
            "Unfiltered schema should be different from filtered schema"
        )

        val expectedFilteredSchema = GJSchema.fromRegistry(SchemaParser().parse(filteredTestSchemaString))
        SchemaDiff(expectedFilteredSchema, filteredSchema).diff().assertEmpty("\n")
    }

    @Test
    fun `test unfilteredDef`() {
        val filteredSchema = unfilteredTestSchema.filter(SuffixSchemaFilter("Remove", "Keep"))

        val filteredEnum = filteredSchema.types["EnumKeep"] as FilteredSchema.Enum
        filteredEnum.checkUnfilteredDef(GJSchema.Enum::class)

        val filteredEnumValue = filteredEnum.values.first { it.name == "A" }
        filteredEnumValue.checkUnfilteredDef(GJSchema.EnumValue::class)

        val filteredInput = filteredSchema.types["InputKeep"] as FilteredSchema.Input
        filteredInput.checkUnfilteredDef(GJSchema.Input::class)

        val filteredObj = filteredSchema.types["ObjectKeep"] as FilteredSchema.Object<*>
        filteredObj.checkUnfilteredDef(GJSchema.Object::class)

        val filteredField = filteredObj.field("c") as FilteredSchema.Field
        filteredField.checkUnfilteredDef(GJSchema.Field::class)

        val filteredArg = filteredField.args.first { it.name == "a1" }
        filteredArg.checkUnfilteredDef(GJSchema.FieldArg::class)

        val filteredUnion = filteredSchema.types["UnionKeep"] as FilteredSchema.Union
        filteredUnion.checkUnfilteredDef(GJSchema.Union::class)

        val filteredInterface = filteredSchema.types["InterfaceKeep"] as FilteredSchema.Interface
        filteredInterface.checkUnfilteredDef(GJSchema.Interface::class)
    }

    @Test
    fun `test Interface_possibleObjectTypes for two-level inheritance`() {
        assertEquals( // test assumptions
            setOf("OA", "OB"),
            unfilteredTestSchema.types["AKeep"]!!.possibleObjectTypes.map { it.name }.toSet()
        )
        val filteredSchema = unfilteredTestSchema.filter(SuffixSchemaFilter("Remove", "Keep"))
        assertEquals(
            setOf("OA", "OB"),
            filteredSchema.types["AKeep"]!!.possibleObjectTypes.map { it.name }.toSet()
        )
        assertEquals(
            emptyList<String>(),
            filteredSchema.types["B"]!!.possibleObjectTypes.map { it.name }
        )
    }

    @Test
    fun `test unwrapAll for one layer`() {
        val filteredSchema = unfilteredTestSchema.filter(SuffixSchemaFilter("Remove", "Keep"))
        `test unwrapAll`(unfilteredTestSchema, filteredSchema)
    }

    @Test
    fun `test unwrapAll for two layers`() {
        val filteredSchema =
            unfilteredTestSchema
                .filter(SuffixSchemaFilter("Remove", "Keep"))
                .filter(NoopSchemaFilter())
        `test unwrapAll`(unfilteredTestSchema, filteredSchema)
    }

    fun `test unwrapAll`(
        unfilteredSchema: ViaductSchema,
        filteredSchema: ViaductSchema
    ) {
        val checker = InvariantChecker()
        for (def in filteredSchema.types.values) {
            checker.checkUnwrapping(unfilteredSchema.types[def.name]!!, def)
        }
        for (dir in filteredSchema.directives.values) {
            for (arg in dir.args) {
                val unfilteredArg = unfilteredSchema.directives[dir.name]!!.args.first { it.name == arg.name }
                checker.checkUnwrapping(unfilteredArg, arg)
            }
        }
        checker.assertEmpty("\n")
    }

    fun InvariantChecker.checkUnwrapping(
        unfilteredDef: ViaductSchema.Def,
        filteredDef: ViaductSchema.Def
    ) {
        isSameInstanceAs(unfilteredDef, filteredDef.unwrapAll(), "UNWRAP_WORKS")
        when (filteredDef) {
            is ViaductSchema.Enum -> {
                for (values in filteredDef.values) {
                    val unfilteredValue = (unfilteredDef as ViaductSchema.Enum).value(values.name)!!
                    checkUnwrapping(unfilteredValue, values)
                }
            }
            is ViaductSchema.HasArgs -> {
                for (arg in filteredDef.args) {
                    val unfilteredArg = (unfilteredDef as ViaductSchema.HasArgs).args.first { it.name == arg.name }
                    checkUnwrapping(unfilteredArg, arg)
                }
            }
            is ViaductSchema.Record -> {
                for (field in filteredDef.fields) {
                    val unfilteredField = (unfilteredDef as ViaductSchema.Record).field(field.name)!!
                    checkUnwrapping(unfilteredField, field)
                }
            }
            else -> { }
        }
    }

    private fun FilteredSchema.Def<*>.checkUnfilteredDef(expectedUnfilteredDefClass: KClass<out ViaductSchema.Def>) {
        assertTrue(expectedUnfilteredDefClass.isInstance(this.unfilteredDef))
        assertEquals(this.name, this.unfilteredDef.name)
    }
}

class EmptyTypesSchemaFilter : SchemaFilter {
    override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) = true

    override fun includeField(field: ViaductSchema.Field) = field.containingDef is ViaductSchema.Input

    override fun includeEnumValue(enumValue: ViaductSchema.EnumValue) = true

    override fun includeSuper(
        record: ViaductSchema.HasExtensionsWithSupers<*, *>,
        superInterface: ViaductSchema.Interface
    ) = true
}

class SuffixSchemaFilter(
    private val suffixToFilter: String,
    private val superSuffix: String
) : SchemaFilter {
    override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) = !typeDef.name.endsWith(suffixToFilter)

    override fun includeField(field: ViaductSchema.Field) = !field.name.endsWith(suffixToFilter)

    override fun includeEnumValue(enumValue: ViaductSchema.EnumValue) = !enumValue.name.endsWith(suffixToFilter)

    override fun includeSuper(
        record: ViaductSchema.HasExtensionsWithSupers<*, *>,
        superInterface: ViaductSchema.Interface
    ) = superInterface.name.endsWith(superSuffix)
}
