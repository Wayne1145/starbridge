"""Build only this local Forge QA jar, using the checkout's existing Java 17 game libraries."""

import argparse
import os
from pathlib import Path
import subprocess
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
QA = Path(__file__).resolve().parent


def build(out, jdk):
    out.mkdir(parents=True, exist_ok=True)
    caches = ROOT / ".gradle-user"
    named = next(
        (caches / "caches/forge_gradle/minecraft_user_repo").rglob(
            "forge-1.20.1-47.4.28_mapped_official_1.20.1.jar"
        )
    )
    local = ROOT / ".research/orbit-dev/original"
    deps = [
        named,
        ROOT / "build/classes/java/main",
        local / "qa/named-main-classes.jar",
        *sorted((local / "launcher/libraries").rglob("*.jar")),
    ]
    profile_fixture = ROOT / "qa/home-berths/src/main"
    sources = list((QA / "src/main/java").rglob("*.java"))
    sources.extend((profile_fixture / "java/dev/starbridge/qa/home/landing").rglob("*.java"))
    for name in ("ConnectedFleet", "FleetPeers"):
        sources.append(
            ROOT
            / (
                "qa/connected-fleet/src/main/java/dev/starbridge/qa/fleet/"
                + name
                + ".java"
            )
        )
    classes = out / "classes"
    classes.mkdir(exist_ok=True)
    args = [
        "-proc:none",
        "-encoding",
        "UTF-8",
        "-d",
        str(classes),
        "-cp",
        os.pathsep.join(map(str, deps)),
        *map(str, sources),
    ]
    argfile = out / "javac.args"
    argfile.write_text(
        "\n".join('"' + a.replace("\\", "/") + '"' for a in args), encoding="utf8"
    )
    subprocess.run([str(jdk / "javac.exe"), "@" + str(argfile)], check=True)
    raw = out / "named.jar"
    jar = out / "starbridge-concurrent-qa.jar"
    with ZipFile(raw, "w", ZIP_DEFLATED) as z:
        for file in classes.rglob("*.class"):
            z.write(file, file.relative_to(classes))
        z.write(QA / "src/main/resources/META-INF/mods.toml", "META-INF/mods.toml")
        z.write(QA / "src/main/resources/berth-qa.mixins.json", "berth-qa.mixins.json")
        z.write(profile_fixture / "resources/starbridge-landing-profile.mixins.json", "starbridge-landing-profile.mixins.json")
        z.writestr(
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\nMixinConfigs: berth-qa.mixins.json,starbridge-landing-profile.mixins.json\r\n\r\n",
        )
    tool = next(caches.rglob("ForgeAutoRenamingTool-1.0.2-all.jar"))
    args = [
        jdk / "java.exe",
        "-jar",
        tool,
        "--input",
        raw,
        "--output",
        jar,
        "--names",
        local / "verified-official-srg.tsrg",
        "--threads",
        "2",
        "--lib",
        named,
        "--lib",
        local / "qa/named-main-classes.jar",
    ]
    with (out / "remap.log").open("wb") as log:
        subprocess.run(
            list(map(str, args)), stdout=log, stderr=subprocess.STDOUT, check=True
        )
    print(jar)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("output", type=Path)
    p.add_argument(
        "--jdk", type=Path, default=Path(r"C:/Program Files/Java/jdk-17/bin")
    )
    a = p.parse_args()
    build(a.output.resolve(), a.jdk)
