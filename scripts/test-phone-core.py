#!/usr/bin/env python3
"""Run Android-independent phone tests with the project's pinned Kotlin/JUnit versions.

Downloads Maven artifacts to the OS cache, never edits the Android build or signing.
Pass JUnit class names to select tests; by default discovers Android-free tests.
"""
import concurrent.futures
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("INSTO_TEST_CACHE", Path.home() / ".cache/insto-kotlin"))
ARTIFACTS = {
    "compiler": "org/jetbrains/kotlin/kotlin-compiler-embeddable/2.2.10/kotlin-compiler-embeddable-2.2.10.jar",
    "stdlib": "org/jetbrains/kotlin/kotlin-stdlib/2.2.10/kotlin-stdlib-2.2.10.jar",
    "reflect": "org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar",
    "script": "org/jetbrains/kotlin/kotlin-script-runtime/2.2.10/kotlin-script-runtime-2.2.10.jar",
    "coroutines": "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar",
    "coroutines-test": "org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.10.2/kotlinx-coroutines-test-jvm-1.10.2.jar",
    "annotations": "org/jetbrains/annotations/13.0/annotations-13.0.jar",
    "json": "org/json/json/20240303/json-20240303.jar",
    "junit": "junit/junit/4.13.2/junit-4.13.2.jar",
    "hamcrest": "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
}


def download(item):
    name, artifact = item
    path = CACHE / (name + ".jar")
    if not path.exists():
        partial = path.with_suffix(".part")
        urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/" + artifact, partial)
        partial.replace(path)
    return str(path)


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        jars = list(pool.map(download, ARTIFACTS.items()))
    classpath = os.pathsep.join(jars)
    source = ROOT / "app/src/main/java/com/example/phone"
    # Explicit boundary: no Android executors, storage or permission adapters.
    names = ["PhoneCommand", "PhoneActionResult", "PhoneCommandCodec", "PhoneCommandParser",
             "EverydayCommandParser", "ExtendedCommandParser", "CalendarRequestParser",
             "CommandDuration", "HybridPhoneRouter", "GeminiDecisionsClient", "StructuredCommandClient",
             "ConversationSession", "WorkflowRunner", "TemporaryModePolicy", "MediaSessionPolicy",
             "GeminiSetupPolicy", "StopwatchUiPolicy", "NativeIntentSpec", "ContactSelection", "GeminiTransport", "CapabilityCatalog",
             "Interpretation", "CommandDraft", "RequestSafetyPolicy", "GeminiCoordinator", "GeminiDiagnostics"]
    sources = [str(source / (name + ".kt")) for name in names if (source / (name + ".kt")).exists()]
    tests = []
    for path in sorted((ROOT / "app/src/test/java/com/example/phone").glob("*Test.kt")):
        text = path.read_text()
        if any(token in text for token in ("import android.", "import androidx.", "org.robolectric", "com.example.testsupport")):
            continue
        # Other pure policy tests have dependencies outside this compilation boundary.
        tests.append(path)
    with tempfile.TemporaryDirectory(prefix="insto-core-") as directory:
        classes = str(Path(directory) / "classes")
        command = ["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-jvm-target", "11", "-classpath", classpath,
                   "-d", classes] + sources + [str(path) for path in tests]
        subprocess.run(command, check=True, cwd=ROOT)
        selected = sys.argv[1:] or ["com.example.phone." + path.stem for path in tests]
        resources = str(ROOT / "app/src/test/resources")
        subprocess.run(["java", "-cp", classes + os.pathsep + resources + os.pathsep + classpath,
                        "org.junit.runner.JUnitCore"] + selected, check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
