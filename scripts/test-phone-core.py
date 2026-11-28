#!/usr/bin/env python3
"""Run Android-independent command, context and voice tests with pinned Kotlin/JUnit versions.

Downloads Maven artifacts to the OS cache, never edits the Android build or signing.
Pass JUnit class names to select tests; by default discovers Android-free tests.
"""
import concurrent.futures
import os
import shutil
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
    "coroutines": "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar",
    "coroutines-test": "org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.11.0/kotlinx-coroutines-test-jvm-1.11.0.jar",
    "annotations": "org/jetbrains/annotations/13.0/annotations-13.0.jar",
    "okhttp": "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar",
    "okio": "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
    "json": "org/json/json/20260814/json-20260814.jar",
    "gson": "com/google/code/gson/gson/2.13.2/gson-2.13.2.jar",
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
    # Explicit boundary: pure command/registry policies and file-based routines,
    # without Android executors, preferences/storage or permission adapters.
    names = ["PhoneCommand", "AppShortcut", "PhoneActionResult", "PhoneCommandCodec", "PhoneCommandParser",
             "EverydayCommandParser", "ExtendedCommandParser", "CalendarRequestParser", "CalendarSelection", "CalendarSelectionParser",
             "ClockTime", "CommandDuration", "HybridPhoneRouter", "GeminiDecisionsClient", "GroqIntentVerifier", "StructuredCommandClient",
             "ConversationSession", "WorkflowRunner", "TemporaryModePolicy", "MediaSessionPolicy",
             "GeminiSetupPolicy", "StopwatchUiPolicy", "NativeIntentSpec", "ContactSelection", "ProviderHttp", "GeminiTransport", "CapabilityCatalog", "InstinctCapabilities", "InstinctPlan", "InstinctStructuredPlan",
             "DecisionsPolicy", "DecisionsCircuitBreaker", "Interpretation", "CommandDraft", "RequestSafetyPolicy", "GeminiCoordinator", "GeminiDiagnostics",
             "LocalIntentRegistry", "LocalIntentPhrases", "ExtendedLocalIntents", "NativeActionParameters",
             "DocumentNamePolicy", "ConfiguredRoutineStore", "PhrasePatterns", "OperationalContext",
             "PhraseCanonicalizer", "LearnedPhraseIndex", "WeatherIntentParser", "SemanticCommandGuard", "InstinctProposals",
             "InstinctReplies", "InstinctRoutines", "InstinctProtocolCatalog",
             "InstinctSchedule", "InstinctUndo", "InstinctLog", "InstinctAutonomy", "InstinctState", "InstinctMailbox",
             "InstinctDoorbell", "InstinctTriggers", "InstinctContextRule", "InstinctPushInbox", "InstinctExecution", "InstinctQuestion", "ActionOutcome",
             "CapabilityHealth", "CapabilityHealthProtocol", "CapabilityHealthCommands"]
    sources = [str(source / (name + ".kt")) for name in names if (source / (name + ".kt")).exists()]
    # SMN weather clients: pure JVM (OkHttp, org.json, coroutines), no Android types.
    sources += [str(path) for path in sorted((source / "weather").glob("*.kt"))]
    sources += [str(ROOT / "app/src/main/java/com/example/audio/VoiceSessionPolicy.kt")]
    sources += [str(ROOT / "app/src/main/java/com/example/context/SharedTextContext.kt")]
    sources += [str(ROOT / "app/src/main/java/com/example" / path) for path in (
        "assist/AutoSendGuard.kt", "assist/ShareConfirmationPolicy.kt", "assist/ShareSendAttempt.kt",
        "assist/WhatsAppSelectors.kt", "assist/AutoSendMetrics.kt", "assist/AutoSendHealth.kt",
        "data/AssistantModels.kt", "backup/BackupCrypto.kt", "backup/BackupFormat.kt",
        "backup/ConfigurationBackupManager.kt", "backup/BackupSections.kt", "backup/InstinctBackupSection.kt", "backup/BackupInventory.kt",
        "whatsapp/PhoneNumberUtils.kt", "speech/AudioTranscriber.kt",
        "speech/GroqTranscriber.kt", "speech/VocabularyHints.kt",
        "session/SessionLifecycle.kt", "session/ActionExecutionLedger.kt",
        # «Callar alarma con la voz» (1.25.0): detection, «Detener» choice and keyword matching, without Android.
        "alarmvoice/AlarmVoiceLogic.kt")]
    tests = [ROOT / "app/src/test/java/com/example/audio" / name for name in (
        "VoiceSessionPolicyTest.kt", "VoiceCaptureTraceTest.kt")]
    tests += [ROOT / "app/src/test/java/com/example/context/SharedTextContextTest.kt"]
    # Session identity and state machine; the ledger test also uses Android-backed auto-send types (Gradle only).
    tests += [ROOT / "app/src/test/java/com/example/session/SessionLifecycleTest.kt"]
    tests += [ROOT / "app/src/test/java/com/example/alarmvoice/AlarmVoiceLogicTest.kt"]
    tests += [ROOT / "app/src/test/java/com/example" / name for name in (
        "AutoSendGuardTest.kt", "ShareConfirmationPolicyTest.kt",
        "speech/GroqTranscriberTest.kt", "speech/VocabularyHintsTest.kt")]
    tests += [ROOT / "app/src/test/java/com/example/assist" / name for name in (
        "WhatsAppSelectorFixtures.kt", "WhatsAppSelectorsTest.kt", "AutoSendMetricsTest.kt")]
    # Shared HTTP test double: replaces the network, keeps the transport policies.
    tests += [ROOT / "app/src/test/java/com/example/phone/FakeHttp.kt"]
    tests += sorted((ROOT / "app/src/test/java/com/example/backup").glob("*Test.kt"))
    tests += sorted((ROOT / "app/src/test/java/com/example/phone/weather").glob("*.kt"))
    for path in sorted((ROOT / "app/src/test/java/com/example/phone").glob("*Test.kt")):
        text = path.read_text()
        # Tests that use Android-backed stores (DataStore) run with Gradle.
        if any(token in text for token in ("import android.", "import androidx.", "org.robolectric", "com.example.testsupport",
                                           "import com.example.data.")):
            continue
        tests.append(path)
    with tempfile.TemporaryDirectory(prefix="insto-core-") as directory:
        classes = str(Path(directory) / "classes")
        command = ["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-jvm-target", "11", "-classpath", classpath,
                   "-d", classes] + sources + [str(path) for path in tests]
        subprocess.run(command, check=True, cwd=ROOT)
        selected = sys.argv[1:] or [".".join(path.relative_to(ROOT / "app/src/test/java").with_suffix("").parts)
                                    for path in tests if path.stem.endswith("Test")]
        resources = str(ROOT / "app/src/test/resources")
        subprocess.run(["java", "-cp", classes + os.pathsep + resources + os.pathsep + classpath,
                        "org.junit.runner.JUnitCore"] + selected, check=True, cwd=ROOT)
    # The Apps Script of the log (docs/instinct-bitacora.gs) with fakes of Google's services, when Node is installed.
    if not sys.argv[1:] and shutil.which("node"):
        subprocess.run(["node", str(ROOT / "scripts/test-bitacora-gs.js")], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
