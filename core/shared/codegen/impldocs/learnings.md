# Intro
We've learned a lot of intricacies of Kotlin-to-bytecode translation.  We probably should've started documenting these earlier, but we didn't.  I'm starting a document now under the theory of "better late than never".

# kotlinp

`kotlinp` is a tool for examining JVM class files similar to Java's `javap`.  It provides Kotlin type information that is encoded in JVM annotations and thus is not readily observable using `javap` output.

Unfortunately I haven't yet found `kotlinp` included in any pre-built Kotlin distribution.  Here are instructions I got from JetBrains on building it yourself (courtesy Alexander Udalov):

> We have a tool in the [Kotlin repository](https://github.com/JetBrains/kotlin) that prints all metadata found in the `class/kotlin_module` file, it is located in `libraries/tools/kotlinp`. Unfortunately, it is only an internal tool, it is not a part of any stable Kotlin release yet. To use it, you'll need to clone and compile the Kotlin project. Here are instructions in case you need it:
> 
> 1) Configure the project according to ReadMe
> 2) Run `./gradlew :tools:kotlinp-jvm:shadowJar`
> 3) Run kotlinp via `java -cp $KOTLIN/libraries/tools/kotlinp/jvm/build/libs/kotlinp-jvm-shadow.jar org.jetbrains.kotlin.kotlinp.jvm.Main` (replace `$KOTLIN` with the path to the project). Use `-verbose` to output a bit more information.

(This worked for me on Sept 2025 off the Kotlin master branch using openjdk version "1.8.0_362".)


# Default Parameters

Kotlin does not use method overloading to handle default parameters the way Java does.  Instead, if a function `f` has default parameters, then Kotlin will generate a synthetic function `f$default` that uses a bit vector to determine where defaults are needed. When the compiler compiles a call to `f` where default param-values are needed, the call will be compiled into a call to `f$default`, passing placeholder values where defaults are needed, and using the bit vector to indicate which are placeholder values.

Let's look at an example:

```
class Foo {
    fun bar(a: Double = 0.0, b: Double? = null, c: Double, d: Double = 1.0): Double {
       return a + (b?:0.0) + c + d
    }

    fun baz(): Double = bar(a = 1.0, c = 2.0)
}
```

Here's what the compiler generates for `bar`:

```
public final double bar(double, java.lang.Double, double, double);
  descriptor: (DLjava/lang/Double;DD)D
  flags: ACC_PUBLIC, ACC_FINAL
  Code: ... omitted for brevity ...

public static double bar$default(Foo, double, java.lang.Double, double, double, int, java.lang.Object);
  descriptor: (LFoo;DLjava/lang/Double;DDILjava/lang/Object;)D
  flags: ACC_PUBLIC, ACC_STATIC, ACC_SYNTHETIC
  Code:
       0: iload         8
       2: iconst_1
       3: iand
       4: ifeq          9
       7: dconst_0
       8: dstore_1
       9: iload         8
      11: iconst_2
      12: iand
      13: ifeq          18
      16: aconst_null
      17: astore_3
      18: iload         8
      20: bipush        8
      22: iand
      23: ifeq          29
      26: dconst_1
      27: dstore        6
      29: aload_0
      30: dload_1
      31: aload_3
      32: dload         4
      34: dload         6
      36: invokevirtual #29       // Method bar:(DLjava/lang/Double;DD)D
      39: dreturn
```

Note that `foo$default` takes three more arguments than does `foo`:

* The first argument to `foo$default` is the `this` argument to `foo`.  `foo$default` is a static method: we're not sure why, we think it has to do with how overrides work, but it doesn't impact our code generation so we haven't pursued the matter.
* The second-to-last argument to `foo$default` is an integer that's treated as bit vector indicating for which parameters a default value is needed.  There is a bit in this vector for all parameters, even those not needing a default.  You can see this on line 20, where the constant `8 == 1<<3` is used to check the bit vector for the 4th argument (`d`), even though the 3rd argument (`c`) takes no default.  Where a function has more than 32 parameters, the Kotlin compiler will add multiple of these bit-vector parameters to accommodate.
* The last argument is also a bit of a mystery to us.  If `Foo` was an `open` class, then `foo$default` would check to ensure that this argument is `null`, and if not throw an exception with the message "Super calls with default arguments not supported in this target."  We emulate this behavior, and we always pass null for this last argument when compiling calls.

Here's what the compiler generates for `baz`:

```
// fun baz(): Double = bar(a = 1.0, c = 2.0)

public final double baz();
  descriptor: ()D
  flags: ACC_PUBLIC, ACC_FINAL
  Code:
       0: aload_0
       1: dconst_1
       2: aconst_null
       3: ldc2_w        #31       // double 2.0d
       6: dconst_0
       7: bipush        10        // "0000000000001010" in binary
       9: aconst_null
      10: invokestatic  #34       // Method bar$default:(LFoo;DLjava/lang/Double;DDILjava/lang/Object;)D
      13: dreturn
```

Note on line 7 it passes a bit-vector indicating that we've omitted values for `b` and `d`.  On lines 2 and 6, placeholder values are provided for those parameters.  The `null` on line 2 is a placeholder, it doesn't serve as the default value for `b` - all default values including that for `b` are provided by the body of `foo$default`.

Again, `$default` functions are _only_ generated when a function has one or more default values in its parameters.

## Constructors with default values

Kotlin does something similar for constructors that take default values, with the following variation:

* For constructors, there's no option to use a distinguished name (`$default`) for the constructor that handles default-value passing, so overloading is used instead.  In particular, the type of the last argument to the synthetic, default-value handling constructor is `kotlin.jvm.internal.DefaultConstructorMarker`.
* Bit vectors are used as described above.
* If every argument to the primary constructor has a default, Kotlin also generates a no-arg constructor for the type that calls the `DefaultConstructorMarker` with a bit vector indicating all defaults are needed.  We believe this is intended to support dependency injection and other places that assume there exists a no-arg constructor.

Here's an example:

```
open class Bar(val a: Boolean = false) {
    companion object {
        fun mkBar1() = Bar()
        fun mkBar2() = Bar(true)
    }
}
```

which generates the following constructors:

```
// Primary constructor provided by developer
public Bar(boolean);
  descriptor: (Z)V
  flags: ACC_PUBLIC
  Code:
       0: aload_0
       1: invokespecial #9        // Method java/lang/Object."<init>":()V
       4: aload_0
       5: iload_1
       6: putfield      #13       // Field a:Z
       9: return

// No-arg constructor generated because all params to primary constructor are optional
public Bar();
  descriptor: ()V
  flags: ACC_PUBLIC
  Code:
       0: aload_0
       1: iconst_0
       2: iconst_1
       3: aconst_null
       4: invokespecial #22       // Method "<init>":(ZILkotlin/jvm/internal/DefaultConstructorMarker;)V
       7: return

// Synthetic constructor called when default values are needed
public Bar(boolean, int, kotlin.jvm.internal.DefaultConstructorMarker);
  descriptor: (ZILkotlin/jvm/internal/DefaultConstructorMarker;)V
  flags: ACC_PUBLIC, ACC_SYNTHETIC
  Code:
       0: iload_2
       1: iconst_1
       2: iand
       3: ifeq          8
       6: iconst_0
       7: istore_1
       8: aload_0
       9: iload_1
      10: invokespecial #18       // Method "<init>":(Z)V
      13: return
```

Since all arguments are optional, a no-arg constructor is generated as well as the `DefaultConstructorMarker` constructor.  The no-arg constructor is _not_ marked as synthetic, bolstering the hypothesis that the point is to pretend to other software that the programmer intended for this to exist.

One thing to note: the `DefaultConstructorMarker` constructor has a whole bunch of bytecode (lines 0-9) before it calls the programmer-provided constructor.  Javassist does not support doing this: if you don't put a `this(...)` call as the first statement of a constructor body, Javassist will insert a call to `super()`, which doesn't do what we want.  Thus, in our version of the generated code, instead of the call to `this(...)` on line 10, we insert (i.e., "inline") the entire body of the primary constructor there, which is functionally equivalent.

Let's look at the calls to these constructors:

```
  // fun mkBar1() = Bar()
  public final Bar mkBar1();
    descriptor: ()LBar;
    flags: ACC_PUBLIC, ACC_FINAL
    Code:
         0: new           #16                 // class Bar
         3: dup
         4: iconst_0
         5: iconst_1
         6: aconst_null
         7: invokespecial #19                 // Method Bar."<init>":(ZILkotlin/jvm/internal/DefaultConstructorMarker;)V
        10: areturn

  // fun mkBar2() = Bar(true)
  public final Bar mkBar2();
    descriptor: ()LBar;
    flags: ACC_PUBLIC, ACC_FINAL
    Code:
         0: new           #16                 // class Bar
         3: dup
         4: iconst_1
         5: invokespecial #23                 // Method Bar."<init>":(Z)V
         8: areturn
```

Since `mkBar1` needs the default value for the primary constructor's parameter, it calls the `DefaultConstructorMarker` version.  Line 4 pushes a placeholder value for that parameter, line 5 the bit-vector saying we need the default-value for that parameter, and line 6 a null value for the `DefaultConstructorMarker` parameter.  `mkBar2` doesn't need a default value, so it calls the primary constructor directly.


# JVM types for enumeration-typed Kotlin properties
In both Java and Kotlin, the declaration of an enumeration class can implicitly define anonymous subclasses.  For example:

```
enum class ProtocolState {
    WAITING {
        override fun signal() = TALKING
    },

    TALKING {
        override fun signal() = WAITING
    };

    abstract fun signal(): ProtocolState
}
```

In this example, anonymous subclasses are generated for `WAITING` and `TALKING`.  Wherever a Kotlin program promises to take a `ProtocolState` value, it needs to be willing to take these anonymous subclasses.

With this in mind, consider the following Kotlin source file:

```
enum class E { ONE, TWO }

interface I<R, out S, in T>

open class H

data class D (
  var f1: E,
  var f2: List<E>,
  var f3: List<E?>,
  var f4: Array<E>,
  var f5: List<List<E>>,
  var f6: Map<E, E>,
  var f7: Map<List<E>, Map<E, List<E>>>,
  var f8: I<E, E, E>,
  var f9: I<List<E>, List<E>, List<E>>,

  var g2: List<String>,
  var g5: List<List<String>>,
  var g7: Map<List<String>, Map<String, List<String>>>,
  var g9: I<List<String>, List<String>, List<String>>,

  var h2: List<H>,
  var h5: List<List<H>>,
)
```

If you compile this class and use `javap` to examine its class file for D, you'll see the following:

```
javap -p D.class
Compiled from "EnumSig.kt"
public final class D {
  private E f1;
  private java.util.List<? extends E> f2;
  private java.util.List<? extends E> f3;
  private E[] f4;
  private java.util.List<? extends java.util.List<? extends E>> f5;
  private java.util.Map<E, ? extends E> f6;
  private java.util.Map<java.util.List<E>, ? extends java.util.Map<E, ? extends java.util.List<? extends E>>> f7;
  private I<E, ? extends E, ? super E> f8;
  private I<java.util.List<E>, ? extends java.util.List<? extends E>, ? super java.util.List<? extends E>> f9;

  private java.util.List<java.lang.String> g2;
  private java.util.List<? extends java.util.List<java.lang.String>> g5;
  private java.util.Map<java.util.List<java.lang.String>, ? extends java.util.Map<java.lang.String, ? extends java.util.List<java.lang.String>>> g7;
  private I<java.util.List<java.lang.String>, ? extends java.util.List<java.lang.String>, ? super java.util.List<java.lang.String>> g9;

  private java.util.List<? extends H> h2;
  private java.util.List<? extends java.util.List<? extends H>> h5;
  ... and a lot more ...
```

As you can see, the Kotlin compiler introduces co- and contravariant signature attributes for the backing fields of properties to properly and exhaustively take into account the possibility of subclasses.  In the case of final classes (like `String`), no such attributes are introduced.  But in the case of our invented `H` class which is open, and the case of enumerations which are implicitly open because of the "anonymous subclass" issue.

(Not shown here is the following: in "output" positions - such as the return-type of the getters for these properties - no such variance-transformations are applied.  In "input" positions - such as the arguments to constructors and copy-functions - these variance-transformations _are_ applied.)

This use-case illustrates a challenge we've been facing in our overall architecture.  We are reusing Kotlin's `KmProperty` metadata type to represent the Kotlin properties that will be representing GraphQL fields.  Kotlin's `KmProperty` abstraction is intended to handle all of the enumeration cases captured in EnumSig.kt and then some.  However, our GraphQL-to-Kotlin transformation only depends on `List<T>` and none of the other cases.  (BTW, this analysis helps us understand why `KmProperty`'s property giving the type of a property is called `returnType` and not just `type`: it is giving the type of the property in "output" positions, which isn't necessarily the same as it would be for "input" positions.)

Our intent is to handle the `List<T>` cases exactly as Kotlin does, but to not build (and therefore have to test) the more general case.  Thus, for now, our `DataClassBuilder.addProperty` function will throw an error if it's asked to handle a generic type other than `List<T>`.

Finally, the `kotlinp` program does not provide variance information the way `javap` does, so it's hard to know what Kotlin is doing in its metadata.  We're going to assume that it's matching what it does in the JVM signatures, but eventually we need to write a Kotlin metadata comparator, which will test this assumption.

Reading:

* [Kotlin docs](https://kotlinlang.org/docs/java-to-kotlin-interop.html#variant-generics)
* [Blog post](https://chao2zhang.medium.com/jvmsuppresswildcards-the-secret-sauce-to-your-sandwich-style-generics-b0093aa5979d)


# DefaultImpls
Kotlin allows interface functions to be non-abstract with default implementations.  Viaduct takes advantage of this Kotlin feature in a number of places:
* Input type classes implement `ViaductInputType`, which has a function with a default implementation.
* Object type interfaces implement `ViaductGeneratedObject`, which has several functions with default implementations. Object type interfaces themselves also declare functions with default implementations.
* Object-type Value classes implement the Object type interface, and thus need to pick up default implementations from those. (They also extend the `ValueBase` class, which in turn implements `ViaductGeneratedObject`. However, in this case, as is described in the last example below, the Value classes will pick up the default implementations of `ViaductGeneratedObject` via standard inheritance from `ValueBase`.)
To generate bytecode for input type classes, object type interfaces, and object type Value classes, we need to follow Kotlin's protocols for implementing these default implementations.

Kotlin has two protocols for implementing default implementations:
1. Generate a nested `DefaultImpls` class - this is the default Kotlin compiler behavior, and is compatible with older Java versions.
2. Generate `default` methods - this is possible by using the `-Xjvm-default` compiler flag, and is compatible with Java 8 and later versions.

See this [Kotlin blog post](https://blog.jetbrains.com/kotlin/2020/07/kotlin-1-4-m3-generating-default-methods-in-interfaces/) for more details on the available compiler flags.

Currently this library adopts option 1, which is described in more detail below. However, we should consider switching to option 2 if we're able to use the `-Xjvm-default=all` flag for Treehouse since it results in simpler and easier to maintain code.


Consider the following Kotlin code:
```kotlin
interface Animal {
    fun isMammal(): Boolean
    fun numEyes() = 2
    fun populationSize(isExtinct: Boolean = false): Int
}
```

This gets compiled into the bytecode equivalent of:
```java
public interface Animal {
    public boolean isMammal();
    public int numEyes();
    public int populationSize(Boolean isExtinct);

    public static final class DefaultImpls {
        // numEyes has a default implementation
        public static String numEyes(Animal $this) {
            return 2;
        }

        // populationSize has a parameter with a default value
        public static boolean populationSize$default(Animal $this, Boolean isExtinct, int b, Object o) {
            // ... $default method body
        }
    }
}
```

There are abstract methods for all 3 functions in the base interface. However, if anything requires a method body, a nested `DefaultImpls` class is generated.
In the example above, numEyes has a default implementation and so it ends up in `DefaultImpls`. `populationSize` does not have a default implementation, but it does
have a parameter with a default value, so the synthetic `populationSize$default` method also appears in `DefaultImpls`.

Let's say there's another interface that implements this interface:
```kotlin
interface Mammal : Animal {
    override fun isMammal() = true
}
```

The compiled equivalent is:
```java
public interface Mammal implements Animal {
    public boolean isMammal();

    public static final class DefaultImpls {
        // Mammal.isMammal has a default implementation
        public static boolean isMammal(Mammal $this) {
            return true;
        }

        // Inherited from Animal.DefaultImpls, but $this is Mammal, not Animal
        public static int numEyes(Mammal $this) {
            return Animal.DefaultImpls.numEyes($this);
        }
    }
}
```

`Mammal.DefaultImpls` inherits `numEyes` from `Animal.DefaultImpls`, and its implementation just calls the parent interface's implementation.
However, it does not inherit `populationSize$default`.

Now let's consider a class that implements an interface with a nested `DefaultImpls`:
```kotlin
class Tiger : Mammal {
    override fun populationSize(isExtinct: Boolean) = 5000
}
```

The compiled equivalent is (irrelevant things like the constructor are omitted from the examples below):
```java
public class Tiger implements Mammal {
    public boolean isMammal() {
        return Mammal.DefaultImpls.isMammal(this);
    }

    public int numEyes() {
        return Mammal.DefaultImpls.numEyes(this);
    }

    public int populationSize(boolean isExtinct) {
        return 5000;
    }
}
```

`isMammal` and `numEyes` both appear in the classfile for `Tiger`, and they call the implementations in `Mammal.DefaultImpls`.
This differs from methods inherited from an abstract class, which don't appear in the classfile of the child class and the inheritance is
purely handled at runtime, which we can see in the more complex relationship below:
```kotlin
abstract class SeaCreature : Animal {
    override fun populationSize(isExtinct: Boolean): Int { ... }
}

class Whale : SeaCreature(), Mammal
```

The compiled equivalent is:
```java
public abstract class SeaCreature implements Animal {
    public int numEyes() {
        return Animal.DefaultImpls.numEyes(this);
    }

    public int populationSize(boolean isExtinct) {
        // implementation
    }
}

public class Whale extends SeaCreature implements Mammal {
    public boolean isMammal() {
        return Mammal.DefaultImpls.isMammal(this);
    }
}
```

What's interesting here is that `Whale` only has 1 method in its classfile, which is `isMammal`, inherited from `Mammal.DefaultImpls`.
It does not have `numEyes` or `populationSize` because these are both inherited from `SeaCreature` at runtime only.

This diamond-shaped inheritance structure is exactly what we have with Value classes.

### Empty constructor in DefaultImpls class
The `DefaultImpls` class generated by the Kotlin compiler doesn't have a constructor. However, Javassist automatically inserts an empty constructor
to the `DefaultImpls` classes we generate. There's no simple way to bypass this and thus far hasn't caused any problems.

# Synthetic Accessors
Consider this nested class configuration:

```kotlin
class Foo private constructor() {
  class Builder {
    fun build() = Foo()
  }
}
```

We could use these classes to construct an instance of `Foo` by calling `Foo.Builder().build()`.

When `build` is invoked, it calls into a private method of a different class, which the kotlin language
permits because of their nested relationship. Though kotlin (and java) allow nested classes to access private
members of a containing class, the JVM doesn't actually support this natively -- trying to _directly_ access
a private member will throw a runtime error:

```
IllegalAccessError: tried to access method Foo.<init>() from class Foo$Builder
```

kotlinc works around this by generating "synthetic accessors". These are public methods in the bytecode
that can relay invocations to private members. If written in kotlin, a synthetic accessor would look like:

```kotlin
class Foo private constructor() {
  // synthetic accessor
  public constructor(marker: kotlin.jvm.internal.DefaultConstructorMarker) : this()
}
```

With this synthetic accessor in place, when a nested object wants to access a private member of its containing class,
it can do so through the accessor. Though synthetic accessors are not normally written by humans, we can visualize
them using kotlin:

```kotlin
// Step 3: private member is invoked
class Foo private constructor() {
  // Step 2: public synthetic accessor proxies to a private member
  public constructor(marker: DefaultConstructorMarker?) : this()

  class Builder {
    // Step 1: nested class calls the public synthetic accessor
    fun build() = Foo(null)
  }
}
```

# Synthetic Bridge Methods
Consider these classes and how they use type parameters:
```kotlin
interface Iface<T> {
  fun read(): T
  fun write(t: T): Boolean
}
class Impl : Iface<String> {
  override fun read(): String = "a"
  override fun write(t: String): Boolean = true
}
```
When `Impl` is compiled, the generated class needs to be usable by callers that have a concrete `Impl`
reference and expect String values, as well as callers that have an `Iface` reference and expect type-erased
Object values

To serve both kinds of callers, kotlinc will generate additional "synthetic bridge" methods that can cast
between Object and the parameterized type. We can visualize what these methods would look like if they were written
in kotlin:

```kotlin
// type parameter has been erased
class Impl : Iface<Object> {
  fun read(): String = "a"
  fun read(): Object = "a" as Object                    // bridge method

  fun write(t: String): Boolean = true
  fun write(t: Object): Boolean = write(t as String)    // bridge method
}
```

Some things to notice:
- the bridge methods are the ones with erased types, not the ones with type info
- in the case of the `read` method, the bridged method is different only by output type. Attempting this in
  java or kotlin would normally generate a "conflicting overloads" compiler error, though this is allowed
  in bytecode because the method calls can be resolved using the const pool
  

# Variance of "inputs"

Our library includes significant code devoted to computing the `KmType` metadata for GraphQL fields.  This code includes logic for computing the "variance" of type parameters.  "Variance" is what is designated by the keywords `out` and `in`, where `out` means roughly "covariance" (ie, subtypes allowed) and `in` means "contravariance" (ie, supertypes allowed).  In the Kotlin metadata library, the third form of variance, _invariance_ is also represented explicitly.

GraphQL fields are used in "output positions" (e.g., returned by getters) and "input positions" (i.e., arguments to things).  There are two major "input positions" for which we generate code: the arguments to setters, and also the arguments to the `Continuation` functions we generate for suspending getter functions.

Java bytecode uses special attributes called "signatures" to capture variance information.  You might remember `? super` -- that's similar to Kotlin's `in` -- and `? extends` is similar to `out`.

What we noticed in codegen for the continuation-inputs of suspend functions is that in Java explicit variances are put into arguments.  The next subsection below gives examples illustrating this point.  Right now, our code generator forces the output of variances in Java signatures by putting them into the `KmType`s for those arguments.  If we didn't do this, our Km-to-Ct translator would have to recapitulate Kotlin's logic, which would be complicated.  So we took a shortcut here.  This means that in our `@Metadata` attributes for Kotlin code, we sometimes include redundant variance specifications for things like function and constructor arguments, but that doesn't seem to cause any harm.

Let's look at the specific way in which we modify variances.

## Variance of suspend-function continuations

The issue raised here is very apparent as we look at what happens when the return-type of a Kotlin suspend function gets translated into the _input_ type for the suspend function's `Continuation` type parameter.  Consider the following Kotlin file:

```kotlin
interface Node
interface NodeId<out T : Node>

abstract class AbstractNode : Node
class FinalNode : Node

class FooFactory {
    suspend fun asNode(): Node = TODO()
    suspend fun asAbstractNode(): NodeId<AbstractNode> = TODO()
    suspend fun asFinalNode(): FinalNode = TODO()
    suspend fun asNodeId(): NodeId<Node> = TODO()
    suspend fun asFinalNodeId(): NodeId<FinalNode> = TODO()

    suspend fun list1A(): List<Node> = TODO()
    suspend fun list1F(): List<FinalNode> = TODO()

    suspend fun list2A(): List<NodeId<AbstractNode>> = TODO()
    suspend fun list2F(): List<NodeId<FinalNode>> = TODO()

    suspend fun mapFF(): Map<String, String> = TODO()
    suspend fun mapFA(): Map<String, Node> = TODO()
    suspend fun mapF2A(): Map<String, NodeId<Node>> = TODO()
}
```
Running `javap` against what the Kotlin compiler generates for `FooFactory` yields:

```
public final class FooFactory {
  public FooFactory();
  public final java.lang.Object asNode(kotlin.coroutines.Continuation<? super Node>);
  public final java.lang.Object asAbstractNode(kotlin.coroutines.Continuation<? super NodeId<? extends AbstractNode>>);
  public final java.lang.Object asFinalNode(kotlin.coroutines.Continuation<? super FinalNode>);
  public final java.lang.Object asNodeId(kotlin.coroutines.Continuation<? super NodeId<? extends Node>>);
  public final java.lang.Object asFinalNodeId(kotlin.coroutines.Continuation<? super NodeId<FinalNode>>);

  public final java.lang.Object list1A(kotlin.coroutines.Continuation<? super java.util.List<? extends Node>>);
  public final java.lang.Object list1F(kotlin.coroutines.Continuation<? super java.util.List<FinalNode>>);

  public final java.lang.Object list2A(kotlin.coroutines.Continuation<? super java.util.List<? extends NodeId<? extends AbstractNode>>>);
  public final java.lang.Object list2F(kotlin.coroutines.Continuation<? super java.util.List<? extends NodeId<FinalNode>>>);

  public final java.lang.Object mapFF(kotlin.coroutines.Continuation<? super java.util.Map<java.lang.String, java.lang.String>>);
  public final java.lang.Object mapFA(kotlin.coroutines.Continuation<? super java.util.Map<java.lang.String, ? extends Node>>);
  public final java.lang.Object mapF2A(kotlin.coroutines.Continuation<? super java.util.Map<java.lang.String, ? extends NodeId<? extends Node>>>);
}
```
As a reminder, `? extends` in the JVM corresponds to `out` variance in Kotlin, while `? super` corresponds to `in`.

The pattern is this: if the return type of a suspend function admits subtypes (e.g., `List<Node>` or `NodeId<Node>`), then the argument to the corresponding continuation needs to have an `out` variance -- and this is true "all the way in" (e.g., `List<List<Node>>`).  However, if the return type does not admit subtypes, then the corresponding argument has no explicit variance.

We apply the same pattern when generating `KmType`s for input arguments.  We do this in two places, in the continuation arguments just discussed, and for the setter function of `Builder` classes.  In the type-translation functions, you'll see the argument `isInput`: this tells the translator whether we'd like it to generate a type for an output position (ie, a return type) or an input position (ie, an argument type).

As suggested earlier, we haven't dug fully into this behavior.  In particular, we're not sure where the Kotlin compiler puts variances in _its_ metadata -- it seems to be explicit about them for function arguments, but not for constructor arguments (see the `includeVariance` argument to some functions in 
`KmMetadataDiff.kt`).  Thus we're not sure if what we're seeing in the Java signatures simply reflects the variances directly as appear in the Kotlin metadata, or if there are further rules guiding Java signatures.  As a practical matter, however, we haven't experienced any issues with the Kotlin compiler _consuming_ the metadata we're generating, so it doesn't seem like minor differences matter.


# Known limitations
We implemented exactly what we needed to in order to support Viaduct GRTs, and nothing more. This library explicitly does not support (not an exhaustive list):
* Companion objects - we began implementation but never completed it. [This PR](https://git.musta.ch/airbnb/treehouse/pull/711091) which removes the incomplete implementation can be used as a reference should we ever decide to add support.
* Custom getters / setters on interface properties - these should also appear in `DefaultImpls`. In fact right now we don't support properties in interfaces at all because of this.
* Override methods with a covariant return type - the compiler generates synthetic [bridge methods](https://docs.oracle.com/javase/tutorial/java/generics/bridgeMethods.html) when there's method override with a covariant return type, which we don't handle. This is not specific to interfaces and also applies to inheritance from parent classes.


# Little things

### Javassist resolves constructor overloading at classfile-creation time:
if you give it code to compile that references
a non-existent constructor variant, it won't complain until you attempt to write out the classfile.  (Might also be
true for methods - haven't tested.)

### Default values for introspection schema

The parser uses "literal" `InputValueWithState` variants, and thus `graphql.language.Value` for default values of arguments.  However, the introspective-schema inserted at schema-creation time uses "external" variants, and actual `Boolean` values (the only arguments in the introspective schema are `includeDeprecatedTypes`, which are `Boolean`).

### Known issue about Kotlin boolean properties & StringTemplate
* [StringTemplate](https://github.com/antlr/stringtemplate4/blob/master/doc/introduction.md#introduction) is used to [bytecode testers](https://git.musta.ch/airbnb/treehouse/tree/master/projects/viaduct/build-src/src/main/kotlin/com/airbnb/viaduct/cli/bytecode/testers) to generate test code. StringTemplate is using Java's getter methods to access the properties of the model. For example,
```kotlin
class DataTypeModelImpl(
    ...
    val testFnName: String,
    ...
    val objectType: Boolean
    ...
)
```
```StringTemplate
suspend fun <def.testFnName>(ctx: KotlinTesterContext) {
    ...
    <if(def.objectType)> <! call DataTypeModelImpl object's getObjectType() !>
    <resolveFieldInternalOnlyDoNotUseTest()>
    <endif>
    ...
}
```
When StringTemplate encounters `def.objectType`, it will attempt to find the `objectType` property within the `def` object. In its search, StringTemplate will look for `getObjectType()`, `isObjectType()`, and `hasObjectType()` methods and attempt to retrieve the value of `objectType`. Considering the provided Kotlin code, the compiler will automatically create a `getObjectType()` method for the `objectType` property. StringTemplate can then successfully resolve `def.objectType` using this generated method.

However, something interesting behavior occurs when a boolean property in Kotlin code begins with the `is` prefix, as shown below:
```kotlin
class DataTypeModelImpl(
    ...
    val testFnName: String,
    ...
    val isObjectType: Boolean
    ...
)
```
```StringTemplate
suspend fun <def.testFnName>(ctx: KotlinTesterContext) {
    ...
    <if(def.isObjectType)> <! try to call DataTypeModelImpl object's getIsObjectType()/isIsObjectType()/hasIsObjectType() !>
    <resolveFieldInternalOnlyDoNotUseTest()>
    <endif>
    ...
}
```

When Kotlin compiles, it creates an `isObjectType()` method. However, StringTemplate, in its attempts to call `getIsObjectType()`, `isIsObjectType()`, or `hasIsObjectType()` methods, cannot find them because they don't exist. StringTemplate will then try accessing the field `isObjectType` directly, in accordance with its documentation on [accessing properties of model objects](https://github.com/antlr/stringtemplate4/blob/master/doc/introduction.md#accessing-properties-of-model-objects). However, Kotlin blocks direct access to this field, restricting access to something through the `isObjectType()` method. This leaves StringTemplate unable to access `isObjectType`.

