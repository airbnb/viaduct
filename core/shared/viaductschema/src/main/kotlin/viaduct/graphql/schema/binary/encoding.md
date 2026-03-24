# Binary GraphQL Schema Encoding Format

## Overview

This document describes the binary encoding format for `ViaductSchema` instances. The format is designed to serialize GraphQL schemas into a compact, efficiently-decodable binary representation suitable for build-time tooling and runtime schema loading.  This document describes that format in detail.  The very last section of this document also discusses the testing strategy.

### Design Goals

1. **Compactness**: Minimize file size through deduplication and efficient encodings
2. **Fast Decoding**: Support single-pass reading with minimal heap allocations
3. **Self-Contained**: Include all schema information needed for runtime operation
4. **Not for Archival**: This is a working format, not meant for long-term storage (use GraphQL SDL for that)

### Key Design Patterns

The encoding uses several recurring patterns throughout:

#### 1. Index-Based References
Instead of repeating strings or complex structures, the format maintains lookup tables (identifiers, source locations, type expressions, constants) and references them by index. Indices are limited to 20 bits (≤ 1,048,576 entries), with the upper 12 bits of each 32-bit word available for flags and metadata.

#### 2. RefPlus Encoding

A "RefPlus" is the format's universal mechanism for attaching metadata to list elements. It packs a 20-bit reference (to an identifier, source location, or type definition) together with 2-3 metadata flags and a list continuation bit.

The format uses six RefPlus variants, each specialized for a different context. All variants share the same bit layout structure but assign different meanings to the flags based on what's being encoded:

- **Bit 31** (`END_OF_LIST_BIT`): List continuation (0 = more elements follow, 1 = last element)
- **Bit 30** (`FLAG_0_BIT`): Context-dependent, primarily `hasAppliedDirectives`
- **Bit 29** (`FLAG_1_BIT`): Context-dependent (see table below)
- **Bit 28** (`FLAG_2_BIT`): Context-dependent, used for `hasArguments` in field contexts
- **Bits 20-27**: Unused (reserved for future use)
- **Bits 0-19**: Index into appropriate table (identifiers, source locations, type definitions)

The meaning of the index portion, FLAG_1_BIT, and FLAG_2_BIT depends on context:

| Context | Index References | FLAG_1_BIT Meaning | FLAG_2_BIT Meaning |
|---------|-----------------|-------------------|-------------------|
| Type definition extensions | Source locations | `hasImplementedTypes` | Unused (0) |
| Input-like fields | Identifiers (field/arg names) | `hasDefaultValue` | Unused (0) |
| Output fields | Identifiers (field names) | `hasDefaultValue` | `hasArguments` |
| Applied directives | Identifiers (directive names) | `hasArguments` | Unused (0) |
| Enum values | Identifiers (value names) | Unused (0) | Unused (0) |
| Applied directive args | Identifiers (arg names) | Unused (0) | Unused (0) |

This uniform structure allows the same RefPlus pattern to serve multiple encoding needs while enabling decoders to share bit-manipulation and validation logic across all contexts.

#### 3. List Encoding with Continuation Bits
Lists are encoded as sequences of elements where each element's `END_OF_LIST_BIT` (bit 31) indicates whether more elements follow:

- `0` in bit 31: More elements follow
- `1` in bit 31: This is the last element

Two mechanisms are used based on circumstances to represent the empty list (since the `END_OF_LIST_BIT` cannot indicate an empty list):

- A RefPlus occuring before the word uses `FLAG_0_BIT` or `FLAG_1_BIT` to indicated the presence of a list (e.g., in many cases `FLAG_0_BIT` is used to represent "`hasAppliedDirectives`", ie, whether or not a schema element has any applied directives).
- The special value `EMPTY_LIST_MARKER` (= -1) is used in place of a first element to indicate that the list is empty.

#### 4. Section-Based Organization
The file is organized into sequential sections, each aligned to 4-byte boundaries:

1. Header (metadata about all other sections)
2. Identifiers (sorted ASCII strings)
3. Definition Stubs (identifier index + kind code for each definition)
4. Source Locations (sorted UTF-8 strings)
5. Simple Constants (kind-code-prefixed UTF-8 strings)
6. Compound Constants (lists and input objects)
7. Type Expressions (deduplicated type references)
8. Root Types (query, mutation, subscription)
9. Definitions (directives and type definitions, interleaved by name)

Each section (except the header) begins with a 4-byte magic number for validation. These magic numbers are included in the byte size counts reported in the header but are NOT included in any item counts.

#### 5. Section Magic Numbers
For validation and error detection, each section begins with a unique 32-bit magic number:

| Section              | Magic Number | ASCII Mnemonic |
|---------------------|--------------|----------------|
| Identifiers         | `0x49444E54` | "IDNT"         |
| Definition Stubs    | `0x53545542` | "STUB"         |
| Source Locations    | `0x534C4F43` | "SLOC"         |
| Simple Constants    | `0x53434F4E` | "SCON"         |
| Compound Constants  | `0x43434F4E` | "CCON"         |
| Type Expressions    | `0x54455850` | "TEXP"         |
| Root Types          | `0x524F4F54` | "ROOT"         |
| Definitions         | `0x44454653` | "DEFS"         |

When reading a section, the decoder verifies the expected magic number before processing section content. This helps detect file corruption and format mismatches.

### 6. Core Encoding Patterns

The format achieves its compactness and regularity by composing a small set of reusable encoding patterns. Understanding these patterns reveals the conceptual structure underlying the entire format.

#### Pattern 1: RefPlus - Universal Metadata Attachment

RefPlus is the format's fundamental mechanism for attaching metadata flags to list elements. Rather than simply packing an index with some flags, RefPlus provides a uniform way to augment references with contextual information.

**Structure**: A 32-bit word with a 20-bit reference portion (bits 0-19) that can point to identifiers, source locations, or type definitions, plus up to four metadata bits:
- **FLAG_2_BIT** (bit 28): Context-dependent, used for `hasArguments` in field contexts
- **FLAG_1_BIT** (bit 29): Context-dependent meaning (see table in section above)
- **FLAG_0_BIT** (bit 30): Usually `hasAppliedDirectives`
- **END_OF_LIST_BIT** (bit 31): List continuation indicator

The format uses six RefPlus variants, each specialized for a different context. All variants share the same bit layout structure but interpret the index and flags differently based on what's being encoded. This uniform structure allows decoders to share validation and bit-manipulation logic across all RefPlus uses.

#### Pattern 2: Continuation-Based Lists

Variable-length sequences are encoded without length prefixes using continuation bits. Each list element's RefPlus has an END_OF_LIST_BIT indicating whether more elements follow.

**Empty list handling** uses two mechanisms:
1. A flag in a preceding RefPlus can indicate "list not present"
2. `EMPTY_LIST_MARKER` (-1) as first element indicates "list present but empty"

This pattern appears throughout the format: extension lists, field lists, type reference lists, applied directive lists, and argument lists all use identical continuation-based encoding.

#### Pattern 3: Extension Lists

Most type definitions (Enum, Input, Interface, Object, Union) follow an identical extension list structure. Each extension consists of:
- A `DefinitionRefPlus` word encoding source location and flags
- Optional applied directives list (if flag set)
- Optional implemented interfaces list (if flag set, Interface/Object only)
- A members list (type-specific: fields, enum values, or type references)

The extension list pattern is encoded generically—only the "members" portion varies by type kind. This allows five different type kinds to share the same fundamental encoding structure.

#### Pattern 4: Field Encoding (Unified Pattern)

Fields and arguments use a unified encoding pattern with a consolidated RefPlus that supports both default values and arguments:

**Input-like fields** (used for directive arguments, field arguments, and input type fields):
- RefPlus (with `hasDefaultValue` flag) → optional applied directives → type expression → optional default value

**Output fields** (used for interface and object fields):
- RefPlus (with `hasArguments` flag) → optional applied directives → type expression → optional arguments list

The consolidated FieldRefPlus supports both flags simultaneously using separate bits (bit 29 for `hasDefaultValue`, bit 28 for `hasArguments`), allowing a single RefPlus type to handle all field encoding contexts. RefPlus encodes the name and flags, followed by common metadata (directives, type), then context-specific "extras" (default value and/or arguments). The arguments themselves are input-like fields, creating a natural compositional structure.

#### Pattern 5: Applied Directive Encoding

Applied directives represent directive applications and appear as optional metadata on many schema elements (type extensions, fields, enum values, arguments). Wherever they appear, they use identical encoding:
- RefPlus (directive name + flags)
- Optional arguments list (if flag set)
- Each argument: name RefPlus + constant reference

The uniform structure means decoders can factor out applied directive decoding into a single reusable component.

#### Pattern Composition

These five patterns compose to build the entire format:
- **Extension lists** use **continuation-based lists** of extensions
- Each extension uses **RefPlus** for metadata and may contain **applied directives**
- Extensions contain **field encoding** for their members
- Fields may contain **applied directives** and (for output fields) arguments using **input-like field** encoding

This compositional structure means the format is built from ~5 primitives rather than dozens of distinct encoding strategies. Recognizing these patterns aids both understanding and implementation.

---

## File Format Version Encoding

**Encoding** - 32-bit integer:

  - Bits 0-7: Minor version
  - Bits 8-15: Major version
  - Bits 16-31: Unused (must be zero)

**Current Version** - `0x00000003` (major=0, minor=3)

---

## Section 1: Header

The header provides metadata about the file and all subsequent sections.

### Structure

All values are signed 32-bit little-endian integers:

```
Offset  Field                           Description
------  -----                           -----------
0x00    Magic Number                    Always 0xA75F2B1C
0x04    File Version                    Current: 0x00000002
0x08    Max String Length               Maximum length in bytes of any identifier, source location, or simple constant string
0x0C    Identifier Count                Number of identifiers
0x10    Identifier Section Bytes        Size of identifiers section in bytes (including 4-byte magic number)
0x14    Definition Stub Count           Number of definition stubs (directive count + type def count)
0x18    Source Location Count           Number of source locations + 1 (includes null placeholder)
0x1C    Source Location Section Bytes   Size of source locations section in bytes (including 4-byte magic number)
0x20    TypeExpr Section Bytes          Size of type expressions section in bytes (including 4-byte magic number)
0x24    TypeExpr Count                  Number of type expressions
0x28    Directive Count                 Number of directive definitions
0x2C    TypeDef Count                   Number of type definitions
0x30    Simple Constants Count          Number of simple constants + 1 (includes null placeholder)
0x34    Simple Constants Section Bytes  Size of simple constants section in bytes (including 4-byte magic number)
0x38    Compound Constants Count        Number of compound constants + 1 (includes EMPTY_LIST_MARKER)
0x3C    Compound Constants Section Bytes Size of compound constants section in bytes (including 4-byte magic number)
```

**Total Header Size**: 64 bytes (16 words)

---

## Section 2: Identifiers

The identifiers section contains all GraphQL identifiers used in the schema: type names, field names, directive names, argument names, enum value names, etc. Identifiers are:
- Sorted lexicographically
- Encoded as ASCII (7-bit characters only)
- Null-terminated

### Encoding

The section begins with the magic number `0x49444E54` ("IDNT"), followed by identifiers:

Each identifier is encoded as:

1. ASCII characters (bytes with values 0-127)
2. Null terminator (`0x00`)

### Properties

- **Sorted**: Identifiers appear in lexicographic order
- **Indexed**: Referenced elsewhere by zero-based index
- **Limited**: Maximum 2²⁰ (≈1M) identifiers
- **Padded**: Section padded with `0x00` bytes to 4-byte boundary

### Example

```
Identifier      Bytes                           Index
----------      -----                           -----
"User"          55 73 65 72 00                  0
"firstName"     66 69 72 73 74 4E 61 6D 65 00   1
"id"            69 64 00                        2
"skip"          73 6B 69 70 00                  3
```

---

## Section 3: Definition Stubs

The definition stubs section provides kind information for type and directive definitions. This section contains one entry per definition (directive or type definition), allowing the decoder to create "shell" objects for each definition before reading the full definitions section.

### Encoding

The section begins with the magic number `0x53545542` ("STUB"), followed by definition stub entries:

Each definition stub is a single 32-bit word (StubRefPlus) encoding:

```
Bits    Field               Description
----    -----               -----------
0-19    Identifier Index    Index into identifiers section
20-23   Unused              Reserved (must be zero)
24-31   Kind Code           Definition kind (see table below)
```

### Kind Codes

The kind code (bits 24-31) indicates the type of definition:

| Code          | Value  | Kind       | Description           |
|---------------|--------|------------|-----------------------|
| `K_DIRECTIVE` | `0x80` | Directive  | Directive definition  |
| `K_ENUM`      | `0x90` | Enum       | Enumeration type      |
| `K_INPUT`     | `0xA0` | Input      | Input object type     |
| `K_INTERFACE` | `0xB0` | Interface  | Interface type        |
| `K_OBJECT`    | `0xC0` | Object     | Object type           |
| `K_SCALAR`    | `0xD0` | Scalar     | Scalar type           |
| `K_UNION`     | `0xE0` | Union      | Union type            |

### Properties

- **Count**: Number of stubs = directive count + type def count (from header)
- **No Padding**: Naturally aligned (word-sized entries)

Note: The encoder writes stubs in identifier-sorted order, but the decoder does not depend on this ordering.

### Example

Given identifiers from Section 2:
```
Stub Entry      Word (hex)      Identifier Index    Kind Code   Meaning
----------      ----------      ----------------    ---------   -------
0               0x80000003      3                   0x80        Directive "skip"
1               0xD0000002      2                   0xD0        Scalar "id"
2               0xC0000000      0                   0xC0        Object "User"
```

---

## Section 4: Source Locations

Source locations track where schema elements were defined in the original SDL. This section contains unique `SourceLocation.sourceName` values.

### Structure

- **Entry 0**: Special null placeholder (single `0x00` byte)
- **Entry 1+**: Sorted UTF-8 strings, each null-terminated

### Encoding

The section begins with the magic number `0x534C4F43` ("SLOC"), followed by a null placeholder and source locations:

Each source location string:
1. UTF-8 encoded characters
2. Null terminator (`0x00`)

### Properties

- **UTF-8**: Full Unicode support (unlike ASCII-only identifiers)
- **Indexed**: Referenced by zero-based index (0 = null)
- **Padded**: Section padded with `0x00` bytes to 4-byte boundary

Note: The encoder writes source locations in lexicographic order for deterministic output, but the decoder does not depend on this ordering.

### Special Cases

- **Null Source Location**: Index `0` (the `0x00` placeholder byte)
- **Empty String**: If present, appears at index `1` (just one `0x00` byte)

---

## Section 5: Simple Constants

Simple constants represent scalar and enum constant values as kind-code-prefixed UTF-8 strings. These appear in default values and directive arguments.

### Structure

The section begins with the magic number `0x53434F4E` ("SCON"), followed by simple constants:

Each simple constant is encoded as:
1. **Kind code byte**: Indicates the GraphQL value type (see table below)
2. **UTF-8 string content**: The value's string representation
3. **Null terminator**: `0x00` byte

- **Entry 0**: Null value (`K_NULL_VALUE` + `0x00`)
- **Entry 1+**: Kind-code-prefixed UTF-8 strings

### Kind Codes

Kind codes use the upper nibble (bits 4-7) to indicate value type. Lower nibble (bits 0-3) must be zero.

| Code   | Value  | GraphQL Type                              | Example                    |
|--------|--------|-------------------------------------------|----------------------------|
| `K_NULL_VALUE`    | `0x10` | NullValue                      | `0x10 0x00` (null)         |
| `K_INT_VALUE`     | `0x20` | IntValue (Int/Long/Byte/Short) | `0x20 "42" 0x00` (42)      |
| `K_FLOAT_VALUE`   | `0x30` | FloatValue                     | `0x30 "3.14" 0x00` (3.14)  |
| `K_STRING_VALUE`  | `0x40` | StringValue (String/ID/Date/etc.) | `0x40 "hello" 0x00` ("hello") |
| `K_BOOLEAN_VALUE` | `0x50` | BooleanValue                   | `0x50 "true" 0x00` (true)  |
| `K_ENUM_VALUE`    | `0x60` | EnumValue                      | `0x60 "ACTIVE" 0x00` (enum value) |

All kind codes (`0x10`-`0x60`) are valid single-byte UTF-8 characters, allowing the kind code + content to be treated as a Kotlin String during encoding.

### String Content Representation

After the kind code, values are represented as:

- **Strings**: Raw string value (no quotes or escaping)
- **Numbers**: Decimal representation (`42`, `3.14`)
- **Booleans**: `true` or `false`
- **Enums**: Unquoted enum value name
- **Null**: Empty string (just kind code + terminator)

### Properties

- **Deduplicated**: Each unique kind+content combination appears once
- **Self-describing**: Kind code allows decoding without type information
- **UTF-8**: Full Unicode support for content
- **Indexed**: Part of combined constants index space (see below)
- **Padded**: Section padded with `0x00` bytes to 4-byte boundary

Note: The encoder writes simple constants in lexicographic order (by kind-code-prefixed string) for deterministic output, but the decoder does not depend on this ordering.

### Special Cases

- **Null Value**: Index `0` contains `K_NULL_VALUE` + `0x00` (kind code with empty content)
- **Empty String**: Would appear as `K_STRING_VALUE` + `0x00` (kind code with empty content)

---

## Section 6: Compound Constants

Compound constants represent list and input object constant values. This section shares an index space with simple constants.

### Combined Index Space

Constants (both simple and compound) share a unified index space:

- Indices `0` to `N-1`: Simple constants (N = simple constants count)
- Indices `N` to `N+M-1`: Compound constants (M = compound constants count)

**Entry N** (first compound constant): Special `EMPTY_LIST_MARKER` (`-1`) representing both empty lists and empty input objects.

### Structure

The section begins with the magic number `0x43434F4E` ("CCON"), followed by the `EMPTY_LIST_MARKER` followed by encoded compound constants.

### Encoding List Constants

Lists like `[1, 2, 3]` are encoded as sequences of constant references:

```
Word    Field                       Description
----    -----                       -----------
0       First Element Reference     Constant index + IS_LIST_CONSTANT (bit 30 set)
1..n-1  Middle Element References   Constant indices (bit 30 clear, bit 31 clear)
n       Last Element Reference      Constant index + END_OF_LIST_BIT (bit 31 set)
```

**Flags**:

- **Bit 30** (`IS_LIST_CONSTANT`): Set only on first element (distinguishes lists from input objects)
- **Bit 31** (`END_OF_LIST_BIT`): Set only on last element

Empty lists use the `EMPTY_LIST_MARKER` at index N.

### Encoding Input Object Constants

Input objects like `{name: "Alice", age: 42}` are encoded as sequences of field-value pairs:

```
Word        Field                   Description
----        -----                   -----------
0           Field Name 1            Identifier index (bit 30 clear, bit 31 clear)
1           Field Value 1           Constant index
2           Field Name 2            Identifier index (bit 31 clear or set if last)
3           Field Value 2           Constant index
...
2n-2        Field Name n            Identifier index | END_OF_LIST_BIT (bit 31 set)
2n-1        Field Value n           Constant index
```

**Distinguishing Lists from Input Objects**:

- Lists: First word has `IS_LIST_CONSTANT` (bit 30) set
- Input Objects: First word has bit 30 clear

Empty input objects use the `EMPTY_LIST_MARKER` at index N.

### Properties

- **No Padding**: Naturally aligned (word-sized entries)
- **Type-Agnostic**: Same physical representation can represent different types (e.g., `[123]` as `[Int]` or `[String]`)
- **No Forward References**: A constant at index `i` can only reference constants with indices `< i`. This allows single-pass decoding

Note: The encoder writes compound constants in a manner that ensures deterministic output, but the decoder does not depend on any aspect of this ordering _other_ than the fact that it contains no forward references.

---

## Section 7: Type Expressions

Type expressions describe the types of fields and arguments (e.g., `String`, `[Int]!`, `[[User!]]`). The section deduplicates type expressions - each unique type expression appears once.

The section begins with the magic number `0x54455850` ("TEXP"), followed by type expressions.

### Type Expression Components

A GraphQL type expression has three components:
1. **Base Type**: The underlying type definition (e.g., `String`, `User`)
2. **Base Nullability**: Whether the base type is nullable
3. **List Wrappers**: Zero or more list wrappers, each with its own nullability

Examples:
- `String` → base: String, nullable: true, wrappers: none
- `String!` → base: String, nullable: false, wrappers: none
- `[String]` → base: String, nullable: true, wrappers: [nullable]
- `[String!]!` → base: String, nullable: false, wrappers: [non-nullable]

### Encoding: One or Two Words

Type expressions use either 1 or 2 words depending on list complexity:
- **One word**: List depth 0-2 (covers most cases)
- **Two words**: List depth 3-27

#### First Word (Always Present)

```
Bits    Field                   Description
----    -----                   -----------
0-19    Base Type Index         Index into identifiers section
20-27   Unused                  Reserved (must be zero)
28-30   Code                    Encoding pattern (see below)
31      Base Type Nullability   0 = non-nullable, 1 = nullable
```

#### Code Field (Bits 28-30)

For list depths 0-2, the code directly encodes the list pattern:

| Code  | Pattern  | Bit Vector | GraphQL Example |
|-------|----------|------------|-----------------|
| `000` | -        | `<>`       | `T` (no list)   |
| `001` | `[?]`    | `<1>`      | `[T]`           |
| `010` | `[?]!`   | `<0>`      | `[T]!`          |
| `011` | `[[?]]`  | `<11>`     | `[[T]]`         |
| `100` | `[[?]]!` | `<01>`     | `[[T]]!`        |
| `101` | `[[?]!]` | `<10>`     | `[[T]!]`        |
| `110` | `[[?]!]!`| `<00>`     | `[[T]!]!`       |
| `111` | Two-word | N/A        | (See below)     |

**Note**: In bit vectors, bit 0 (LSB) represents the outermost wrapper.
- `0` = non-nullable
- `1` = nullable

#### Second Word (When Code = 111)

For list depths 3-27:

```
Bits    Field               Description
----    -----               -----------
0-(d-1) List Nullability    Nullability bits for each wrapper
d-26    Unused              Reserved (must be zero)
27-31   List Depth          Depth value (3-27)
```

Where `d` is the list depth. Bit 0 (LSB) is the outermost wrapper.

### Properties

- **Deduplicated**: Each unique type expression appears once
- **Indexed**: Referenced by zero-based index
- **No Padding**: Naturally aligned (4 or 8 bytes each)

Note: The encoder may order type expressions by usage frequency for determinstic output, but the decoder accesses them only by index and does not depend on any particular ordering.

---


## Section 8: Root Types

The root types section identifies the query, mutation, and subscription root types. This is a fixed-size section of exactly 4 words (16 bytes).

### Structure

The section begins with the magic number `0x524F4F54` ("ROOT"), followed by root type indices:

```
Word    Field                   Description
----    -----                   -----------
0       Magic Number            Always 0x524F4F54
1       Query Type Index        Identifier index or UNDEFINED_ROOT_MARKER
2       Mutation Type Index     Identifier index or UNDEFINED_ROOT_MARKER
3       Subscription Type Index Identifier index or UNDEFINED_ROOT_MARKER
```

### Values

Each word contains either:

- **Identifier Index** (masked with `IDX_MASK`): Points to an Object type identifier
- **`UNDEFINED_ROOT_MARKER`** (`-1`): Indicates this root type is not defined

### Constraints

- All three can be undefined (legacy schemas); however, if any are defined, than `Query` must be defined.
- Root types must be Object types (not Interface, Union, etc.)
- No padding needed (naturally aligned)

---

## Section 9: Definitions

The definitions section encodes both directive definitions and type definitions. Each definition begins with a name reference word that identifies which definition is being encoded, allowing the decoder to match definition content with the corresponding stub regardless of ordering.

The section begins with the magic number `0x44454653` ("DEFS"), followed by definitions.

**Definition Ordering**: All directive definitions are encoded before any type definitions. This ensures that when decoding applied directives on type definitions (or their fields, arguments, etc.), the referenced directive definition has always been decoded and populated.

Within the directive definitions, directives are encoded in topological order based on their dependencies. A directive A depends on directive B if A has @B applied to any of its arguments. This ensures that when decoding applied directives on directive definition arguments, the referenced directive definition is available. The GraphQL spec prohibits circular directive references, so a valid topological ordering always exists.

### General Structure

Each definition begins with a **Name Reference Word**:

- **Bits 0-19**: Identifier index for the definition's name
- **Bits 20-31**: Unused (must be zero)

This name reference allows the decoder to look up the corresponding definition stub (created during definition stubs section decoding) regardless of the order in which definitions appear.

### Directive Definitions

Each directive definition consists of (in order):

#### 1. Name Reference Word
- **Bits 0-19**: Identifier index for the directive name
- **Bits 20-31**: Unused (must be zero)

#### 2. RefPlus Word
- **Bits 0-19**: Source location index
- **Bit 29**: Always 0 (directives don't have `hasImplementedTypes`)
- **Bit 30**: `hasAppliedDirectives` (false - directives can **not** be annotated with other directives according to graphql)
- **Bit 31**: Always 1 (`END_OF_LIST_BIT` - directives aren't in lists)

#### 3. Directive Info Word

```
Bits    Field                 Description
----    -----                 -----------
0       Repeatable            0 = non-repeatable, 1 = repeatable
1-19    Location Bits         One bit per allowed location
20-30   Unused                Reserved (must be zero)
31      hasArguments          0 = no args, 1 = has args
```

**Location Bits**: For each `Directive.Location` enum value at ordinal `i`, bit `1 + i` indicates whether the directive is allowed at that location.

#### 4. Arguments List (Optional)
Present only if `hasArguments` flag is set. Each argument uses input-like field encoding (see below).

### Type Definitions

Each type definition begins with a **Name Reference Word**:

- **Bits 0-19**: Identifier index for the type name
- **Bits 20-31**: Unused (must be zero)

Type definitions are then structured around GraphQL's extension mechanism. Each type consists of:

1. **Extension list**: One or more extensions (base definition + any SDL extensions)
2. **Global list** (for certain kinds of type defs): List that apply to the entire type

#### Extension Encoding

Each extension begins with:

**RefPlus Word**:

- **Bits 0-19**: Source location index
- **Bit 29**: `hasImplementedTypes` (Interface/Object only: does this extension add interfaces?)
- **Bit 30**: `hasAppliedDirectives` (does this extension have directives applied?)
- **Bit 31**: `END_OF_LIST_BIT` (0 = more extensions follow, 1 = last extension)

Followed by:

- **Applied Directives List** (if `hasAppliedDirectives` is set)
- **Extension-Specific Data** (by type kind, see below)

### Common Type Definition Structure

Most type definitions follow a three-level hierarchy that leverages the extension list pattern:

**Level 1 - Type Definition**: Name and kind (from identifiers section)

**Level 2 - Extensions**: One or more extensions, each with:
- Source location (from `DefinitionRefPlus` index)
- Applied directives (optional list, if flag set)
- Implemented interfaces (optional list, if flag set - Interface/Object only)
- Members list (type-specific: fields, enum values, or type references)

**Level 3 - Members**: Type-specific elements with their own metadata (fields have names, types, arguments; enum values have names and directives; etc.)

This three-level structure is encoded using the same patterns throughout: RefPlus for metadata attachment, continuation-based lists for sequences, and applied directives appearing identically wherever needed. The extension list structure (Level 2) is identical across five type kinds (Enum, Input, Interface, Object, Union)—only the members encoding (Level 3) varies.

**Pattern Reuse**: Extension decoding can be factored into generic logic that handles the common structure (source location, directives, implemented types), with only the members list requiring type-specific handling. This compositional approach means the format encodes ~7 type kinds using ~5 reusable patterns rather than 7 distinct encoding strategies.

#### Type-Specific Encodings

**Enum:**

- Per-extension:
  - **Enum Values List**: Either `EMPTY_LIST_MARKER` or a list of enum values
    - Each value: RefPlus (name, `hasAppliedDirectives`, continuation) + optional applied directives
- No global lists

**Input:**

- Per-extension:
  - **Fields List**: Either `EMPTY_LIST_MARKER` or list of input-like fields (see below)
- No global lists

**Interface:**

- Per-extension:
  - **Supers List** (if `hasImplementedTypes`): List of implemented Interface type indices
  - **Fields List**: Either `EMPTY_LIST_MARKER` or list of output fields (see below)
- Global list (after all extensions):
  - **Possible Object Types**: Either `EMPTY_LIST_MARKER` or list of Object type indices (all objects implementing this interface)

**Object:**

- Per-extension:
  - **Supers List** (if `hasImplementedTypes`): List of implemented Interface type indices
  - **Fields List**: Either `EMPTY_LIST_MARKER` or list of output fields (see below)
- Global list (after all extensions):
  - **Unions**: Either `EMPTY_LIST_MARKER` or list of Union type indices (all unions containing this object)

**Scalar:**

- Per-extension: Applied directives only (no members list)
- No global lists

**Union:**

- Per-extension:
  - **Member Types List**: Either `EMPTY_LIST_MARKER` or list of Object type indices (with continuation bits)
- No global lists  

---

## Field and Argument Encoding

Fields and arguments use a unified encoding pattern with a consolidated RefPlus that supports both default values and arguments.

### Unified Pattern

All field and argument encodings follow this structure:

1. **RefPlus Word**: Encodes name (identifier index) + metadata flags
2. **Applied Directives List** (optional): Present if `hasAppliedDirectives` flag set
3. **Type Expression Index**: 32-bit word indexing the TypeExpr section
4. **Context-Specific Extras**: Default value (input-like) and/or arguments list (output)

The consolidated FieldRefPlus uses separate bits for different flags:
- **FLAG_1_BIT** (bit 29) = `hasDefaultValue` - present on input-like fields (directive args, field args, input type fields)
- **FLAG_2_BIT** (bit 28) = `hasArguments` - present on output fields (interface fields, object fields)
- **FLAG_0_BIT** (bit 30) = `hasAppliedDirectives` - can be present on any field

This pattern appears in five contexts: directive arguments, field arguments, input type fields, interface fields, and object fields. The uniform structure means field encoding and decoding logic is fully shared across all contexts.

### Input-Like Fields

Used for:

- Directive arguments
- Field arguments (for output fields)
- Input object type fields

Each input-like field:

**1. RefPlus Word (FieldRefPlus)**:

- **Bits 0-19**: Field/argument name identifier index
- **Bit 28**: `hasArguments` (always 0 for input-like fields)
- **Bit 29**: `hasDefaultValue` (does this field/arg have a default?)
- **Bit 30**: `hasAppliedDirectives` (does this field/arg have directives?)
- **Bit 31**: `END_OF_LIST_BIT` (list continuation)

**2. Applied Directives List** (optional):
- Present if `hasAppliedDirectives` is set

**3. Type Expression Index**:
- 32-bit word: index into TypeExpr section

**4. Constant Reference** (optional):
- Present if `hasDefaultValue` is set
- 32-bit word: index into combined constants space

### Output Fields

Used for:

- Interface fields
- Object fields

Each output field:

**1. RefPlus Word (FieldRefPlus)**:

- **Bits 0-19**: Field name identifier index
- **Bit 28**: `hasArguments` (does this field take arguments?)
- **Bit 29**: `hasDefaultValue` (always 0 for output fields)
- **Bit 30**: `hasAppliedDirectives` (does this field have directives?)
- **Bit 31**: `END_OF_LIST_BIT` (list continuation)

**2. Applied Directives List** (optional):

- Present if `hasAppliedDirectives` is set

**3. Type Expression Index**:

- 32-bit word: index into TypeExpr section

**4. Arguments List** (optional):

- Present if `hasArguments` is set
- Each argument uses input-like field encoding

---

## Applied Directives Encoding

Applied directives represent directive applications like `@deprecated(reason: "Use newField")`. They appear on type definitions, fields, enum values, and arguments.

### Structure

When a schema element has applied directives (indicated by `hasAppliedDirectives` flag in its RefPlus), the RefPlus is immediately followed by a list of applied directives.

Each applied directive:

**1. RefPlus Word**:

- **Bits 0-19**: Directive name identifier index
- **Bit 29**: `hasArguments` (does this application provide arguments?)
- **Bit 30**: Unused (no directives on directives)
- **Bit 31**: `END_OF_LIST_BIT` (list continuation)

**2. Arguments List** (optional):

- Present if `hasArguments` is set
- Each argument encoded as described below

---

## Arguments Encoding

The presense of an argument list is indicated in by the `hasArguments` flag of a preceeding RefPlus or DirectivesInfo word.  If the list is present, it's structured as a list of name-value pairs, where each pair consists of (in order):

- **RefPlus Word**: Argument name identifier index + list continuation
- **Constant Reference**: 20-bit word index into combined constants space

### Argument Ordering

Applied directive arguments are sorted by argument name for deterministic encoding.

### Argument Omission Optimization

To reduce encoded size, arguments may be **omitted** from the binary when their value can be reconstructed from the directive definition:

- **Value matches the default** in the directive definition
- **Value is null** and the argument type is nullable (with no default)

The decoder reconstructs omitted arguments using the directive definition:

| Scenario                                                           | What arguments map contains |
|--------------------------------------------------------------------|----------------------------|
| Argument explicitly specified                                      | Specified value            |
| Argument not explicitly specified but has default in definition    | Default from definition    |
| Argument not explicitly specified, has no default, but is nullable | Null                       |
| Other (required, no default)                                       | Nothing, key not defined   |

**Note**: Since directives are encoded in topological order (see Section 9), the decoder is guaranteed to have access to any referenced directive definition when decoding applied directives. This allows argument omission optimization to work uniformly for all applied directives.

---


## Bit Field Summary

Understanding the bit fields is crucial for implementing encoders and decoders. Here's a comprehensive reference:

### Common Bit Positions

```
Bit     Name                Mask         Usage
---     ----                ----         -----
31      END_OF_LIST_BIT     0x80000000   List continuation (0 = more, 1 = last)
30      FLAG_0_BIT          0x40000000   hasAppliedDirectives (most contexts)
30      IS_LIST_CONSTANT    0x40000000   First word of list constant
29      FLAG_1_BIT          0x20000000   Context-dependent (see below)
28      FLAG_2_BIT          0x10000000   Context-dependent (see below)
0-19    IDX_MASK            0x000FFFFF   Index (identifier, source location, etc.)
```

### FLAG_1_BIT (Bit 29) and FLAG_2_BIT (Bit 28) Usage by Context

| Context                   | FLAG_1_BIT Meaning     | FLAG_2_BIT Meaning     |
|---------------------------|------------------------|------------------------|
| Type definition extension | `hasImplementedTypes`  | Unused (0)             |
| Input-like field          | `hasDefaultValue`      | Unused (0)             |
| Output field              | `hasDefaultValue`      | `hasArguments`         |
| Applied directive         | `hasArguments`         | Unused (0)             |

### RefPlus Variants

Different RefPlus classes encode different flag combinations:

```kotlin
DefinitionRefPlus(sourceLocationIdx, hasImplementedTypes, hasAppliedDirectives, hasNext)
    → bits 0-19: sourceLocationIdx
    → bit 28: 0
    → bit 29: hasImplementedTypes
    → bit 30: hasAppliedDirectives
    → bit 31: !hasNext

FieldRefPlus(nameIdx, hasDefaultValue, hasArguments, hasAppliedDirectives, hasNext)
    → bits 0-19: nameIdx
    → bit 28: hasArguments
    → bit 29: hasDefaultValue
    → bit 30: hasAppliedDirectives
    → bit 31: !hasNext

    // Type aliases for context clarity:
    // InputLikeFieldRefPlus = FieldRefPlus (hasArguments always 0)
    // OutputFieldRefPlus = FieldRefPlus (hasDefaultValue always 0)

AppliedDirectiveRefPlus(nameIdx, hasArguments, hasNext)
    → bits 0-19: nameIdx
    → bit 28: 0
    → bit 29: hasArguments
    → bit 30: 0
    → bit 31: !hasNext

AppliedDirectiveArgRefPlus(nameIdx, hasNext)
    → bits 0-19: nameIdx
    → bit 28: 0
    → bit 29: 0
    → bit 30: 0
    → bit 31: !hasNext

EnumValueRefPlus(nameIdx, hasAppliedDirectives, hasNext)
    → bits 0-19: nameIdx
    → bit 28: 0
    → bit 29: 0
    → bit 30: hasAppliedDirectives
    → bit 31: !hasNext

StubRefPlus(identifierIdx, kindCode)
    → bits 0-19: identifierIdx
    → bits 20-23: 0 (unused)
    → bits 24-31: kindCode
```

---

## Decoding Strategy

The format is designed for efficient three-phase decoding:

### Phase 1: Shell Creation
1. Read identifiers section to get all identifier strings
2. Read definition stubs section to discover all type and directive names with their kinds
3. Create empty "shell" objects for each definition

### Phase 2: Structure Building
1. Read type expressions section (can reference shells by name)
2. Read root types section
3. Read definitions section:
   - Populate directive definitions
   - Populate type definitions with their extensions, fields, etc.
   - Store constant references (indices) without resolving them yet

### Phase 3: Constant Resolution
1. Read simple constants section
2. Read compound constants section (can reference simple constants and earlier compound constants)
3. Resolve all stored constant references from Phase 2

This strategy handles the circular references between types and type expressions, and respects the no-forward-references constraint for constants.

### Pattern Recognition

Efficient decoders benefit from recognizing the format's pattern reuse:

**Universal Patterns**:
- All six RefPlus variants can share validation code (bits 20-28 must be zero) and bit-extraction logic
- List decoding logic can be genericized—all lists use END_OF_LIST_BIT for continuation
- Applied directive decoding is identical wherever directives appear (type extensions, fields, enum values, arguments)

**Compositional Patterns**:
- Extension decoding can be factored into generic logic handling the common structure (source location, directives, implemented types), with only members list handling varying by type kind
- Field decoding can be parameterized—input-like and output variants differ only in FLAG_1_BIT meaning and "extras" handling
- Arguments are themselves input-like fields, allowing recursive application of the same pattern

**Implementation Insight**: The format's regularity allows decoders to implement ~5 generic helper methods (decode RefPlus, decode continuation list, decode extension list, decode field, decode applied directives) that compose to handle all schema elements. This is significantly simpler than implementing separate decoding logic for each of the dozens of schema element types.

---

## Alignment and Padding

Several sections require 4-byte alignment:

**Padded Sections**:

- Identifiers section
- Source Locations section
- Simple Constants section

**Naturally Aligned** (no padding needed):

- Header section (multiple of 4 bytes)
- TypeExpr section (all entries 4 or 8 bytes)
- Root Types section (exactly 12 bytes)
- Definitions section (all entries multiples of 4 bytes)
- Compound Constants section (all entries 4 bytes)

Padding bytes must be `0x00`.

---

## Size Limits

The format imposes several limits:

| Limit                     | Value      | Reason                              |
|---------------------------|------------|-------------------------------------|
| Identifiers               | 2²⁰ (~1M)  | 20-bit indices                      |
| Source Locations          | 2²⁰ (~1M)  | 20-bit indices                      |
| Type Expressions          | 2²⁰ (~1M)   | 20-bit indices                  |
| Total Constants           | 2²⁰ (~1M)  | 20-bit indices (simple + compound)  |
| Max List Depth            | 27         | TypeExpr encoding limitation        |
| Max String Length         | 65536      | Sanity limit (header specifies max) |

---

## Validation and Invariants

The file format maintains numerous invariants documented in `bschema-invariants.md`. Key categories:

1. **Structural Invariants**: Correct counts, sizes, alignment
2. **Reference Invariants**: All indices in bounds, point to correct types
3. **Encoding Invariants**: Unused bits zero, correct flag usage
4. **Ordering Invariants**: No forward references in compound constants
5. **Semantic Invariants**: GraphQL schema validity (separate from format validity)

These invariants help detect:

- File corruption (bit flips, truncation)
- Encoder bugs (incorrect flag settings)
- Reader/writer "offsetting bugs" (both wrong in the same way)
- Security issues (maliciously crafted files)

---

## Example Walkthrough

Let's trace the encoding of a simple schema:

```graphql
type Query {
  user(id: ID!): User
}

type User {
  id: ID!
  name: String
}

directive @deprecated(reason: String!) on FIELD_DEFINITION
```

### Identifiers Section
```
Index   String          Terminator
-----   ------          ----------
0       "ID"            0x00
1       "Query"         0x00
2       "String"        0x00
3       "User"          0x00
4       "deprecated"    0x00
5       "id"            0x00
6       "name"          0x00
7       "reason"        0x00
8       "user"          0x00
```

### Definition Stubs Section
```
Entry   Identifier Index    Kind Code       Definition
-----   ----------------    ---------       ----------
0       0                   0xD0            Scalar "ID"
1       1                   0xC0            Object "Query"
2       2                   0xD0            Scalar "String"
3       3                   0xC0            Object "User"
4       4                   0x80            Directive "deprecated"
```

### Source Locations Section
```
Index   String
-----   ------
0       (null placeholder)
1       "schema.graphql"
```

### TypeExpr Section
```
Index   Encoding                    Type
-----   --------                    ----
0       baseIdx=0, nullable=false   ID!
1       baseIdx=2, nullable=true    String
2       baseIdx=3, nullable=true    User
```

### Root Types Section
```
Query:        1 (points to "Query")
Mutation:     -1 (undefined)
Subscription: -1 (undefined)
```

### Definitions Section

The section processes definitions in identifier order: ID, Query, String, User, deprecated.

**ID** (Scalar):

- RefPlus: srcLoc=1, hasAppliedDirectives=0, endOfList=1

**Query** (Object):

- Extension RefPlus: srcLoc=1, hasImplementedTypes=0, hasAppliedDirectives=0, endOfList=1
- Fields list:
  - Field "user" (index 8): RefPlus: hasArguments=1, hasAppliedDirectives=0, endOfList=1
    - TypeExpr index: 2 (User)
    - Arguments list:
      - Arg "id" (index 5): RefPlus: hasDefault=0, hasAppliedDirectives=0, endOfList=1
        - TypeExpr index: 0 (ID!)
- Unions list: EMPTY_LIST_MARKER

**String** (Scalar):

- RefPlus: srcLoc=1, hasAppliedDirectives=0, endOfList=1

**User** (Object):

- Extension RefPlus: srcLoc=1, hasImplementedTypes=0, hasAppliedDirectives=0, endOfList=1
- Fields list:
  - Field "id" (index 5): RefPlus: hasArguments=0, hasAppliedDirectives=0, endOfList=0
    - TypeExpr index: 0 (ID!)
  - Field "name" (index 6): RefPlus: hasArguments=0, hasAppliedDirectives=0, endOfList=1
    - TypeExpr index: 1 (String)
- Unions list: EMPTY_LIST_MARKER

**deprecated** (Directive):

- RefPlus: srcLoc=1, hasAppliedDirectives=0, endOfList=1
- DirectiveInfo: repeatable=0, locations=[FIELD_DEFINITION], hasArgs=1
- Arguments list:
  - Arg "reason" (index 7): RefPlus: hasDefault=0, hasAppliedDirectives=0, endOfList=1
    - TypeExpr index: (new entry for String!)
---

## Implementation Notes

### Known Fixes

The following bugs have been identified and corrected:

1. **hasArguments vs hasAppliedDirectives confusion**: Earlier versions incorrectly used the `hasArguments` bit (FLAG_1_BIT, bit 29) instead of the `hasAppliedDirectives` bit (FLAG_0_BIT, bit 30) to indicate whether input-like fields have applied directives. This worked because both reader and writer had the same bug, creating an "offsetting bug" that passed tests. The current implementation correctly uses:
   - Bit 30 (FLAG_0_BIT) for `hasAppliedDirectives`
   - Bit 29 (FLAG_1_BIT) for `hasDefaultValue`

### Potential Future Enhancements

1. **Documentation Metadata**: Additional sections for descriptions, deprecation reasons, etc., keyed by type/field coordinates
2. **Identifier Trie**: Replace flat identifier array with a trie structure for more efficient lookups
3. **Compression**: Add optional compression (the current format is already compact but not compressed)
4. **Incremental Updates**: Support for schema deltas/patches
5. **Schema Validation Metadata**: Pre-computed validation results for faster runtime checks

---

## References

- `ViaductSchema.kt`: AST interface definition
- `BSchemaWriter.kt`: Encoder implementation
- `BSchemaReader.kt`: Decoder implementation
- `constants.kt`: Bit field masks and constants
- `RefPlus.kt`: RefPlus encoding utilities
- `bschema-invariants.md`: Comprehensive invariant documentation
- `encoding.md`: Original encoding specification

---

## Conclusion

This binary format achieves its goals of compactness and efficient decoding while maintaining full schema fidelity. The careful use of bit flags, index-based references, and section organization enables fast single-pass decoding with minimal allocations. The no-forward-references constraint on constants and the three-pass decoding strategy cleanly handle circular type references.

The format is not intended for long-term archival (use GraphQL SDL for that) but excels at its designed purpose: build-time schema exchange and runtime schema loading.

---

## Appendix: Testing Strategy

Errors in the reading and writing of binary formats like this is notoriously challenging, so special attention to correctness is warranted.  At the same time, deeply embedding details of the file format into the test suite will make it difficult to change the format, because the tests would need to be updated with every change, and tests of these kinds can get very tedious to change.

To avoid this problem, we've employed a "round trip" testing strategy.  In this strategy we've carefully crafted small schemas that exercise specific paths through the encoder and/or decoder, and then we test if -- after encoding and then decoding that schema -- we get an exact match of the original schema back.  We've also been able to re-use the `ViaductSchema` "contract" tests which test more detailed invariants of `ViaductSchema` objects than a round-trip equality test would catch.

This form of testing is vulnerable to the "offsetting bugs" problem: if both the encoder and decoder do something wrong in the same way, schemas might successfully make the round trip despite the bug.  This is mostly a problem with detailed bit assignments, e.g., if the encoder and decoder both use the wrong bit for the `hasAppliedDirectives` flag, this will be missed by a roundtrip test.  We've guarded against these problems by using the `RefPlus` value classes, which encapsulate bit-positions away from the main encoding and decoding logic.
