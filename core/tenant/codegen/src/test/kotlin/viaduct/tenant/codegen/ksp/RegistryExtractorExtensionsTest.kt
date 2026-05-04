package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.Variable

class RegistryExtractorExtensionsTest {
    @Test
    fun `toResolverParams returns node params for node resolver`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "",
                        "queryValueFragment" to "",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertTrue(result is ResolverParams.Node)
        assertEquals("com.example.feature.resolvers.ExampleNodeResolver", result.implFqn)
        assertEquals("ExampleNode", result.typeName)
        assertEquals("com.example.feature.resolverbases.NodeResolvers.ExampleNode", result.resolverBaseClass)
        assertEquals("ExampleNodeResolver", result.attribution)
        assertEquals(false, result.isBatching)
        assertEquals(false, result.isSelective)
        assertTrue(logger.warns.isEmpty(), logger.warns.joinToString("\n"))
        assertTrue(logger.errors.isEmpty(), logger.errors.joinToString("\n"))
    }

    @Test
    fun `toResolverParams returns field params for field resolver`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf(
                        "typeName" to "ExampleNode",
                        "fieldName" to "name",
                        "isBatching" to false,
                        "isSelective" to false,
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertTrue(result is ResolverParams.Field)
        assertEquals("com.example.feature.resolvers.ExampleNameResolver", result.implFqn)
        assertEquals("ExampleNode", result.typeName)
        assertEquals("name", result.fieldName)
        assertEquals("com.example.feature.resolverbases.ExampleName", result.resolverBaseClass)
        assertEquals("ExampleNameResolver", result.attribution)
        assertEquals(false, result.isBatching)
        assertEquals(false, result.isSelective)
        assertNull(result.objectSelections)
        assertNull(result.querySelections)
        assertTrue(logger.warns.isEmpty(), logger.warns.joinToString("\n"))
        assertTrue(logger.errors.isEmpty(), logger.errors.joinToString("\n"))
    }

    @Test
    fun `toResolverParams returns field params with fragments when Resolver annotation is present`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf(
                        "typeName" to "ExampleNode",
                        "fieldName" to "name",
                        "isBatching" to false,
                        "isSelective" to false,
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "fragment _ on ExampleNode { firstName lastName }",
                        "queryValueFragment" to "",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertTrue(result is ResolverParams.Field)
        assertEquals("fragment _ on ExampleNode { firstName lastName }", result.objectSelections?.selections)
        assertTrue(result.objectSelections?.variablesProviders.isNullOrEmpty())
        assertNull(result.querySelections)
    }

    @Test
    fun `toResolverParams returns null when direct resolver base is not annotated`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.other.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.other",
            annotations = emptyList(),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.infos.any {
                it.contains("no direct supertype is annotated with @NodeResolverFor or @ResolverFor")
            },
            logger.infos.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs error for local class`() {
        val logger = RecordingKspLogger()

        // A local class has a non-KSClassDeclaration parent declaration (e.g., a function)
        val localParent = ksNonClassDeclaration(simpleName = "someFunction")
        val localDeclaration = ksLocalClassDeclaration(
            simpleName = "LocalResolver",
            parentDeclaration = localParent,
        )

        val result = localDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.errors.any { it.contains("not supported on local classes") },
            logger.errors.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs error when qualified name is null`() {
        val logger = RecordingKspLogger()

        val resolverDeclaration = ksClassDeclarationWithNullQualifiedName(
            simpleName = "AnonymousResolver",
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.errors.any { it.contains("must have a qualified name") },
            logger.errors.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null when direct supertype declaration is not a class`() {
        val logger = RecordingKspLogger()

        // A supertype whose declaration is not a KSClassDeclaration (e.g. a type alias)
        // is filtered out silently; if no annotated class supertype remains, an info is emitted.
        val nonClassDeclaration = ksNonClassDeclaration(simpleName = "SomeTypeAlias")
        val resolverDeclaration = ksClassDeclarationWithNonClassSupertype(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            supertypeDeclaration = nonClassDeclaration,
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.infos.any { it.contains("no direct supertype is annotated with") },
            logger.infos.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null when class has no supertypes`() {
        val logger = RecordingKspLogger()

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = emptyList(),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.infos.any { it.contains("no direct supertype is annotated") },
            logger.infos.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs warn when isBatching is missing from NodeResolverFor`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "",
                        "queryValueFragment" to "",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.warns.any { it.contains("isBatching") },
            logger.warns.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs warn when isSelective is missing from NodeResolverFor`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "",
                        "queryValueFragment" to "",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.warns.any { it.contains("isSelective") },
            logger.warns.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams returns null and logs warn when base class has no qualified name`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksNodeBaseDeclarationWithNullQualifiedName(
            simpleName = "ExampleNode",
            typeName = "ExampleNode",
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.warns.any { it.contains("base class has no qualified name") },
            logger.warns.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams populates providedVariables from @Variables nested class`() {
        val logger = RecordingKspLogger()

        val variablesAnnotation = ksAnnotation(
            simpleName = "Variables",
            args = mapOf("types" to listOf("experiment: Boolean!", "limit: Int")),
        )
        val nestedVarsClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver.Vars",
            simpleName = "Vars",
            packageName = "com.example.feature.resolvers",
            annotations = listOf(variablesAnnotation),
        )

        val variableAnnotation = ksAnnotation(
            simpleName = "Variable",
            args = mapOf("name" to "experiment", "fromArgument" to "experiment", "fromObjectField" to Variable.UNSET_STRING_VALUE, "fromQueryField" to Variable.UNSET_STRING_VALUE),
        )
        val resolverAnnotation = ksAnnotation(
            simpleName = "Resolver",
            args = mapOf(
                "objectValueFragment" to "fragment _ on ExampleNode { name }",
                "queryValueFragment" to "",
                "variables" to listOf(variableAnnotation),
            ),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            annotations = listOf(resolverAnnotation),
            superDeclarations = listOf(baseDeclaration),
            declarations = listOf(nestedVarsClass),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field
        val provider = result?.objectSelections?.variablesProviders?.single()

        assertEquals(mapOf("experiment" to "Boolean!"), provider?.providedVariables)
    }

    @Test
    fun `toResolverParams providedVariables uses empty string type sentinel when no @Variables nested class present`() {
        val logger = RecordingKspLogger()

        val variableAnnotation = ksAnnotation(
            simpleName = "Variable",
            args = mapOf("name" to "experiment", "fromArgument" to "experiment", "fromObjectField" to Variable.UNSET_STRING_VALUE, "fromQueryField" to Variable.UNSET_STRING_VALUE),
        )
        val resolverAnnotation = ksAnnotation(
            simpleName = "Resolver",
            args = mapOf(
                "objectValueFragment" to "fragment _ on ExampleNode { name }",
                "queryValueFragment" to "",
                "variables" to listOf(variableAnnotation),
            ),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            annotations = listOf(resolverAnnotation),
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field
        val provider = result?.objectSelections?.variablesProviders?.single()

        // Must be keyed by name (even with empty string) so bootstrapper can iterate .keys
        assertEquals(mapOf("experiment" to ""), provider?.providedVariables)
    }

    @Test
    fun `toResolverParams providedVariables only includes entries matching the variable name`() {
        val logger = RecordingKspLogger()

        // @Variables declares two variables, but only one @Variable binding exists
        val variablesAnnotation = ksAnnotation(
            simpleName = "Variables",
            args = mapOf("types" to listOf("experiment: Boolean!", "unrelated: String")),
        )
        val nestedVarsClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver.Vars",
            simpleName = "Vars",
            packageName = "com.example.feature.resolvers",
            annotations = listOf(variablesAnnotation),
        )

        val variableAnnotation = ksAnnotation(
            simpleName = "Variable",
            args = mapOf("name" to "experiment", "fromArgument" to "experiment", "fromObjectField" to Variable.UNSET_STRING_VALUE, "fromQueryField" to Variable.UNSET_STRING_VALUE),
        )
        val resolverAnnotation = ksAnnotation(
            simpleName = "Resolver",
            args = mapOf(
                "objectValueFragment" to "fragment _ on ExampleNode { name }",
                "queryValueFragment" to "",
                "variables" to listOf(variableAnnotation),
            ),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            annotations = listOf(resolverAnnotation),
            superDeclarations = listOf(baseDeclaration),
            declarations = listOf(nestedVarsClass),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field
        val provider = result?.objectSelections?.variablesProviders?.single()

        // Only "experiment" matches the @Variable name — "unrelated" is not included
        assertEquals(mapOf("experiment" to "Boolean!"), provider?.providedVariables)
    }

    @Test
    fun `toResolverParams skips node resolver without @Resolver annotation`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.infos.any { it.contains("not annotated with @Resolver") },
            logger.infos.joinToString("\n"),
        )
        assertTrue(logger.errors.isEmpty(), logger.errors.joinToString("\n"))
    }

    @Test
    fun `toResolverParams errors when node resolver @Resolver has objectValueFragment`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "fragment _ on ExampleNode { id }",
                        "queryValueFragment" to "",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.errors.any { it.contains("must not specify objectValueFragment") },
            logger.errors.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams errors when node resolver @Resolver has queryValueFragment`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "",
                        "queryValueFragment" to "fragment _ on Query { viewer { id } }",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.errors.any { it.contains("must not specify objectValueFragment, queryValueFragment, or variables") },
            logger.errors.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams errors when node resolver @Resolver has variables`() {
        val logger = RecordingKspLogger()

        val variableAnnotation = ksAnnotation(
            simpleName = "Variable",
            args = mapOf(
                "name" to "x",
                "fromArgument" to "x",
                "fromObjectField" to Variable.UNSET_STRING_VALUE,
                "fromQueryField" to Variable.UNSET_STRING_VALUE,
            ),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "",
                        "queryValueFragment" to "",
                        "variables" to listOf(variableAnnotation),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertNull(result)
        assertTrue(
            logger.errors.any { it.contains("must not specify objectValueFragment, queryValueFragment, or variables") },
            logger.errors.joinToString("\n"),
        )
    }

    @Test
    fun `toResolverParams succeeds for node resolver with @Resolver that has blank fragments`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = mapOf(
                        "objectValueFragment" to "   ",
                        "queryValueFragment" to " \t ",
                        "variables" to emptyList<Any>(),
                    ),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertTrue(result is ResolverParams.Node)
        assertEquals("ExampleNode", result.typeName)
        assertTrue(logger.errors.isEmpty(), logger.errors.joinToString("\n"))
    }

    @Test
    fun `toResolverParams succeeds for node resolver with @Resolver with no explicit args`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.NodeResolvers.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "NodeResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNodeResolver",
            simpleName = "ExampleNodeResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            annotations = listOf(
                ksAnnotation(
                    simpleName = "Resolver",
                    args = emptyMap(),
                ),
            ),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger)

        assertTrue(result is ResolverParams.Node)
        assertEquals("ExampleNode", result.typeName)
        assertTrue(logger.errors.isEmpty(), logger.errors.joinToString("\n"))
    }

    @Test
    fun `toResolverParams - hasArguments is false when Context supertype includes NoArguments`() {
        val logger = RecordingKspLogger()

        val noArgumentsDeclaration = ksClassDeclaration(
            qualifiedName = "viaduct.api.types.Arguments.NoArguments",
            simpleName = "NoArguments",
            packageName = "viaduct.api.types",
            declarations = emptyList(),
        )
        val contextSupertype = ksClassDeclaration(
            qualifiedName = "com.example.ContextBase",
            simpleName = "ContextBase",
            packageName = "com.example",
            declarations = emptyList(),
        )
        val contextTypeArg = ksTypeArgument(ksTypeReference(noArgumentsDeclaration))
        val contextClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName.Context",
            simpleName = "Context",
            packageName = "com.example.feature.resolverbases",
            superDeclarations = emptyList(),
            declarations = emptyList(),
            contextSuperTypeRef = ksTypeReferenceWithArgs(contextSupertype, listOf(contextTypeArg)),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = listOf(contextClass),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field

        assertEquals(false, result?.hasArguments)
    }

    @Test
    fun `toResolverParams - hasArguments is true when Context supertype has no NoArguments type arg`() {
        val logger = RecordingKspLogger()

        val someArgumentsDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.SomeArguments",
            simpleName = "SomeArguments",
            packageName = "com.example",
            declarations = emptyList(),
        )
        val contextSupertype = ksClassDeclaration(
            qualifiedName = "com.example.ContextBase",
            simpleName = "ContextBase",
            packageName = "com.example",
            declarations = emptyList(),
        )
        val contextTypeArg = ksTypeArgument(ksTypeReference(someArgumentsDeclaration))
        val contextClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName.Context",
            simpleName = "Context",
            packageName = "com.example.feature.resolverbases",
            superDeclarations = emptyList(),
            declarations = emptyList(),
            contextSuperTypeRef = ksTypeReferenceWithArgs(contextSupertype, listOf(contextTypeArg)),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = listOf(contextClass),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field

        assertEquals(true, result?.hasArguments)
    }

    @Test
    fun `toResolverParams - queryTypeName defaults to Query when no Context class present`() {
        val logger = RecordingKspLogger()

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field

        assertEquals("Query", result?.queryTypeName)
    }

    @Test
    fun `toResolverParams - queryTypeName is extracted from Context supertype type arg that implements Query`() {
        val logger = RecordingKspLogger()

        val queryInterfaceDeclaration = ksClassDeclaration(
            qualifiedName = "viaduct.api.types.Query",
            simpleName = "Query",
            packageName = "viaduct.api.types",
            declarations = emptyList(),
        )
        val queryTypeDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.grts.SomeQuery",
            simpleName = "SomeQuery",
            packageName = "com.example.grts",
            superDeclarations = listOf(queryInterfaceDeclaration),
            declarations = emptyList(),
        )
        val contextSupertype = ksClassDeclaration(
            qualifiedName = "com.example.ContextBase",
            simpleName = "ContextBase",
            packageName = "com.example",
            declarations = emptyList(),
        )
        val contextTypeArg = ksTypeArgument(ksTypeReference(queryTypeDeclaration))
        val contextClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName.Context",
            simpleName = "Context",
            packageName = "com.example.feature.resolverbases",
            superDeclarations = emptyList(),
            declarations = emptyList(),
            contextSuperTypeRef = ksTypeReferenceWithArgs(contextSupertype, listOf(contextTypeArg)),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = listOf(contextClass),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field

        assertEquals("SomeQuery", result?.queryTypeName)
    }

    @Test
    fun `toResolverParams - queryTypeName defaults to Query when Context has no type arg implementing Query`() {
        val logger = RecordingKspLogger()

        val noArgumentsDeclaration = ksClassDeclaration(
            qualifiedName = "viaduct.api.types.Arguments.NoArguments",
            simpleName = "NoArguments",
            packageName = "viaduct.api.types",
            declarations = emptyList(),
        )
        val contextSupertype = ksClassDeclaration(
            qualifiedName = "com.example.ContextBase",
            simpleName = "ContextBase",
            packageName = "com.example",
            declarations = emptyList(),
        )
        val contextTypeArg = ksTypeArgument(ksTypeReference(noArgumentsDeclaration))
        val contextClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName.Context",
            simpleName = "Context",
            packageName = "com.example.feature.resolverbases",
            superDeclarations = emptyList(),
            declarations = emptyList(),
            contextSuperTypeRef = ksTypeReferenceWithArgs(contextSupertype, listOf(contextTypeArg)),
        )

        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = listOf(contextClass),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field

        assertEquals("Query", result?.queryTypeName)
    }

    @Test
    fun `toResolverParams - returnTypeName is extracted from ResolverBase type argument`() {
        val logger = RecordingKspLogger()

        val returnTypeDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.grts.ExampleNode",
            simpleName = "ExampleNode",
            packageName = "com.example.grts",
            declarations = emptyList(),
        )
        val resolverBaseDeclaration = ksClassDeclaration(
            qualifiedName = "viaduct.api.ResolverBase",
            simpleName = "ResolverBase",
            packageName = "viaduct.api",
            declarations = emptyList(),
        )
        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
            superTypeRefs = listOf(
                ksTypeReferenceWithArgs(resolverBaseDeclaration, listOf(ksTypeArgument(ksTypeReference(returnTypeDeclaration)))),
            ),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field

        assertEquals("ExampleNode", result?.returnTypeName)
    }

    @Test
    fun `extractGrtPackagePrefix finds prefix from resolver nested more than two levels deep`() {
        val grtClass = ksClassDeclaration(
            qualifiedName = "viaduct.api.grts.SomeGrtClass",
            simpleName = "SomeGrtClass",
            packageName = "viaduct.api.grts",
            declarations = emptyList(),
        )
        val contextSupertype = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.FieldContext",
            simpleName = "FieldContext",
            packageName = "com.example.feature.resolverbases",
            declarations = emptyList(),
        )
        val contextClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName.Context",
            simpleName = "Context",
            packageName = "com.example.feature.resolverbases",
            declarations = emptyList(),
            contextSuperTypeRef = ksTypeReferenceWithArgs(contextSupertype, listOf(ksTypeArgument(ksTypeReference(grtClass)))),
        )
        val resolverBase = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = listOf(contextClass),
        )
        // Resolver at depth 3: outerClass → middleClass → deepResolver
        val deepResolver = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.outer.Middle.DeepResolver",
            simpleName = "DeepResolver",
            packageName = "com.example.feature.resolvers.outer",
            superDeclarations = listOf(resolverBase),
            declarations = emptyList(),
        )
        val middleClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.outer.Middle",
            simpleName = "Middle",
            packageName = "com.example.feature.resolvers.outer",
            declarations = listOf(deepResolver),
        )
        val outerClass = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.Outer",
            simpleName = "Outer",
            packageName = "com.example.feature.resolvers",
            declarations = listOf(middleClass),
        )

        assertEquals("viaduct.api.grts", extractGrtPackagePrefix(listOf(outerClass)))
    }

    @Test
    fun `toResolverParams - returnTypeName is null when ResolverBase has no type argument`() {
        val logger = RecordingKspLogger()

        val resolverBaseDeclaration = ksClassDeclaration(
            qualifiedName = "viaduct.api.ResolverBase",
            simpleName = "ResolverBase",
            packageName = "viaduct.api",
            declarations = emptyList(),
        )
        val baseDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolverbases.ExampleName",
            simpleName = "ExampleName",
            packageName = "com.example.feature.resolverbases",
            annotations = listOf(
                ksAnnotation(
                    simpleName = "ResolverFor",
                    args = mapOf("typeName" to "ExampleNode", "fieldName" to "name", "isBatching" to false, "isSelective" to false),
                ),
            ),
            declarations = emptyList(),
            superTypeRefs = listOf(
                ksTypeReferenceWithArgs(resolverBaseDeclaration, emptyList()),
            ),
        )

        val resolverDeclaration = ksClassDeclaration(
            qualifiedName = "com.example.feature.resolvers.ExampleNameResolver",
            simpleName = "ExampleNameResolver",
            packageName = "com.example.feature.resolvers",
            superDeclarations = listOf(baseDeclaration),
            declarations = emptyList(),
        )

        val result = resolverDeclaration.toResolverParams(logger) as? ResolverParams.Field

        assertNull(result?.returnTypeName)
    }
}

private fun ksClassDeclaration(
    qualifiedName: String,
    simpleName: String,
    packageName: String,
    annotations: List<KSAnnotation> = emptyList(),
    superDeclarations: List<KSClassDeclaration> = emptyList(),
    declarations: List<KSDeclaration> = emptyList(),
    contextSuperTypeRef: KSTypeReference? = null,
    superTypeRefs: List<KSTypeReference>? = null,
): KSClassDeclaration {
    val qualifiedNameValue = ksName(qualifiedName)
    val simpleNameValue = ksName(simpleName)
    val packageNameValue = ksName(packageName)
    val superTypes = superTypeRefs
        ?: contextSuperTypeRef?.let { listOf(it) }
        ?: superDeclarations.map { ksTypeReference(it) }

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getQualifiedName" -> qualifiedNameValue
            "getSimpleName" -> simpleNameValue
            "getPackageName" -> packageNameValue
            "getAnnotations" -> annotations.asSequence()
            "getSuperTypes" -> superTypes.asSequence()
            "getDeclarations" -> declarations.asSequence()
            "getContainingFile" -> null
            "getParentDeclaration" -> null
            "toString" -> qualifiedName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

private fun ksAnnotation(
    simpleName: String,
    args: Map<String, Any?>,
): KSAnnotation {
    val shortName = ksName(simpleName)
    val arguments = args.map { (name, value) -> ksValueArgument(name, value) }

    return proxy(KSAnnotation::class.java) { method, _ ->
        when (method.name) {
            "getShortName" -> shortName
            "getArguments" -> arguments
            "toString" -> "@$simpleName"
            else -> unsupported("KSAnnotation.${method.name}")
        }
    }
}

private fun ksValueArgument(
    name: String,
    value: Any?,
): KSValueArgument {
    val argumentName = ksName(name)

    return proxy(KSValueArgument::class.java) { method, _ ->
        when (method.name) {
            "getName" -> argumentName
            "getValue" -> value
            "toString" -> "$name=$value"
            else -> unsupported("KSValueArgument.${method.name}")
        }
    }
}

private fun ksTypeReference(declaration: KSClassDeclaration): KSTypeReference {
    val type = proxy(KSType::class.java) { method, _ ->
        when (method.name) {
            "getDeclaration" -> declaration
            "toString" -> declaration.toString()
            else -> unsupported("KSType.${method.name}")
        }
    }

    return proxy(KSTypeReference::class.java) { method, _ ->
        when (method.name) {
            "resolve" -> type
            "toString" -> declaration.toString()
            else -> unsupported("KSTypeReference.${method.name}")
        }
    }
}

private fun ksTypeArgument(typeRef: KSTypeReference): KSTypeArgument {
    return proxy(KSTypeArgument::class.java) { method, _ ->
        when (method.name) {
            "getType" -> typeRef
            "toString" -> typeRef.toString()
            else -> unsupported("KSTypeArgument.${method.name}")
        }
    }
}

private fun ksTypeReferenceWithArgs(
    declaration: KSClassDeclaration,
    typeArgs: List<KSTypeArgument>,
): KSTypeReference {
    val type = proxy(KSType::class.java) { method, _ ->
        when (method.name) {
            "getDeclaration" -> declaration
            "getArguments" -> typeArgs
            "toString" -> declaration.toString()
            else -> unsupported("KSType.${method.name}")
        }
    }

    return proxy(KSTypeReference::class.java) { method, _ ->
        when (method.name) {
            "resolve" -> type
            "toString" -> declaration.toString()
            else -> unsupported("KSTypeReference.${method.name}")
        }
    }
}

private fun ksName(value: String): KSName {
    return proxy(KSName::class.java) { method, _ ->
        when (method.name) {
            "asString" -> value
            "getShortName" -> value.substringAfterLast('.', value)
            "getQualifier" -> value.substringBeforeLast('.', "")
            "toString" -> value
            else -> unsupported("KSName.${method.name}")
        }
    }
}

/** Creates a KSClassDeclaration whose parentDeclaration is a non-KSClassDeclaration (making it local). */
private fun ksLocalClassDeclaration(
    simpleName: String,
    parentDeclaration: KSDeclaration,
): KSClassDeclaration {
    val simpleNameValue = ksName(simpleName)

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getParentDeclaration" -> parentDeclaration
            "toString" -> simpleName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

/** Creates a KSClassDeclaration with null qualifiedName (and non-local, so isLocal() = false). */
private fun ksClassDeclarationWithNullQualifiedName(simpleName: String): KSClassDeclaration {
    val simpleNameValue = ksName(simpleName)

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getQualifiedName" -> null
            "getParentDeclaration" -> null
            "toString" -> simpleName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

/** Creates a non-KSClassDeclaration (e.g. a function) to act as parent or supertype declaration. */
private fun ksNonClassDeclaration(simpleName: String): KSDeclaration {
    val simpleNameValue = ksName(simpleName)

    return proxy(KSFunctionDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getParentDeclaration" -> null
            "toString" -> simpleName
            else -> unsupported("KSFunctionDeclaration.${method.name}")
        }
    }
}

/** Creates a KSClassDeclaration whose first supertype has a non-KSClassDeclaration declaration. */
private fun ksClassDeclarationWithNonClassSupertype(
    qualifiedName: String,
    simpleName: String,
    packageName: String,
    supertypeDeclaration: KSDeclaration,
): KSClassDeclaration {
    val qualifiedNameValue = ksName(qualifiedName)
    val simpleNameValue = ksName(simpleName)
    val packageNameValue = ksName(packageName)

    val type = proxy(KSType::class.java) { method, _ ->
        when (method.name) {
            "getDeclaration" -> supertypeDeclaration
            "toString" -> supertypeDeclaration.toString()
            else -> unsupported("KSType.${method.name}")
        }
    }
    val typeRef = proxy(KSTypeReference::class.java) { method, _ ->
        when (method.name) {
            "resolve" -> type
            "toString" -> supertypeDeclaration.toString()
            else -> unsupported("KSTypeReference.${method.name}")
        }
    }

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getQualifiedName" -> qualifiedNameValue
            "getSimpleName" -> simpleNameValue
            "getPackageName" -> packageNameValue
            "getAnnotations" -> emptySequence<KSAnnotation>()
            "getSuperTypes" -> sequenceOf(typeRef)
            "getDeclarations" -> emptySequence<KSDeclaration>()
            "getContainingFile" -> null
            "getParentDeclaration" -> null
            "toString" -> qualifiedName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

/** Creates a node resolver base class (annotated with @NodeResolverFor) with null qualifiedName. */
private fun ksNodeBaseDeclarationWithNullQualifiedName(
    simpleName: String,
    typeName: String,
): KSClassDeclaration {
    val simpleNameValue = ksName(simpleName)
    val annotation = ksAnnotation(
        simpleName = "NodeResolverFor",
        args = mapOf("typeName" to typeName, "isBatching" to false, "isSelective" to false),
    )

    return proxy(KSClassDeclaration::class.java) { method, _ ->
        when (method.name) {
            "getSimpleName" -> simpleNameValue
            "getQualifiedName" -> null
            "getAnnotations" -> sequenceOf(annotation)
            "getParentDeclaration" -> null
            "toString" -> simpleName
            else -> unsupported("KSClassDeclaration.${method.name}")
        }
    }
}

private fun unsupported(name: String): Nothing {
    throw UnsupportedOperationException("Unexpected proxy call: $name")
}

private fun <T> proxy(
    type: Class<T>,
    handler: (method: java.lang.reflect.Method, args: Array<out Any?>?) -> Any?,
): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, args ->
        handler(method, args)
    } as T
}
