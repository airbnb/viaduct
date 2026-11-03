#!/usr/bin/env python3
"""Exercise published plugins in independent consumer builds, including cache relocation."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


OSS = Path(__file__).resolve().parents[2]
VERSIONS = dict(re.findall(
    r'^([\w-]+) = "([^"]+)"$',
    (OSS / "gradle/libs.versions.toml").read_text().split("[libraries]")[0],
    re.MULTILINE,
))
VERSION = (OSS / "VERSION").read_text().strip()
SCHEMA_TASK = ":assembleViaductCentralSchema"
GRT_TASKS = {
    "kotlin": ":generateViaductGRTClassFiles",
    "java": ":generateViaductJavaGRTSources",
}


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def resolver(root, language, name, field, value):
    package = f"example.{language}"
    if language == "kotlin":
        path = root / f"src/main/kotlin/example/kotlin/{name}.kt"
        content = f'''package {package}
import {package}.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver
@Resolver
class {name} : QueryResolvers.{field[0].upper() + field[1:]}() {{
    override suspend fun resolve(ctx: Context) = "{value}"
}}
'''
    else:
        path = root / f"src/main/java/example/java/{name}.java"
        content = f'''package {package};
import {package}.resolverbases.QueryResolvers;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
@Resolver
public class {name} extends QueryResolvers.{field[0].upper() + field[1:]} {{
    @Override public CompletableFuture<String> resolve(Context ctx) {{
        return CompletableFuture.completedFuture("{value}");
    }}
}}
'''
    write(path, content)
    return path


def build_script(language, application=False):
    plugins = ['`java-library`']
    if language == "kotlin":
        plugins += ['id("org.jetbrains.kotlin.jvm")', 'id("com.google.devtools.ksp")']
    if language:
        suffix = "module" if language == "kotlin" else "module-java"
        plugins += [f'id("com.airbnb.viaduct.{suffix}-gradle-plugin")']
    dependencies = [f'implementation("com.airbnb.viaduct:api:{VERSION}")']
    if language == "java":
        dependencies += [f'implementation("com.airbnb.viaduct:javaapi-{part}:{VERSION}")' for part in ("api", "runtime")]
    if application:
        dependencies += [
            f'implementation("com.airbnb.viaduct:runtime:{VERSION}")',
            f'implementation("org.jetbrains.kotlin:kotlin-stdlib:{VERSIONS["kotlin"]}")',
            f'implementation("org.jetbrains.kotlin:kotlin-reflect:{VERSIONS["kotlin"]}")',
            f'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:{VERSIONS["kotlinx"]}")',
            'implementation("org.reactivestreams:reactive-streams:1.0.4")',
            f'testImplementation("org.junit.jupiter:junit-jupiter:{VERSIONS["junit5"]}")',
            'testRuntimeOnly("org.junit.platform:junit-platform-launcher")',
        ]
    return (
        "plugins {\n    " + "\n    ".join(plugins) + "\n}\n"
        + "java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }\n"
        + "dependencies {\n    " + "\n    ".join(dependencies) + "\n}\n"
        + ('tasks.test { useJUnitPlatform() }\n' if application else "")
    )


def runtime_test(root, languages, probe=False):
    fields = [f"greeting{language.capitalize()}" for language in languages]
    if probe:
        fields += [f"probe{language.capitalize()}" for language in languages]
    expected = ", ".join(f'"{field}", "{field}"' for field in fields)
    resources = []
    for language, path in (("kotlin", "viaduct/api/grts/Query.class"), ("java", "viaduct/java/grts/Query.class")):
        assertion = "assertNotNull" if language in languages else "assertNull"
        resources.append(f'{assertion}(getClass().getClassLoader().getResource("{path}"));')
    write(root / "src/test/java/example/RuntimeTest.java", f'''package example;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import viaduct.service.BasicViaductFactory;
import viaduct.service.api.ExecutionInput;
public class RuntimeTest {{
    @Test void publishedModulesExecute() {{
        var result = BasicViaductFactory.create().executeAsync(
            ExecutionInput.create("query {{ {' '.join(fields)} }}")).join();
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals(Map.of({expected}), result.getData());
        {' '.join(resources)}
    }}
}}
''')


def consumer(root, languages, same_project, cache):
    for relative in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(OSS / "demoapps/cli-starter" / relative, target)
    repository = '''
        mavenLocal()
        val mirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
        if (mirror != null) maven(mirror) else mavenCentral()
'''
    settings = f'''pluginManagement {{
    repositories {{ {repository} gradlePluginPortal() }}
    plugins {{
        id("org.jetbrains.kotlin.jvm") version "{VERSIONS['kotlin']}"
        id("com.google.devtools.ksp") version "{VERSIONS['ksp']}"
        id("com.airbnb.viaduct.module-gradle-plugin") version "{VERSION}"
        id("com.airbnb.viaduct.module-java-gradle-plugin") version "{VERSION}"
    }}
}}
plugins {{
    id("com.airbnb.viaduct.settings-gradle-plugin") version "{VERSION}"
    id("com.airbnb.viaduct.kotlinapi") version "{VERSION}" apply false
    id("com.airbnb.viaduct.javaapi") version "{VERSION}" apply false
}}
dependencyResolutionManagement {{ repositories {{ {repository} }} }}
rootProject.name = "published-plugin-consumer"
buildCache {{ local {{ directory = file("{cache.as_posix()}") }} }}
includeViaductApplication {{
    usePlugin("com.airbnb.viaduct.kotlinapi")
    usePlugin("com.airbnb.viaduct.javaapi")
    project(":")
    modulePackagePrefix("example")
'''
    for language in languages:
        module = ":" if same_project else f":{language}"
        settings += f'    includeModule {{ project("{module}"); modulePackageSuffix("{language}") }}\n'
    write(root / "settings.gradle.kts", settings + "}\n")
    write(root / "gradle.properties", "org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m\nksp.project.isolation.enabled=true\n")
    write(root / "build.gradle.kts", build_script(languages[0] if same_project else None, True))
    for language in languages:
        module = root if same_project else root / language
        if not same_project:
            write(module / "build.gradle.kts", build_script(language))
        field = f"greeting{language.capitalize()}"
        write(module / "src/main/viaduct/schema/schema.graphqls", f"extend type Query {{ {field}: String @resolver }}\n")
        resolver(module, language, "GreetingResolver", field, field)
    declarations = "\n".join(
        f"    {'viaduct.api' if language == 'kotlin' else 'viaduct.java'}.grts.Query {language};"
        for language in languages
    )
    write(root / "src/main/java/example/GeneratedTypes.java", f"package example;\nclass GeneratedTypes {{\n{declarations}\n}}\n")
    runtime_test(root, languages)


def run(root, repository, phase, *tasks, offline=False):
    command = [
        str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), "-p", str(root), *tasks,
        "--console=plain", "--max-workers=4", "--configuration-cache",
        "--configuration-cache-problems=fail", "-Dorg.gradle.unsafe.isolated-projects=true",
        "--build-cache", f"-Dmaven.repo.local={repository}",
    ]
    if offline:
        command.append("--offline")
    result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log = root.parent / f"{root.name}-{phase}.log"
    log.write_text(result.stdout)
    print(f"{root.parent.name}/{root.name}: {phase} (log: {log})", flush=True)
    if result.returncode:
        raise AssertionError(f"Gradle failed:\n{result.stdout}")
    return result.stdout


def outcome(output, task, expected):
    assert re.search(rf"^> Task {re.escape(task)} {expected}$", output, re.MULTILINE), f"Expected {task} {expected}\n{output}"


def generated_snapshot(root):
    result = {}
    for path in root.rglob("build/*"):
        if path.is_dir() and path.name in ("generated-sources", "generated-resources", "viaduct"):
            for file in path.rglob("*"):
                if file.is_file():
                    result[str(file.relative_to(root))] = hashlib.sha256(file.read_bytes()).hexdigest()
    for path in root.rglob("build/libs/*.jar"):
        with zipfile.ZipFile(path) as jar:
            for name in jar.namelist():
                if not name.endswith("/"):
                    result[f"{path.relative_to(root)}!{name}"] = hashlib.sha256(jar.read(name)).hexdigest()
    assert result, "No generated outputs found"
    return result


def check_case(parent, repository, languages, same_project, offline):
    first, second = parent / "first", parent / "relocated"
    invoke = lambda root, phase, *tasks: run(root, repository, phase, *tasks, offline=offline)
    assert not first.exists(), f"Use a fresh work directory: {first}"
    consumer(first, languages, same_project, parent / "cache")
    baseline = invoke(first, "initial", "build")
    for language, task in GRT_TASKS.items():
        assert (f"> Task {task}" in baseline) == (language in languages), f"Unexpected language generators: {baseline}"
    snapshot = generated_snapshot(first)
    reused = invoke(first, "reuse", "build")
    assert "Reusing configuration cache." in reused, reused
    outcome(reused, SCHEMA_TASK, "UP-TO-DATE")
    for language in languages:
        outcome(reused, GRT_TASKS[language], "UP-TO-DATE")
    shutil.copytree(first, second, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
    relocated = invoke(second, "relocation", "build")
    outcome(relocated, SCHEMA_TASK, "FROM-CACHE")
    for language in languages:
        outcome(relocated, GRT_TASKS[language], "FROM-CACHE")
    assert generated_snapshot(second) == snapshot, "Relocated generated outputs differ"
    for language in languages:
        module = second if same_project else second / language
        extension = "kt" if language == "kotlin" else "java"
        path = next(module.glob(f"src/main/{language}/**/GreetingResolver.{extension}"))
        path.write_text(path.read_text().replace(f'"greeting{language.capitalize()}"', f'"greeting{language.capitalize()}".trim()'))
    edited = invoke(second, "resolver-edit", "build")
    outcome(edited, SCHEMA_TASK, "UP-TO-DATE")
    for language in languages:
        outcome(edited, GRT_TASKS[language], "UP-TO-DATE")
        module = second if same_project else second / language
        extension = "kt" if language == "kotlin" else "java"
        path = next(module.glob(f"src/main/{language}/**/GreetingResolver.{extension}"))
        path.write_text(path.read_text().replace(f'"greeting{language.capitalize()}".trim()', f'"greeting{language.capitalize()}"'))
    added = []
    for language in languages:
        module = second if same_project else second / language
        field = f"probe{language.capitalize()}"
        schema = module / "src/main/viaduct/schema/probe.graphqls"
        write(schema, f"extend type Query {{ {field}: String @resolver }}\ntype ProbeType{language.capitalize()} {{ value: String }}\n")
        added += [schema, resolver(module, language, "ProbeResolver", field, field)]
    runtime_test(second, languages, probe=True)
    invoke(second, "addition", "build")
    for language in languages:
        module = second if same_project else second / language
        registry = module / f"build/generated-resources/viaduct-registry/META-INF/viaduct/modules/example.{language}.json"
        assert "ProbeResolver" in registry.read_text(), registry
        assert any(second.rglob(f"ProbeType{language.capitalize()}.class")), "Added GRT missing"
    for path in added:
        path.unlink()
    runtime_test(second, languages)
    invoke(second, "deletion", "build")
    for language in languages:
        module = second if same_project else second / language
        registry = module / f"build/generated-resources/viaduct-registry/META-INF/viaduct/modules/example.{language}.json"
        assert "ProbeResolver" not in registry.read_text(), registry
    assert not list(second.rglob("ProbeResolver.class")), "Deleted resolver class remains"
    assert not list(second.rglob("ProbeType*.class")), "Deleted GRT class remains"
    assert generated_snapshot(second) == snapshot, "Deleted schema/resolver left stale generated outputs"
    if not same_project:
        for language in languages:
            path = second / language / "build.gradle.kts"
            path.write_text(path.read_text() + '\ndependencies { runtimeOnly("unavailable.example:runtime:1") }\n')
        invoke(second, "runtime-graph-isolation", "compileJava")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-repository", required=True, type=Path)
    parser.add_argument("--work-directory", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--case", choices=("kotlin-multi", "java-multi", "mixed", "kotlin-local", "java-local"))
    args = parser.parse_args()
    work = args.work_directory or Path(tempfile.mkdtemp(prefix="viaduct-plugin-compatibility-"))
    for name, languages, same_project in (
        ("kotlin-multi", ["kotlin"], False),
        ("java-multi", ["java"], False),
        ("mixed", ["kotlin", "java"], False),
        ("kotlin-local", ["kotlin"], True),
        ("java-local", ["java"], True),
    ):
        if args.case is None or args.case == name:
            check_case(work / name, args.maven_repository.resolve(), languages, same_project, args.offline)
    print(f"Published plugin compatibility checks passed: {work}", flush=True)


if __name__ == "__main__":
    main()
