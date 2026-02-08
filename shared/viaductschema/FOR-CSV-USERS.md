# For CSV Users

This file is for developers who want to use the `schema2csv` command to explore GraphQL schemas.

## Purpose

The `schema2csv` tool dumps schema information into CSV files for open-ended exploration. This is useful for understanding how types and fields are organized across tenant modules, which directives are used where, and how modules reference each other.

## Running the Tool

From the `projects/viaduct/oss` directory:

```bash
./gradlew :core:shared:shared-viaductschema:runSchemaCli \
  --args="schema2csv -p /path/to/schema/dir -o /output/dir"
```

For example, to analyze the Star Wars demo application:

```bash
./gradlew :core:shared:shared-viaductschema:runSchemaCli \
  --args="schema2csv -p demoapps/starwars -o /tmp/starwars-csv"
```

## Output Files

The tool generates four CSV files:

| File | Description |
|------|-------------|
| `types.csv` | One row per type |
| `typedirs.csv` | One row per directive per type |
| `fields.csv` | One row per field |
| `fielddirs.csv` | One row per directive per field |

The `*dirs.csv` files contain one row for every directive applied. If a type has two directives, it appears twice in `typedirs.csv`. Types or fields with no directives still appear once with `NONE` for the directive columns.

## Column Reference

Run `schema2csv --help` for a complete description of all columns. Here's a summary:

### Common Columns (all files)

| Column | Description |
|--------|-------------|
| `Tenant` | Module path extracted from source file (everything before `/src/`, with common prefix stripped) |
| `Kind` | Type kind: ENUM, INPUT, INTERFACE, SCALAR, OBJECT, or UNION |
| `IsNode` | `true` if the type implements Node |
| `Type` | The type name |

### Field-specific Columns (fields.csv, fielddirs.csv)

| Column | Description |
|--------|-------------|
| `Extension` | `true` if field is in an inter-module type extension |
| `FieldTenant` | Module path for the field's definition |
| `IsRoot` | `true` if this is a root query/mutation field |
| `Field` | The field name |
| `FieldBaseTenant` | Module path for the field's base type |
| `FieldBaseKind` | Kind of the field's base type |
| `IsExternal` | `true` if base type is from a different module |
| `FieldBase` | Name of the field's base type |
| `FieldWrappers` | Nullability/list wrappers (e.g., `!` = non-null, `?` = nullable, `??` = nullable list of nullable) |
| `FieldListDepth` | Number of list wrappers |

### Directive Columns (*dirs.csv files)

| Column | Description |
|--------|-------------|
| `DirectiveName` | Name of the directive (e.g., `resolver`, `scope`) or `NONE` |
| `AppliedDirective` | Full directive text (e.g., `@scope(to: ["default"])`) |

## Querying with csvq

[csvq](https://github.com/mithrandie/csvq) is a useful tool for querying CSV files with SQL.

### Installing csvq

If you have Go installed:

```bash
go install github.com/mithrandie/csvq@latest
```

Alternatively, download a binary from the [releases page](https://github.com/mithrandie/csvq/releases).

### Running csvq

**Important:** Run csvq from the directory containing your CSV files. The table names in your queries correspond to the CSV filenames (without the `.csv` extension).

```bash
cd /path/to/csv/output
csvq "SELECT * FROM types LIMIT 5"
```

### Basic Counts

Count types:
```bash
$ cd /tmp/starwars-csv   # or wherever you output the CSV files
$ csvq "SELECT COUNT(Type) FROM types"
+-------------+
| COUNT(Type) |
+-------------+
|          18 |
+-------------+
```

Count fields:
```bash
$ csvq "SELECT COUNT(*) AS FieldCount FROM fields"
+------------+
| FieldCount |
+------------+
|        112 |
+------------+
```

### Exploring Types

List all types that implement Node:
```bash
$ csvq "SELECT Type, Kind FROM types WHERE IsNode = 'true'"
+-----------+--------+
|   Type    |  Kind  |
+-----------+--------+
| Character | OBJECT |
| Film      | OBJECT |
| Planet    | OBJECT |
| Species   | OBJECT |
| Starship  | OBJECT |
| Vehicle   | OBJECT |
+-----------+--------+
```

### Directive Usage

See which directives are used on types:
```bash
$ csvq "SELECT DirectiveName, COUNT(*) AS Count FROM typedirs GROUP BY DirectiveName ORDER BY Count DESC"
+---------------+-------+
| DirectiveName | Count |
+---------------+-------+
| scope         |    18 |
| resolver      |     6 |
| connection    |     2 |
| edge          |     2 |
| oneOf         |     1 |
+---------------+-------+
```

See which directives are used on fields:
```bash
$ csvq "SELECT DirectiveName, COUNT(*) AS Count FROM fielddirs GROUP BY DirectiveName ORDER BY Count DESC"
+---------------+-------+
| DirectiveName | Count |
+---------------+-------+
| NONE          |    78 |
| resolver      |    31 |
| backingData   |     6 |
| idOf          |     5 |
+---------------+-------+
```

### Cross-Module References

Find fields that reference types from other modules:
```bash
$ csvq "SELECT Type, Field, FieldTenant, FieldBaseTenant FROM fields WHERE IsExternal = 'true'"
+-----------+------------+-------------+-----------------+
|   Type    |   Field    | FieldTenant | FieldBaseTenant |
+-----------+------------+-------------+-----------------+
| Character | homeworld  | filmography | universe        |
| Character | species    | filmography | universe        |
| Film      | planets    | filmography | universe        |
| Film      | species    | filmography | universe        |
| Planet    | residents  | universe    | filmography     |
| Planet    | films      | universe    | filmography     |
+-----------+------------+-------------+-----------------+
```

### Root Fields

List all root query and mutation fields:
```bash
$ csvq "SELECT Type, Field FROM fields WHERE IsRoot = 'true'"
+----------+-----------------+
|   Type   |      Field      |
+----------+-----------------+
| Query    | allPlanets      |
| Query    | allCharacters   |
| Query    | searchCharacter |
| Query    | allFilms        |
| Query    | allStarships    |
| Query    | allSpecies      |
| Query    | allVehicles     |
| Mutation | createCharacter |
| Mutation | updateCharacter |
| Mutation | deleteCharacter |
+----------+-----------------+
```

### Field Type Patterns

Analyze field wrapper patterns (nullability and lists):
```bash
$ csvq "SELECT FieldWrappers, COUNT(*) AS Count FROM fields GROUP BY FieldWrappers ORDER BY Count DESC"
+---------------+-------+
| FieldWrappers | Count |
+---------------+-------+
| ?             |    67 |
| !             |    25 |
| ??            |    12 |
| ?!            |     8 |
+---------------+-------+
```

## Tips

- Always `cd` to the CSV output directory before running csvq
- Use `csvq -f` to write query results to a file
- The `*dirs.csv` files contain all columns from the base file plus directive info—no joins needed
- The `Tenant` column uses `NO_TENANT` when source location information isn't available
- For large schemas, consider filtering early with WHERE clauses to reduce result sets
