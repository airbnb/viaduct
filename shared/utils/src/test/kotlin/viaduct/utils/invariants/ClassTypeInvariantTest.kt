package viaduct.utils.invariants

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ClassTypeInvariantTest {
    class User(
        val name: String,
        val age: Int
    )

    class Product(
        val name: String,
        val price: Double
    ) : HasInvariantAssertion {
        override fun assertInvariants() {
            require(price > 0) { "Price must be greater than zero" }
        }
    }

    @Test
    fun `check should pass for correct class`() {
        val user = User("Alice", 30)
        val userInvariant = ClassTypeInvariant(User::class)
        assertDoesNotThrow { userInvariant.check(user) }
    }

    @Test
    fun `check should throw for incorrect class`() {
        val user = User("Alice", 30)
        val productInvariant = ClassTypeInvariant(Product::class)
        assertThrows(InvariantException::class.java) { productInvariant.check(user) }
    }

    @Test
    fun `check should pass for correct type`() {
        val user = User("Alice", 30)
        val userInvariant = ClassTypeInvariant(typeInfo<User>())
        assertDoesNotThrow { userInvariant.check(user) }
    }

    @Test
    fun `check should throw for incorrect type`() {
        val user = User("Alice", 30)
        val productInvariant = ClassTypeInvariant(typeInfo<Product>())
        assertThrows(InvariantException::class.java) { productInvariant.check(user) }
    }

    @Test
    fun `check should pass for class with invariant assertion`() {
        val product = Product("Sample Product", 10.0)
        val productInvariant = ClassTypeInvariant(Product::class)
        assertDoesNotThrow { productInvariant.check(product) }
    }

    @Test
    fun `check should throw for class with failing invariant assertion`() {
        val product = Product("Sample Product", -10.0)
        val productInvariant = ClassTypeInvariant(Product::class)
        assertThrows(InvariantException::class.java) { productInvariant.check(product) }
    }

    @Test
    fun `check should throw for Map with incorrect value types`() {
        val map = mapOf("key1" to "value1", "key2" to "value2")
        val mapInvariant = ClassTypeInvariant(typeInfo<Map<String, Int>>())
        assertThrows(InvariantException::class.java) { mapInvariant.check(map) }
    }

    @Test
    fun `check should pass for HashMap with mixed generic types`() {
        val map = HashMap<String, Any>(hashMapOf("key1" to "value1", "key2" to 2))
        val fieldTypeInvariant = ClassTypeInvariant(
            typeInfo<HashMap<String, Any>>()
        )
        assertDoesNotThrow { fieldTypeInvariant.check(map) }
    }
}
