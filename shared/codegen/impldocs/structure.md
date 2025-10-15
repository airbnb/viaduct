This project is structured into three packages:

* `codegen/util` - utility items (including naming abstractions) shared by the other two packages in this project, and also exported to clients of this project.


* `codegen/km` - the main interface to the project.  In particular, the class `KmClassFilesBuilder` is the entry point into the code generator.  Through an instance of this class you get access to `Builders` of various kinds, which allow you to build up data structures that can be converted (by calling `KmClassFilesBuilder.buildClassfiles`) into class files.  (Under the covers, these builder classes build "wrapper" classes, which are the inputs to the next layer in our design.)


* `codegen/ct` - converts the data structures built up using `KmClassFilesBuilder` into class files.  This package is meant as purely implementation for `KmClassFilesBuilder`: clients of the project as a whole should _not_ use anything from this package.  The `KmClassFilesBuilder.buildClassfiles` works by calling the `buildClasses` function in the `ct` package.  This function takes a collection of data structures called "wrappers" (defined by `ct` in `KmWrappers.kt`).  These classes "wrap" `kotlinx-metadata` information about classfile elements like classes themselves, properties, and methods.  The wrappers provide information that supplements the `kotlinx-metadata` information (e.g., the bodies of methods), so the `ct` package has all the information needed to generate the class files.  In addition to the `buildClasses` function and these wrapper classes, the `ct` package also exports a number of shared utilities to the `km` package (in `SharedUtils.kt`).  Unlike the utilities in `codegen/util`, these utilities are not meant to be use by external users.
