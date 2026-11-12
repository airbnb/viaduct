@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.testing

import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ArgumentResolutionError
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.FromArgument
import viaduct.engine.runtime2.model.registry.FromField
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.resolve
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class ResolverTestDslTest {
    @Test
    fun `scripted providers bind both fragments alongside path variables`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result(seed: Int!): Int! @resolver(
                of: "source local: echo(value: ${'$'}provided)"
                qf: "remote: echo(value: ${'$'}provided) fromPath: echo(value: ${'$'}fromSource)"
                pathVars: [{name: "fromSource", path: ["source"]}]
                vars: {names: ["provided"], script: "{'provided': seed * 2}"}
                script: "objectValue.local + queryValue.remote + queryValue.fromPath"
              )
              source: Int! @resolver(result: 7)
              echo(value: Int!): Int! @resolver(result: "value(${'$'}value)")
            }
            """.trimIndent()
        )
        val field = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(field)
        assertIs<VariableDefinition.FromProvider>(resolver.declaredDependencies.variables.getValue(Arguments.Variable.of(field, "provided")))
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ result(seed: 5) }"), dispatcher)
            assertEquals(27, root.getCell(root.keys.single { it.field == field }).value.get())
        }
    }

    @Test
    fun `scripted provider errors fail all bindings without admitting error input values`() {
        for (script in listOf("error", "{'first': 1, 'second': error}", "{'first': 1, 'second': [error]}")) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int @resolver(
                    of: "a: echo(value: ${'$'}first) b: echo(value: ${'$'}second)"
                    vars: {names: ["first", "second"], script: "$script"}
                    script: "1"
                  )
                  echo(value: Int): Int @resolver(result: "value(${'$'}value)")
                  healthy: Int @resolver(result: 9)
                }
                """.trimIndent()
            )
            val field = world.schema.requireObjectField("Query", "result")
            val operation = SharedOperationContext.create(world.assumptions)
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val root = operation.resolve(world.schemas.operationSelectionsFrom("{ result healthy }"), dispatcher)
                val key = root.keys.single { it.field == field }
                assertIs<ErrorEngineResult>(root.getCell(key).value.get())
                assertEquals(9, root.getCell(root.keys.single { it.field.name == "healthy" }).value.get())
                val definitions = world.resolverRegistry.resolver(field).declaredDependencies.instantiatedVariableDefinitions(ResolverOccurrenceId.at(root, listOf(key)))
                assertEquals(setOf("first", "second"), definitions.mapTo(linkedSetOf()) { it.variable.variableName })
                definitions.forEach { definition ->
                    assertEquals(VariableBinding.Error, operation.variableBindings.getBinding(checkNotNull(definition.variable.instanceId)))
                }
            }
        }
    }

    @Test
    fun `compiles structured references at scalar object and nested list positions`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item(seed: Int): Item @resolver(result: {
                number: {__ref: "namespace.number", arguments: {value: "${'$'}seed", options: {weights: ["${'$'}seed", null]}}},
                items: [[{__ref: "item", arguments: {seed: 3}}, null]]
              })
              namespace: Namespace
            }
            type Item { number: Int items: [[Item]] }
            type Namespace { number(value: Int, options: Options): Int }
            input Options { weights: [Int] }
            """.trimIndent(),
        )
        val item = assertIs<EngineObjectData.Sync>(world.apply(world.schema.requireObjectField("Query", "item"), arguments = mapOf("seed" to 7)))
        val number = assertIs<RootFieldReferenceData>(item.outputValue("number"))
        assertEquals(listOf("namespace", "number"), number.path.map { it.name })
        assertEquals(mapOf("value" to 7, "options" to mapOf("weights" to listOf(7, null))), number.arguments.fieldValues)
        val list = assertIs<List<*>>(assertIs<List<*>>(item.outputValue("items")).single())
        assertEquals(mapOf("seed" to 3), assertIs<RootFieldReferenceData>(list.first()).arguments.fieldValues)
        assertNull(list.last())
    }

    @Test
    fun `rejects malformed structured references and invalid target arguments`() {
        for (result in listOf(
            "{__ref: 7}",
            "{__ref: \"\"}",
            "{__ref: \"missing\"}",
            "{__ref: \"target\", extra: 1}",
            "{__ref: \"target\", arguments: 7}",
            "{__ref: \"target\", arguments: {value: \"wrong\"}}",
            "{__ref: \"target\", arguments: {value: \"${'$'}missing\"}}",
            "{__ref: \"target\", arguments: {value: 1, value: 2}}",
            "{__ref: \"targets\"}",
            "{__ref: \"namespace.target\"}",
        )) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  source: Int @resolver(result: $result)
                  target(value: Int): Int
                  targets: [Int]
                  namespace(id: Int): Namespace
                }
                type Namespace { target: Int }
                """.trimIndent(),
            )
            assertThrows<IllegalArgumentException>(result) { world.apply(world.schema.requireObjectField("Query", "source")) }
        }
    }

    @Test
    fun `evaluates object Query and argument inputs in one result`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item! @resolver(result: {local: 2})
              values: [Int]
            }
            type Item {
              local: Int!
              total(extra: Int!): Int!
                @resolver(of: "local", qf: "values", script: ${"\"\"\""}
                  var total = objectValue.local + extra;
                  for (var value : queryValue.values) {
                    if (value != null) total += value;
                  }
                  return total;
                ${"\"\"\""})
            }
            """.trimIndent(),
        )
        val input = assertIs<EngineObjectData.Sync>(world.apply(world.schema.requireObjectField("Query", "item")))
        val query = world.schema.objectOf("Query") { "values" setTo listOf(3, null, 5) }
        val total = world.schema.requireObjectField("Item", "total")

        assertEquals(14, world.apply(total, input, mapOf("extra" to 4), queryValue = query))
    }

    @Test
    fun `Query scripts preserve null and error inputs`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              source: Int
              result: Int @resolver(qf: "source", script: "queryValue.source")
            }
            """.trimIndent(),
        )
        val result = world.schema.requireObjectField("Query", "result")
        val nullQuery = world.schema.objectOf("Query") { "source" setTo null }
        val failure = EngineErrorData.of()
        val errorQuery = world.schema.objectOf("Query") { "source" setTo failure }

        assertNull(world.apply(result, queryValue = nullQuery))
        assertEquals(failure, world.apply(result, queryValue = errorQuery))
    }

    @Test
    fun `scripts bind null for whitespace-only object and Query fragments`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result: Int @resolver(of: " ", qf: " ", script: "objectValue == null && queryValue == null ? 7 : 0")
            }
            """.trimIndent(),
        )

        assertEquals(7, world.apply(world.schema.requireObjectField("Query", "result")))
    }

    @Test
    fun `scripts read selections bound by path and provider variables`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              source: Int @resolver(result: 7)
              echo(value: Int): Int @resolver(script: "value")
              result(seed: Int): Int
                @resolver(
                  of: "source local: echo(value: ${'$'}seed)"
                  qf: "fromPath: echo(value: ${'$'}fromSource) fromProvider: echo(value: ${'$'}provided)"
                  pathVars: [{name: "fromSource", path: ["source"]}]
                  providerVars: {provided: "sum(${'$'}seed, ${'$'}seed)"}
                  script: "objectValue.local + queryValue.fromPath + queryValue.fromProvider"
                )
            }
            """.trimIndent(),
        )

        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ first: result(seed: 5) second: result(seed: 9) }"), dispatcher)
            val outputs = root.keys.filter { it.field.name == "result" }.associate { key ->
                assertIs<Int>(assertIs<Arguments.Resolved>(key.arguments).fieldValues.getValue("seed")) to assertIs<Int>(root.getCell(key).value.get())
            }
            assertEquals(mapOf(5 to 22, 9 to 34), outputs)
        }
    }

    @Test
    fun `compiles variables used across both fragments and only in the Query fragment`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              source: Int! @resolver(result: 7)
              echo(value: Int!): Int! @resolver(result: "value(${'$'}value)")
              result(seed: Int!): Int!
                @resolver(
                  of: "source echo(value: ${'$'}seed)"
                  qf: "byArgument: echo(value: ${'$'}seed) byPath: echo(value: ${'$'}fromSource) byProvider: echo(value: ${'$'}provided)"
                  pathVars: [{name: "fromSource", path: ["source"]}]
                  providerVars: {provided: 3}
                  result: "sum(echo, query:byArgument, query:byPath, query:byProvider)"
                )
            }
            """.trimIndent(),
        )
        val field = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(field)
        val seed = Arguments.Variable.of(field, "seed")
        val fromSource = Arguments.Variable.of(field, "fromSource")
        val provided = Arguments.Variable.of(field, "provided")

        assertEquals(setOf(seed, fromSource, provided), resolver.declaredDependencies.variables.keys)
        assertIs<VariableDefinition.FromArgument>(resolver.declaredDependencies.variables.getValue(seed))
        assertEquals(ProviderFragment.OBJECT, assertIs<VariableDefinition.FromField>(resolver.declaredDependencies.variables.getValue(fromSource)).providerFragment)
        assertIs<VariableDefinition.FromProvider>(resolver.declaredDependencies.variables.getValue(provided))
        assertEquals("Query", resolver.declaredDependencies.queryFragment.type.name)
    }

    @Test
    fun `rejects malformed Query fragment declarations`() {
        for (declaration in listOf("qf: 7", "qf: \"missing\"", "qf: \"echo(value: ${'$'}undefined)\"")) {
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver($declaration, result: 1)
                      echo(value: Int!): Int! @resolver(result: "value(${'$'}value)")
                    }
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `evaluates scripted arguments and list inputs`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container!
                    @resolver(script: "{'values': [{'value': 2}, null, {'value': 3}]}")
                }

                type Container {
                  values: [Item]!
                  total(extra: Int!): Int!
                    @resolver(
                      of: "values { value }"
                      script: ${"\"\"\""}
                        var total = 1 + extra;
                        for (var item : objectValue.values) {
                          if (item != null) total += item.value;
                        }
                        return total;
                      ${"\"\"\""}
                    )
                }

                type Item {
                  value: Int!
                }
                """.trimIndent(),
            )
        val schema = world.schemas
        val containerField = schema.loweredSchema.requireObjectField("Query", "container")
        val container =
            assertIs<EngineObjectData.Sync>(
                world.apply(containerField),
            )
        val total = schema.loweredSchema.requireObjectField("Container", "total")

        val result = world.apply(total, container, mapOf("extra" to 4))

        assertEquals(10, result)
    }

    @Test
    fun `scalar object-field provider can supply a nested input-list location`() {
        TestWorld.fromDSL(
            schemaSDL =
                """
                extend type Query {
                  result: Int!
                    @resolver(
                      of: "source consume(value: ${'$'}value)"
                      pathVars: [{name: "value", path: ["source"]}]
                      result: "sum(consume)"
                    )
                  source: Int! @resolver(result: 7)
                  consume(value: [[Int!]!]!): Int!
                    @resolver(result: 14)
                }
                """.trimIndent(),
        )
    }

    @Test
    fun `infers argument variables and compiles explicit path variables`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "source byArg(value: ${'$'}seed) byPath(value: ${'$'}fromSource)"
                      pathVars: [{name: "fromSource", path: ["source"]}]
                      result: "sum(byArg, byPath)"
                    )
                  source: Int! @resolver(result: 7)
                  byArg(value: Int!): Int! @resolver(result: "sum(${ '$' }value)")
                  byPath(value: Int!): Int! @resolver(result: "sum(${ '$' }value)")
                }
                """.trimIndent(),
            )
        val result = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(result)
        val seed = Arguments.Variable.of(result, "seed")
        val fromSource = Arguments.Variable.of(result, "fromSource")

        assertIs<VariableDefinition.FromArgument>(resolver.declaredDependencies.variables.getValue(seed))
        assertEquals(
            ProviderFragment.OBJECT,
            assertIs<VariableDefinition.FromField>(resolver.declaredDependencies.variables.getValue(fromSource)).providerFragment,
        )
    }

    @Test
    fun `compiles provider variables from resolver test DSL`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "consume(value: ${'$'}provided) consumeOther(value: ${'$'}constant)"
                      providerVars: {
                        provided: "sum(${'$'}seed, ${'$'}seed)"
                        constant: 3
                      }
                      result: "sum(consume, consumeOther)"
                    )
                  consume(value: Int!): Int! @resolver(result: "sum(${ '$' }value)")
                  consumeOther(value: Int!): Int! @resolver(result: "sum(${ '$' }value)")
                }
                """.trimIndent(),
            )
        val result = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(result)

        assertIs<VariableDefinition.FromProvider>(
            resolver.declaredDependencies.variables.getValue(Arguments.Variable.of(result, "provided")),
        )
        assertIs<VariableDefinition.FromProvider>(
            resolver.declaredDependencies.variables.getValue(Arguments.Variable.of(result, "constant")),
        )
        assertNotNull(resolver.declaredDependencies.variablesProvider)
    }

    @Test
    fun `rejects provider variables that redefine another source`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result(value: Int!): Int!
                        @resolver(
                          of: "consume(value: ${'$'}value)"
                          providerVars: {value: 3}
                          result: "sum(consume)"
                        )
                      consume(value: Int!): Int! @resolver(result: "sum(${ '$' }value)")
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(true, exception.message.orEmpty().contains("may not redefine"))
    }

    @Test
    fun `rejects unused provider variables`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(providerVars: {unused: 3}, result: 1)
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(true, exception.message.orEmpty().contains("Unused providerVars variables"))
    }

    @Test
    fun `supports explicit null and error results`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  nullable: Int @resolver(result: null)
                  failed: Int! @resolver(result: "ERROR")
                }
                """.trimIndent(),
            )
        val root = world.resolverRegistry.createRootQueryInput()
        val nullable = world.schema.requireObjectField("Query", "nullable")
        val failed = world.schema.requireObjectField("Query", "failed")

        assertNull(world.apply(nullable, root))
        assertIs<EngineErrorData>(world.apply(failed, root))
    }

    @Test
    fun `rejects error sentinels in object-fragment arguments`() {
        assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int!
                    @resolver(
                      of: "dependency(arg: \"ERROR\")"
                      result: 1
                    )
                  dependency(arg: Int!): Int! @resolver(result: 2)
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `value expressions preserve integer null and error values`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  echo(input: Int): Int
                    @resolver(result: "value(${'$'}input)")
                  echoPath: Int
                    @resolver(of: "source", result: "value(source)")
                  source: Int @resolver(result: null)
                }
                """.trimIndent(),
            )
        val root = world.resolverRegistry.createRootQueryInput()
        val echo = world.schema.requireObjectField("Query", "echo")

        assertEquals(4, world.apply(echo, root, mapOf("input" to 4)))
        assertNull(world.apply(echo, root, mapOf("input" to null)))
        assertIs<EngineErrorData>(
            world.apply(echo, root, mapOf("input" to ArgumentResolutionError)),
        )

        val echoPath = world.schema.requireObjectField("Query", "echoPath")
        assertNull(
            world.apply(
                echoPath,
                world.schema.objectOf("Query") {
                    "source" setTo null
                },
            ),
        )
    }

    @Test
    fun `uses typename to select a scripted abstract result`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  subject: Subject!
                    @resolver(script: "{'__typename': 'Person', 'value': 9}")
                }

                interface Subject {
                  value: Int!
                }

                type Person implements Subject {
                  value: Int!
                }
                """.trimIndent(),
            )
        val subject = world.schema.requireObjectField("Query", "subject")

        val result =
            assertIs<EngineObjectData.Sync>(
                world.apply(subject),
            )

        assertEquals("Person", result.type.name)
        assertEquals(
            9,
            result.get("value"),
        )
    }

    @Test
    fun `resolves Node references using an ID argument`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  viewer(id: ID!): User!
                    @resolver(result: {id: "idFrom(${'$'}id)"})
                }

                type User implements Node
                  @nodeResolver(
                    result: [
                      {id: "user-1", result: {score: 7}},
                      {id: "user-2", result: {score: 8}}
                    ]
                  ) {
                  id: ID!
                  score: Int!
                }
                """.trimIndent(),
            )
        val schema = world.schemas
        val viewer = schema.loweredSchema.requireObjectField("Query", "viewer")
        val reference =
            assertIs<RootFieldReferenceData>(
                world.apply(viewer, arguments = mapOf("id" to "user-2")),
            )
        val node = schema.loweredSchema.requireObjectField("Query", "node")
        val user =
            assertIs<EngineObjectData.Sync>(
                world.apply(
                    node,
                    arguments = reference.arguments.fieldValues,
                    selections = schema.fragmentFrom("fragment UserResult on User { id score }").subselections,
                ),
            )

        assertEquals(
            "user-2",
            user.get("id"),
        )
        assertEquals(
            8,
            user.get("score"),
        )
        val sourceSchema = SourceSchemaAdapter(schema.loweredSchema)
        assertEquals(viewer, sourceSchema.field("Query", "viewer"))
    }

    @Test
    fun `rejects an ID result expression backed by a String argument`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  value(text: String!): ID!
                    @resolver(result: "idFrom(${'$'}text)")
                }
                """.trimIndent(),
            )
        val value = world.schema.requireObjectField("Query", "value")

        val exception =
            assertThrows<IllegalArgumentException> {
                world.apply(value, arguments = mapOf("text" to "same"))
            }

        assertEquals(true, exception.message.orEmpty().contains("requires an ID argument"))
    }

    @Test
    fun `requires result or script even though result is nullable`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      value: Int @resolver
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(true, exception.message.orEmpty().contains("requires exactly one of result or script"))
    }

    @Test
    fun `preserves resolver-owned fields in object results`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  item: Item! @resolver(script: "{'computed': 2}")
                }

                type Item {
                  computed: Int! @resolver(result: 1)
                }
                """.trimIndent(),
            )
        val item = world.schema.requireObjectField("Query", "item")

        val result = assertIs<EngineObjectData.Sync>(world.apply(item))

        assertEquals(2, result.outputValue("computed"))
    }

    @Test
    fun `scripts execute subqueries with variables and response aliases`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              outer(seed: Int!): Int! @resolver(script: ${"\"\"\""}
                if (objectValue != null || queryValue != null) return 0;
                return query('{ renamed: inner(value: ${'$'}value) }', {'value': seed + 0H}).renamed + 1;
              ${"\"\"\""})
              inner(value: Int!): Int! @resolver(qf: "base", script: "queryValue.base + value")
              base: Int! @resolver(result: 7)
            }
            """.trimIndent(),
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ outer(seed: 6) }"), dispatcher)
            assertEquals(14, root.getCell(root.keys.single()).value.get())
        }
    }

    @Test
    fun `requires explicit output projection for aliased subquery objects`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item @resolver(script: "{'n': 7}")
              copy: Item @resolver(script: "query('{ item { absent: n } }').item")
              projected: Item @resolver(script: "{'n': query('{ item { absent: n } }').item.absent}")
              unaliased: Item @resolver(script: "query('{ item { __typename n } }').item")
            }
            type Item { n: Int absent: Int }
            """.trimIndent(),
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ copy { n } projected { n } unaliased { n } }"), dispatcher)
            val failure = assertIs<ErrorEngineResult>(root.getCell(root.keys.single { it.field.name == "copy" }).value.get())
            val cause = assertIs<IllegalArgumentException>(failure.errorData.cause)
            assertEquals(true, cause.message.orEmpty().contains("aliased selection 'absent' for field 'n'"))
            for (name in listOf("projected", "unaliased")) {
                val item = assertIs<ObjectEngineResult>(root.getCell(root.keys.single { it.field.name == name }).value.get())
                assertEquals(7, item.getCell(item.keys.single()).value.get())
            }
        }
    }
}

private fun TestWorld.apply(
    field: ViaductSchema.ObjectField,
    input: EngineObjectData.Sync = resolverRegistry.createRootQueryInput(),
    arguments: Map<String, Any?> = emptyMap(),
    selections: SelectionForest = selectionForestOf(),
    queryValue: EngineObjectData.Sync = resolverRegistry.createRootQueryInput(),
): EngineOutputData? =
    when (val grounded = Arguments.of(field, arguments)) {
        Arguments.Error -> EngineErrorData.of()
        is Arguments.Resolved ->
            runBlocking {
                resolverRegistry.resolver(field)(
                    input = input,
                    queryValue = queryValue,
                    arguments = grounded,
                    selections = selections,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }
        else -> error("Direct resolver application requires ground arguments")
    }
