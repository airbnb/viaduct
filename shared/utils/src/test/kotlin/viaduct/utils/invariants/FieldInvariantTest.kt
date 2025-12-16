package viaduct.utils.invariants

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FieldInvariantTest {
    class User(
        val name: String,
        val age: Int
    )

    class Address(
        val street: String,
        val city: String
    )

    class UserWithAddress(
        val name: String,
        val age: Int,
        val address: Address
    )

    class Container<K, V>(
        val map: Map<K, V>
    )

    @Test
    fun `check should pass for correct class and fields`() {
        val user = User("Alice", 30)
        val userInvariant = FieldInvariant(
            User::class,
            mapOf(
                "name" to FieldInvariant(String::class),
                "age" to FieldInvariant(Int::class)
            )
        )
        assertDoesNotThrow { userInvariant.check(user) }
    }

    @Test
    fun `check should throw if instance is of incorrect class`() {
        val userInvariant = FieldInvariant(String::class)
        val user = User("Alice", 30)
        assertThrows(InvariantException::class.java) { userInvariant.check(user) }
    }

    @Test
    fun `check should throw if field does not exist`() {
        val userInvariant = FieldInvariant(
            User::class,
            mapOf("nonexistentField" to FieldInvariant(String::class))
        )
        val user = User("Alice", 30)
        assertThrows(InvariantException::class.java) { userInvariant.check(user) }
    }

    @Test
    fun `check should pass for nested class and fields`() {
        val address = Address("123 Main St", "Wonderland")
        val userWithAddress = UserWithAddress("Alice", 30, address)
        val userWithAddressInvariant = FieldInvariant(
            UserWithAddress::class,
            mapOf(
                "name" to FieldInvariant(String::class),
                "age" to FieldInvariant(Int::class),
                "address" to FieldInvariant(
                    Address::class,
                    mapOf(
                        "street" to FieldInvariant(String::class),
                        "city" to FieldInvariant(String::class)
                    )
                )
            )
        )
        assertDoesNotThrow { userWithAddressInvariant.check(userWithAddress) }
    }

    @Test
    fun `check should throw if nested field does not exist`() {
        val userWithAddressInvariant = FieldInvariant(
            UserWithAddress::class,
            mapOf(
                "name" to ClassTypeInvariant(String::class),
                "age" to ClassTypeInvariant(Int::class),
                "address" to FieldInvariant(
                    Address::class,
                    mapOf("nonexistentField" to FieldInvariant(String::class))
                )
            )
        )
        val address = Address("123 Main St", "Wonderland")
        val userWithAddress = UserWithAddress("Alice", 30, address)
        assertThrows(InvariantException::class.java) { userWithAddressInvariant.check(userWithAddress) }
    }

    @Test
    fun `check should throw null pointer exception if nullable properties are null`() {
        class User(
            val name: String?,
            val age: Int?
        )
        val user = User(null, null)
        val userInvariant = FieldInvariant(
            User::class,
            mapOf(
                "name" to FieldInvariant(String::class),
                "age" to FieldInvariant(Int::class)
            )
        )
        assertThrows(InvariantException::class.java) { userInvariant.check(user) }
    }

    @Test
    fun `check should throw null pointer exception for nullable recursive fields`() {
        class Foo(
            val self: Foo?
        )
        val foo = Foo(null)
        val fooInvariant = FieldInvariant(
            Foo::class,
            mapOf("self" to FieldInvariant(Foo::class))
        )
        assertThrows(InvariantException::class.java) { fooInvariant.check(foo) }
    }

    @Test
    fun `check should pass for lists with invariant type`() {
        class Item(
            val value: Int
        )

        class Container(
            val items: List<Item>
        )
        val container = Container(listOf(Item(1), Item(2), Item(3)))
        val containerInvariant = FieldInvariant(
            Container::class,
            mapOf(
                "items" to
                    FieldTypeInvariant(List::class, listOf(FieldInvariant(Item::class)))
            )
        )
        assertDoesNotThrow { containerInvariant.check(container) }
    }

    @Test
    fun `check should pass for lists with abstract inner type`() {
        abstract class Animal(
            val name: String
        )

        class Dog(
            name: String
        ) : Animal(name)

        class Zoo(
            val animals: List<Animal>
        )
        val zoo = Zoo(listOf(Dog("Buddy")))
        val zooInvariant = FieldInvariant(
            Zoo::class,
            mapOf("animals" to FieldTypeInvariant(List::class, listOf(FieldInvariant(Animal::class))))
        )
        assertDoesNotThrow { zooInvariant.check(zoo) }
    }

    class Product(
        val name: String,
        val price: Double
    ) : HasInvariantAssertion {
        override fun assertInvariants() {
            require(price > 0) { "Price must be greater than zero" }
        }
    }

    @Test
    fun `check should throw if price is not greater than zero`() {
        val product = Product("Sample Product", -10.0)
        val productInvariant = FieldInvariant(Product::class)
        assertThrows(InvariantException::class.java) { productInvariant.check(product) }
    }

    @Test
    fun `check should pass for map with correct keys and values`() {
        val map = mapOf("key1" to "value1", "key2" to 42)
        val mapInvariant = FieldInvariant(
            Map::class,
            mapOf(
                "key1" to ClassTypeInvariant(String::class),
                "key2" to ClassTypeInvariant(Int::class)
            )
        )
        assertDoesNotThrow { mapInvariant.check(map) }
    }

    @Test
    fun `check should throw if map has incorrect value type`() {
        val map = mapOf("key1" to "value1", "key2" to "wrongType")
        val mapInvariant = FieldInvariant(
            Map::class,
            mapOf(
                "key1" to FieldInvariant(String::class),
                "key2" to FieldInvariant(Int::class)
            )
        )
        assertThrows(InvariantException::class.java) { mapInvariant.check(map) }
    }

    @Test
    fun `check should throw if map is missing required key`() {
        val map = mapOf("key1" to "value1")
        val mapInvariant = FieldInvariant(
            Map::class,
            mapOf(
                "key1" to FieldInvariant(String::class),
                "key2" to FieldInvariant(Int::class)
            )
        )
        assertThrows(InvariantException::class.java) { mapInvariant.check(map) }
    }

    @Test
    fun `check should pass for HashMap with generic types`() {
        val map = hashMapOf("key1" to "value1", "key2" to "value2")
        val container = Container(map)
        val fieldInvariant = FieldInvariant(
            Container::class,
            mapOf("map" to ClassTypeInvariant(typeInfo<HashMap<String, String>>()))
        )
        assertDoesNotThrow { fieldInvariant.check(container) }
    }

    @Test
    fun `check should throw for HashMap with incorrect generic types`() {
        val map = hashMapOf("key1" to 1, "key2" to 2)
        val container = Container(map)
        val fieldInvariant = FieldInvariant(
            typeInfo<Container<String, String>>(),
            mapOf("map" to FieldInvariant(typeInfo<HashMap<String, String>>()))
        )
        assertThrows(InvariantException::class.java) { fieldInvariant.check(container) }
    }

    @Test
    fun `check should pass for HashMap with mixed generic types`() {
        val map = hashMapOf("key1" to "value1", "key2" to 2)
        val container = Container(map)
        val fieldInvariant = FieldInvariant(
            Container::class,
            mapOf("map" to FieldInvariant(typeInfo<HashMap<String, Any>>()))
        )
        assertDoesNotThrow { fieldInvariant.check(container) }
    }

    @Test
    fun `check should throw for HashMap with incorrect mixed generic types`() {
        val map = hashMapOf("key1" to "value1", "key2" to 2)
        val container = Container(map)
        val fieldInvariant = FieldInvariant(
            typeInfo<Container<String, String>>(),
            mapOf("map" to FieldInvariant(typeInfo<HashMap<String, String>>()))
        )
        assertThrows(InvariantException::class.java) { fieldInvariant.check(container) }
    }
}
