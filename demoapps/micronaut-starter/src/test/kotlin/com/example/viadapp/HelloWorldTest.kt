package com.example.viadapp

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micronaut.context.ApplicationContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelloWorldTest {
    private lateinit var context: ApplicationContext
    private lateinit var viaduct: Viaduct

    @BeforeAll
    fun setUp() {
        context = ApplicationContext.run()
        viaduct = context.getBean(Viaduct::class.java)
    }

    @AfterAll
    fun tearDown() {
        context.close()
    }

    private fun execute(query: String) = viaduct.execute(ExecutionInput.create(operationText = query))

    @Test
    fun `greeting resolver returns Hello World`() {
        val result = execute("query { greeting }")
        result.errors shouldBe emptyList()
        result.getData().shouldNotBeNull()["greeting"] shouldBe "Hello, World!"
    }

    @Test
    fun `author resolver returns Brian Kernighan`() {
        val result = execute("query { author }")
        result.errors shouldBe emptyList()
        result.getData().shouldNotBeNull()["author"] shouldBe "Brian Kernighan"
    }

    @Test
    fun `both fields resolve in one query`() {
        val result = execute("query { greeting author }")
        result.errors shouldBe emptyList()
        val data = result.getData().shouldNotBeNull()
        data["greeting"] shouldBe "Hello, World!"
        data["author"] shouldBe "Brian Kernighan"
    }

    @Test
    fun `throwException resolver surfaces error and returns null field`() {
        val result = execute("query { throwException }")
        result.errors.shouldNotBeNull()
        result.errors!!.isNotEmpty() shouldBe true
        val data = result.getData().shouldNotBeNull()
        data["throwException"] shouldBe null
    }

    @Test
    fun `invalid syntax returns parse error`() {
        val result = execute("query { }")
        result.errors.shouldNotBeNull()
        result.errors!!.isNotEmpty() shouldBe true
        result.errors!![0].message shouldContain "Invalid syntax"
    }

    @Test
    fun `undefined field returns validation error`() {
        val result = execute("query { thisIsNotAField }")
        result.errors.shouldNotBeNull()
        result.errors!!.isNotEmpty() shouldBe true
        result.errors!![0].message shouldContain "thisIsNotAField"
    }

    @Test
    fun `whitespace-only query returns parse error`() {
        val result = execute("query {  }")
        result.errors.shouldNotBeNull()
        result.errors!!.isNotEmpty() shouldBe true
        result.errors!![0].message shouldContain "Invalid syntax"
    }
}
