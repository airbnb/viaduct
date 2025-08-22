@file:Suppress("unused")

package viaduct.api

class GoodFixture {
    val f = 1
}

class BadFixtureNotAccessible private constructor() {
    val f = 2
}

class BadFixtureNoNoArgs(arg: Int) {
    val f = arg
}

interface BadFixtureInterface

class BadFixtureKey
