"""Build the standalone AsterRPG Java launcher and optionally run its tests."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "vendor"
BUILD = ROOT / "build"
DEPENDENCIES = {
    "gson-2.13.2.jar": "com/google/code/gson/gson/2.13.2/gson-2.13.2.jar",
    "jna-5.17.0.jar": "net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar",
    "jna-platform-5.17.0.jar": "net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar",
    "sqlite-jdbc-3.50.3.0.jar": "org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar",
}


def java_tool(name: str) -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        path = Path(java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if not path.is_file():
            raise SystemExit(f"JAVA_HOME does not contain {name}: {path}")
        return str(path)
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"Java 21 JDK is required; set JAVA_HOME to its installation directory ({name} not found)")
    return path


def dependencies() -> None:
    VENDOR.mkdir(exist_ok=True)
    for name, relative in DEPENDENCIES.items():
        destination = VENDOR / name
        if destination.is_file():
            continue
        url = "https://repo.maven.apache.org/maven2/" + relative
        temporary = destination.with_suffix(".part")
        print(f"Downloading {name}", flush=True)
        try:
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)


def sources(folder: Path) -> list[str]:
    found = sorted(folder.rglob("*.java"))
    if not found:
        raise SystemExit(f"No Java sources in {folder}")
    return [str(path) for path in found]


def run(*command: str) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test", action="store_true", help="Run the four standalone Java test programs")
    parser.add_argument("--bootstrap", action="store_true", help="Compile the NSIS Windows launcher entrypoint")
    args = parser.parse_args()

    dependencies()
    javac, java, jar = (java_tool(name) for name in ("javac", "java", "jar"))
    classes = BUILD / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    cp = str(VENDOR / "*")
    run(javac, "-encoding", "UTF-8", "--release", "21", "-cp", cp, "-d", str(classes),
        *sources(ROOT / "src/main/java"))
    shutil.copytree(ROOT / "src/main/resources", classes, dirs_exist_ok=True)
    launcher_jar = BUILD / "aster-auth-launcher.jar"
    run(jar, "--create", "--file", str(launcher_jar), "--main-class", "cn.aster.launcher.Main",
        "-C", str(classes), ".")

    tool_classes = BUILD / "tool-classes"
    tool_classes.mkdir(exist_ok=True)
    runtime_cp = os.pathsep.join((str(classes), cp))
    run(javac, "--release", "21", "-encoding", "UTF-8", "-cp", runtime_cp, "-d", str(tool_classes),
        *sources(ROOT / "src/tools/java"))
    run(java, "-Djava.awt.headless=true", "-cp", os.pathsep.join((runtime_cp, str(tool_classes))),
        "cn.aster.launcher.ExportLauncherBrand", str(BUILD / "brand"))

    if args.test:
        tests = BUILD / "test-classes"
        tests.mkdir(exist_ok=True)
        run(javac, "--release", "21", "-encoding", "UTF-8", "-cp", runtime_cp, "-d", str(tests),
            *sources(ROOT / "src/test/java"))
        test_cp = os.pathsep.join((runtime_cp, str(tests)))
        for name in ("GameInstallationTest", "LauncherViewTest", "ClientUpdaterTest", "ResourcePacksTest"):
            arguments = [java, "-Djava.awt.headless=true", "-cp", test_cp, f"cn.aster.launcher.{name}"]
            if name == "LauncherViewTest":
                arguments.append(str(BUILD / "ui"))
            run(*arguments)

    if args.bootstrap:
        makensis = shutil.which("makensis")
        if not makensis:
            raise SystemExit("NSIS makensis is required for --bootstrap")
        destination = BUILD / "bootstrap" / "AsterRPG.exe"
        destination.parent.mkdir(exist_ok=True)
        run(makensis, "-V2", f"-DOUTPUT={destination}", f"-DBRAND={BUILD / 'brand'}",
            str(ROOT / "installer/client-bootstrap.nsi"))
        print(f"Built {destination.relative_to(ROOT)}")
    print(f"Built {launcher_jar.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
