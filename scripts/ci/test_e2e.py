"""Parser tests for `e2e.py`'s focused-window guard.

The fixtures are real `dumpsys window displays` captures from a Pixel 9 Pro
(Android 15): one with the example app's window focused, one with a crash
dialog holding focus — the shape of the frame that polluted the `shape`
candidate golden in nightly run 35268572603 — and one with the notification
shade holding focus over a live app.
"""

import subprocess
import tomllib
from pathlib import Path

import e2e


APP_FOCUSED = """\
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
    init=960x2142 360dpi mMinSizeOfResizeableTaskDp=220 cur=960x2142 app=960x2142 rng=960x960-2142x2142
  mImeWindow=Window{677d17a u0 InputMethod}
  mImeLayeringTarget=Window{d7e665a u0 com.waterui.animation_example/com.waterui.animation_example.MainActivity}
  mRemoteInsetsControlTarget=RemoteInsetsControlTarget{b0347a9 displayId=0 requestedVisibleTypes=503 animatingTypes=0}
  mCurrentFocus=Window{d7e665a u0 com.waterui.animation_example/com.waterui.animation_example.MainActivity}
  mFocusedApp=ActivityRecord{174333607 u0 com.waterui.animation_example/.MainActivity t1631}
  mLastWakeLockObscuringWindow=Window{3404a52 u0 dev.waterui.edge_layout/dev.waterui.edge_layout.MainActivity EXITING}
    mHasBottomNavigationBar=true
    mFocusedWindow=Window{d7e665a u0 com.waterui.animation_example/com.waterui.animation_example.MainActivity}
    mTopFullscreenOpaqueWindowState=Window{d7e665a u0 com.waterui.animation_example/com.waterui.animation_example.MainActivity}
"""

# The "System UI keeps stopping" dialog from the polluted `shape` candidate:
# AppErrorDialog titles its window `Application Error: <process>` (AOSP
# services/core/java/com/android/server/am/AppErrorDialog.java).
CRASH_DIALOG_FOCUSED = """\
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
    init=960x2142 360dpi mMinSizeOfResizeableTaskDp=220 cur=960x2142 app=960x2142 rng=960x960-2142x2142
  mImeWindow=Window{677d17a u0 InputMethod}
  mImeLayeringTarget=Window{9f1c2ab u0 com.waterui.shape_example/com.waterui.shape_example.MainActivity}
  mCurrentFocus=Window{55e0d31 u0 Application Error: com.android.systemui}
  mFocusedApp=ActivityRecord{88213764 u0 com.waterui.shape_example/.MainActivity t1640}
    mHasBottomNavigationBar=true
    mFocusedWindow=Window{55e0d31 u0 Application Error: com.android.systemui}
      Window{9f1c2ab u0 com.waterui.shape_example/com.waterui.shape_example.MainActivity}:
      Window{55e0d31 u0 Application Error: com.android.systemui}:
"""

SHADE_FOCUSED = """\
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
    init=960x2142 360dpi mMinSizeOfResizeableTaskDp=220 cur=960x2142 app=960x2142 rng=960x960-2142x2142
  mImeWindow=Window{677d17a u0 InputMethod}
  mImeInputTarget=Window{d7e665a u0 com.waterui.animation_example/com.waterui.animation_example.MainActivity}
  mCurrentFocus=Window{57f38a1 u0 NotificationShade}
  mFocusedApp=ActivityRecord{174333607 u0 com.waterui.animation_example/.MainActivity t1631}
    mExpandedPanel=Window{57f38a1 u0 NotificationShade}
    mFocusedWindow=Window{57f38a1 u0 NotificationShade}
    mSystemUiControllingWindow=Window{57f38a1 u0 NotificationShade}
"""

NO_FOCUS = """\
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
  mCurrentFocus=null
    mFocusedWindow=null
"""

ANR_FOCUSED = """\
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
  mCurrentFocus=Window{771ab02 u0 Application Not Responding: com.android.systemui}
  mFocusedApp=ActivityRecord{174333607 u0 com.waterui.animation_example/.MainActivity t1631}
    mFocusedWindow=Window{771ab02 u0 Application Not Responding: com.android.systemui}
"""


def test_parse_focused_window_returns_app_window_title():
    title = e2e.parse_focused_window(APP_FOCUSED)
    assert title == (
        "com.waterui.animation_example/"
        "com.waterui.animation_example.MainActivity"
    )
    assert e2e.window_package(title) == "com.waterui.animation_example"


def test_parse_focused_window_returns_crash_dialog_title():
    title = e2e.parse_focused_window(CRASH_DIALOG_FOCUSED)
    assert title == "Application Error: com.android.systemui"
    # A free-form dialog title carries no `<package>/` prefix, so it can
    # never be attributed to the app under test.
    assert e2e.window_package(title) is None


def test_parse_focused_window_returns_system_surface_title():
    assert e2e.parse_focused_window(SHADE_FOCUSED) == "NotificationShade"
    assert e2e.window_package("NotificationShade") is None


def test_parse_focused_window_null():
    assert e2e.parse_focused_window(NO_FOCUS) is None


def test_parse_focused_window_falls_back_to_mFocusedWindow():
    dump = "    mFocusedWindow=Window{57f38a1 u0 NotificationShade}\n"
    assert e2e.parse_focused_window(dump) == "NotificationShade"


def test_parse_focused_window_ignores_other_window_lines():
    # Window{...} appears all over the dump — only the focus lines count.
    dump = (
        "  mImeWindow=Window{677d17a u0 InputMethod}\n"
        "  mLastWakeLockObscuringWindow=Window{3404a52 u0 "
        "dev.waterui.edge_layout/dev.waterui.edge_layout.MainActivity EXITING}\n"
        "  mCurrentFocus=Window{d7e665a u0 com.waterui.animation_example/"
        "com.waterui.animation_example.MainActivity}\n"
    )
    assert e2e.window_package(e2e.parse_focused_window(dump)) == (
        "com.waterui.animation_example"
    )


def test_window_package_of_exiting_app_window():
    # An exiting app window still titles itself `<package>/<component>`.
    title = "dev.waterui.edge_layout/dev.waterui.edge_layout.MainActivity EXITING"
    assert e2e.window_package(title) == "dev.waterui.edge_layout"


def test_parse_anr_package_finds_dialog_package():
    assert e2e.parse_anr_package(ANR_FOCUSED) == "com.android.systemui"
    assert e2e.parse_anr_package(APP_FOCUSED) is None


def test_capture_twin_drops_a_frame_rejected_for_foreign_focus(monkeypatch):
    # wait_for_settle hands back its last capture on a rejected-frame
    # timeout — the foreign window itself — which must never be consumed
    # as the twin.
    foreign = []
    monkeypatch.setattr(e2e, "adb", lambda *args: None)
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda *args, **kwargs: subprocess.CompletedProcess(args, 0),
    )

    def rejected(serial, timeout_s, poll_s, **kwargs):
        kwargs["foreign"].append("NotificationShade")
        return False, b"the foreign window's frame", None

    monkeypatch.setattr(e2e, "wait_for_settle", rejected)
    assert e2e.capture_twin("emulator-5554", "shape", 1.0, 0.1, foreign) is None
    assert foreign == ["NotificationShade"]


def test_capture_twin_returns_the_settled_frame(monkeypatch):
    foreign = []
    monkeypatch.setattr(e2e, "adb", lambda *args: None)
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda *args, **kwargs: subprocess.CompletedProcess(args, 0),
    )
    monkeypatch.setattr(
        e2e, "wait_for_settle", lambda *args, **kwargs: (True, b"twin", None)
    )
    assert (
        e2e.capture_twin("emulator-5554", "shape", 1.0, 0.1, foreign) == b"twin"
    )
    assert foreign == []


def test_verify_parity_fails_on_foreign_focus_without_comparing(
    monkeypatch, tmp_path
):
    def rejected(serial, example, timeout_s, poll_s, foreign):
        foreign.append("Application Error: com.android.systemui")
        return None

    monkeypatch.setattr(e2e, "capture_twin", rejected)
    compared = []
    monkeypatch.setattr(
        e2e, "compare_images", lambda *args: compared.append(args) or 0
    )
    cfg = {"settle_s": 1.0, "poll_s": 0.1, "tolerance": 8}
    detail = e2e.verify_parity(
        "shape", tmp_path / "shape.actual.png", "emulator-5554",
        cfg, 0.005, tmp_path
    )
    assert detail == (
        "foreign window 'Application Error: com.android.systemui' held focus"
    )
    assert compared == []
    assert not (tmp_path / "shape.twin.png").exists()


# The pin rewrites `android-backend-url` / `android-backend-revision` in the
# `[package.metadata.waterui]` table of the framework checkout's root
# Cargo.toml — the JitPack coordinate `water package` resolves the Kotlin
# runtime from. The fixture layout mirrors the real one: an example two
# directories deep selecting the checkout through `waterui_path = "../.."`.
def make_framework_example(tmp_path):
    framework_manifest = tmp_path / "Cargo.toml"
    framework_manifest.write_text(
        "[package]\n"
        'name = "waterui"\n'
        "\n"
        "# coordinates the framework certifies\n"
        "[package.metadata.waterui]\n"
        'android-backend-url = "https://github.com/water-rs/android-backend.git"\n'
        f'android-backend-revision = "{"0" * 40}"\n'
    )
    example = tmp_path / "examples" / "gesture"
    example.mkdir(parents=True)
    (example / "Water.toml").write_text(
        'waterui_path = "../.."\n'
        "\n"
        "[package]\n"
        'name = "Gesture Example"\n'
        'bundle_identifier = "com.waterui.gesture_example"\n'
    )
    return example, framework_manifest


def test_pin_android_backend_rewrites_the_framework_manifest(
    tmp_path, monkeypatch
):
    example, manifest = make_framework_example(tmp_path)
    revision = "f" * 40
    monkeypatch.setattr(
        e2e, "backend_coordinates",
        lambda _dir: ("https://github.com/water-rs/android-backend.git", revision),
    )

    assert e2e.pin_android_backend(example, tmp_path / "backend") == revision

    parsed = tomllib.loads(manifest.read_text())
    metadata = parsed["package"]["metadata"]["waterui"]
    assert metadata["android-backend-url"] == (
        "https://github.com/water-rs/android-backend.git"
    )
    assert metadata["android-backend-revision"] == revision
    # The example's own manifest and the file's comments are untouched.
    assert "# coordinates the framework certifies" in manifest.read_text()
    assert "backends" not in tomllib.loads(
        (example / "Water.toml").read_text()
    )

    # A second run returns the same revision and leaves the file
    # byte-identical.
    written = manifest.read_text()
    assert e2e.pin_android_backend(example, tmp_path / "backend") == revision
    assert manifest.read_text() == written


def test_pin_android_backend_uses_the_checkouts_git_coordinates(
    tmp_path, monkeypatch
):
    example, manifest = make_framework_example(tmp_path)
    monkeypatch.setattr(
        e2e, "backend_coordinates",
        lambda _dir: ("git@github.com:lexoliu/android-backend.git", "a" * 40),
    )

    e2e.pin_android_backend(example, tmp_path / "backend")

    metadata = tomllib.loads(manifest.read_text())["package"]["metadata"][
        "waterui"
    ]
    assert metadata["android-backend-url"] == (
        "git@github.com:lexoliu/android-backend.git"
    )
    assert metadata["android-backend-revision"] == "a" * 40


def test_pin_android_backend_rejects_a_channel_selected_example(
    tmp_path, monkeypatch
):
    example = tmp_path / "gesture"
    example.mkdir()
    (example / "Water.toml").write_text(
        "[package]\n"
        'name = "Gesture Example"\n'
        'bundle_identifier = "com.waterui.gesture_example"\n'
    )
    monkeypatch.setattr(
        e2e, "backend_coordinates",
        lambda _dir: ("u", "r"),
    )

    try:
        e2e.pin_android_backend(example, tmp_path / "backend")
    except ValueError as error:
        assert "waterui_path" in str(error)
    else:
        raise AssertionError("a channel selection has no checkout to pin")


def test_pin_android_backend_rejects_a_manifest_without_waterui_metadata(
    tmp_path, monkeypatch
):
    tmp_path.joinpath("Cargo.toml").write_text(
        "[package]\n"
        'name = "waterui"\n'
    )
    example = tmp_path / "examples" / "gesture"
    example.mkdir(parents=True)
    (example / "Water.toml").write_text('waterui_path = "../.."\n')
    monkeypatch.setattr(
        e2e, "backend_coordinates",
        lambda _dir: ("u", "r"),
    )

    try:
        e2e.pin_android_backend(example, tmp_path / "backend")
    except ValueError as error:
        assert "package.metadata.waterui" in str(error)
    else:
        raise AssertionError("a manifest without the metadata table must fail")


def test_pin_backend_subcommand_points_at_this_checkout(
    tmp_path, capsys, monkeypatch
):
    example, manifest = make_framework_example(tmp_path)
    revision = "e" * 40
    monkeypatch.setattr(
        e2e, "backend_coordinates",
        lambda _dir: ("https://github.com/water-rs/android-backend.git", revision),
    )

    assert e2e.main(["pin-backend", str(example)]) == 0

    metadata = tomllib.loads(manifest.read_text())["package"]["metadata"][
        "waterui"
    ]
    assert metadata["android-backend-revision"] == revision
    assert capsys.readouterr().out.strip() == revision


def test_package_release_fetches_remote_fonts_before_packaging(
    monkeypatch, tmp_path
):
    # A remote font declaration resolves out of the font cache `water fetch`
    # seeds — packaging alone never downloads (the build's no-network
    # guarantee). The suite ran `water package` without it, so any example
    # declaring a remote font failed packaging outright (nightly #206).
    commands = []

    def record(cmd, **kwargs):
        commands.append(cmd)
        return subprocess.CompletedProcess(cmd, 0)

    monkeypatch.setattr(subprocess, "run", record)
    monkeypatch.setattr(
        e2e, "find_release_apk", lambda _dir: tmp_path / "app.apk"
    )

    e2e.package_release(
        tmp_path, tmp_path / "examples" / "icons", "x86_64", tmp_path / "log"
    )

    assert [cmd[1] for cmd in commands] == ["fetch", "package"]
    fetch = commands[0]
    assert fetch[0] == "water"
    assert "--backend" in fetch and "android" in fetch


def test_package_release_fails_when_the_fetch_fails(monkeypatch, tmp_path):
    def fail_fetch(cmd, **kwargs):
        return subprocess.CompletedProcess(cmd, 1 if cmd[1] == "fetch" else 0)

    monkeypatch.setattr(subprocess, "run", fail_fetch)

    try:
        e2e.package_release(
            tmp_path, tmp_path / "example", "x86_64", tmp_path / "log"
        )
    except RuntimeError as error:
        assert "water fetch" in str(error)
    else:
        raise AssertionError("a failed fetch must fail the packaging step")