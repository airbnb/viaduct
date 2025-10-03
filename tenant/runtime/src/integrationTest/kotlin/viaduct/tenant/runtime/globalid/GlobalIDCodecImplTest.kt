@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.globalid

import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.internal.MissingReflection
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl

class GlobalIDCodecImplTest : KotestPropertyBase() {
    private lateinit var codec: GlobalIDCodecImpl
    private lateinit var nodeObjectTypes: List<GraphQLNamedType>
    private lateinit var nonNodeObjectTypes: List<GraphQLNamedType>
    private val mirror = ReflectionLoaderImpl { name -> Class.forName("viaduct.tenant.runtime.globalid.$name").kotlin }

    @Suppress("UNCHECKED_CAST")
    private fun nodeObjectType(name: String): Type<NodeObject> = mirror.reflectionFor(name) as Type<NodeObject>

    @BeforeEach
    fun setup() {
        val schema = GlobalIdFeatureAppTest.schema
        codec = GlobalIDCodecImpl(mirror)

        val allTypes = schema.schema.allTypesAsList.filter { !it.name.startsWith("_") }

        // Types that can have GlobalIDs created (GraphQL object types that implement Node)
        nodeObjectTypes = allTypes
            .filterIsInstance<GraphQLObjectType>()
            .filter { gjType -> gjType.interfaces.any { it.name == "Node" } }

        // Types that should fail GlobalID creation (everything else)
        nonNodeObjectTypes = allTypes.filter { gjType ->
            // Non-object types (interfaces, inputs, etc.)
            gjType !is GraphQLObjectType ||
                // Object types that don't implement Node
                !gjType.interfaces.any { it.name == "Node" }
        }
    }

    @Test
    fun `test serialize and deserialize`(): Unit =
        runBlocking {
            Arb.Companion.element(nodeObjectTypes).forAll { gjType ->
                val type = nodeObjectType(gjType.name)
                val globalId = GlobalIDImpl(type, Arb.Companion.string().bind())
                val globalId2 = codec.deserialize<NodeObject>(codec.serialize(globalId))
                globalId == globalId2
            }
        }

    @Test
    fun `test globalid on non-NodeObject types`(): Unit =
        runBlocking {
            Arb.Companion.element(nonNodeObjectTypes).checkAll { gjType ->
                val type = try {
                    nodeObjectType(gjType.name)
                } catch (_: MissingReflection) {
                    return@checkAll
                }
                try {
                    GlobalIDImpl(type, Arb.Companion.string().bind())
                    markFailure()
                } catch (_: IllegalArgumentException) {
                    markSuccess()
                }
            }
        }

    @Test
    fun `test multiple delimiters in internalID`(): Unit =
        runBlocking {
            val type = User.Reflection
            val globalId = GlobalIDImpl(type, "internalid:1:2:3:4:5")
            val globalId2 = codec.deserialize<User>(codec.serialize(globalId))
            Assertions.assertEquals(globalId, globalId2)
            Assertions.assertEquals("internalid:1:2:3:4:5", globalId2.internalID)
        }

    @Test
    fun `test malformed globalID`(): Unit =
        runBlocking {
            val exception =
                Assertions.assertThrows(IllegalArgumentException::class.java) { codec.deserialize<User>("123") }
            Assertions.assertTrue(exception.message!!.contains("to be a Base64-encoded string with the decoded format"))
        }
}
