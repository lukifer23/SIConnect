#!/usr/bin/env python3
"""Build a separate disposable test APK with Android SDK tools; no new Gradle/runtime module."""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path, help="New directory outside the public repository")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    repository = source.parent.parent
    output = args.output_dir.expanduser().resolve()
    if output == repository or repository in output.parents:
        parser.error("Fixture APKs and signing material must remain outside the public repository")
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    sdk = args.sdk.expanduser().resolve()
    tools = sdk / "build-tools/37.0.0"
    android = sdk / "platforms/android-37.0/android.jar"
    for path in [android, *(tools / name for name in ("aapt2", "d8", "zipalign", "apksigner"))]:
        if not path.is_file():
            parser.error(f"Required Android SDK component missing: {path.name}")
    classes = output / "classes"
    dex = output / "dex"
    classes.mkdir(); dex.mkdir()
    env = dict(os.environ)
    env["SICONNECT_FIXTURE_PASSWORD"] = secrets.token_hex(24)
    def run(argv):
        result = subprocess.run([str(item) for item in argv], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            detail = result.stderr.decode("utf-8", errors="replace")[-6000:]
            raise SystemExit(f"Fixture build step {Path(str(argv[0])).name} failed ({result.returncode}):\n{detail}")
    unsigned = output / "unsigned.apk"
    run([tools / "aapt2", "link", "--manifest", source / "AndroidManifest.xml", "-I", android, "-o", unsigned])
    run(["javac", "-source", "17", "-target", "17", "-classpath", android, "-d", classes, *sorted((source / "src").rglob("*.java"))])
    run([tools / "d8", "--lib", android, "--min-api", "31", "--output", dex, *sorted(classes.rglob("*.class"))])
    with zipfile.ZipFile(unsigned, "a") as apk:
        apk.write(dex / "classes.dex", "classes.dex")
    aligned = output / "aligned.apk"
    run([tools / "zipalign", "-f", "4", unsigned, aligned])
    key = output / "fixture-signing.p12"
    run(["keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-storepass:env", "SICONNECT_FIXTURE_PASSWORD",
         "-keypass:env", "SICONNECT_FIXTURE_PASSWORD", "-alias", "fixture", "-keyalg", "RSA", "-validity", "365", "-dname", "CN=Disposable SIConnect test fixture"])
    key.chmod(0o600)
    apk = output / "notification-fixture.apk"
    run([tools / "apksigner", "sign", "--ks", key, "--ks-pass", "env:SICONNECT_FIXTURE_PASSWORD", "--key-pass", "env:SICONNECT_FIXTURE_PASSWORD", "--out", apk, aligned])
    run([tools / "apksigner", "verify", apk])
    apk.chmod(0o600)
    print(f"Built and signature-verified disposable fixture: {apk}")
    print("Package: dev.siconnect.notificationfixture; install/grant/launch only when explicitly coordinating its physical test.")


if __name__ == "__main__":
    main()
