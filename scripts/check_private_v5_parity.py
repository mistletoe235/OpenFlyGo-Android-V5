import argparse
from pathlib import Path
import subprocess


SHARED_TREES = (
    "app/src/main/java/edu/playground/djivln/survey",
    "app/src/main/java/edu/playground/djivln/camera",
    "app/src/main/java/edu/playground/djivln/account",
    "app/src/main/java/edu/playground/djivln/domain/camera",
    "uxsdk/src/main/java/dji/v5/ux/map",
    "uxsdk/src/main/java/dji/v5/ux/mapkit",
)
MANUAL_REVIEW = {
    "app/src/main/java/edu/playground/djivln/survey/SurveyFeatureAvailability.kt": "public terrain exclusion",
    "app/src/main/java/edu/playground/djivln/survey/SurveySimulatorExecution.kt": "public terrain exclusion",
    "app/src/main/java/edu/playground/djivln/camera/CameraCaptureController.kt": "private uncommitted recording/mode-ack changes; shared photo-readiness section checked separately",
}
SHARED_FILES = (
    "control/VirtualStickDisableCompletion.kt",
    "control/VirtualStickLifecycleGuard.kt",
    "control/VirtualStickPortLease.kt",
    "control/VirtualStickSendPolicy.kt",
    "domain/telemetry/TelemetryNormalizer.kt",
    "hil/DjiV5SimulatorPoseSource.kt",
    "ui/LatestMapUpdate.kt",
    "ui/SurveyExecutionOverlayRenderer.kt",
    "ui/DjiSurveyExecutionSurface.kt",
)
SHARED_SECTIONS = (
    ("camera/CameraCaptureController.kt", "    private fun triggerPhotoWhenReady(", "    private fun completeOperation("),
    ("adapter/dji/DjiV5CameraFrameSource.kt", "    fun unbind()", "internal fun <T> selectVideoStreamIndex("),
    ("adapter/dji/DjiV5FlightControlPort.kt", "    private fun requestDisableFromManager()", "    private fun onReleaseTimeout()"),
    ("ui/FlightFeatureController.kt", "    override fun renderExecutionOverlay(", "    override fun setThreeDimensional("),
    ("ui/HilFeatureController.kt", "        binding.hilPreviewOnce.setOnClickListener", "        val alreadyRunning"),
)
BASE = "app/src/main/java/edu/playground/djivln/"


def normalized(path, text):
    if path == "uxsdk/src/main/java/dji/v5/ux/map/MapWidget.java":
        start = text.index("    private final LocationListener systemLocationListener = ")
        end = text.index("    //endregion", start)
        return text[:start] + text[end:]
    return text


def main():
    parser = argparse.ArgumentParser(description="Compare shared V5 fixes without copying private modules or computing hashes.")
    parser.add_argument("--private-root", required=True, type=Path)
    args = parser.parse_args()
    public = Path(__file__).resolve().parents[1]
    private = args.private_root.resolve()
    for label, repo in (("public", public), ("private", private)):
        revision = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=repo, text=True).strip()
        print(f"{label}: HEAD {revision}; comparing working-tree contents, including uncommitted files")
    paths = {BASE + name for name in SHARED_FILES}
    for subtree in SHARED_TREES:
        for repo in (public, private):
            paths.update(str(path.relative_to(repo)) for path in (repo / subtree).rglob("*")
                         if path.suffix in (".kt", ".java"))
    errors = []
    compared = 0
    for path in sorted(paths):
        if path in MANUAL_REVIEW:
            print(f"MANUAL REVIEW: {path}: {MANUAL_REVIEW[path]}")
            continue
        try:
            left = normalized(path, (public / path).read_text())
            right = normalized(path, (private / path).read_text())
            if left != right:
                errors.append(f"DIFF: {path}")
            else:
                compared += 1
        except (OSError, ValueError) as error:
            errors.append(f"MISSING/INVALID: {path}: {error}")
    for name, start, end in SHARED_SECTIONS:
        try:
            sections = []
            for repo in (public, private):
                text = (repo / BASE / name).read_text()
                sections.append(text[text.index(start):text.index(end, text.index(start))])
            if sections[0] != sections[1]:
                errors.append(f"SECTION DIFF: {name}: {start.strip()}")
            else:
                compared += 1
        except (OSError, ValueError) as error:
            errors.append(f"MISSING SECTION: {name}: {error}")
    for error in errors:
        print(error)
    print(f"Shared checks passed: {compared}; failed: {len(errors)}. Exemptions and other modules still need manual review.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
