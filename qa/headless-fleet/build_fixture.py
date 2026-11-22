"""Build the dedicated-only diagnostic with the same cached JDK 17 mappings as the game QA."""

from pathlib import Path
import argparse
import os
import subprocess
import zipfile

QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]


def build(out, jdk):
    out.mkdir(parents=True, exist_ok=True)
    cache = ROOT / ".gradle-user/caches"
    research = ROOT / ".research/orbit-dev/original"
    named = next(
        (cache / "forge_gradle/minecraft_user_repo").rglob(
            "forge-1.20.1-47.4.28_mapped_official_1.20.1.jar"
        )
    )
    libs = sorted((research / "launcher/libraries").rglob("*.jar"))
    classes = out / "classes"
    classes.mkdir(exist_ok=True)
    args = [
        "--release",
        "17",
        "-proc:none",
        "-encoding",
        "UTF-8",
        "-d",
        str(classes),
        "-cp",
        os.pathsep.join(map(str, [named, *libs])),
        *map(str, (QA / "src/main/java").rglob("*.java")),
    ]
    argfile = out / "compile.args"
    argfile.write_text(
        "\n".join('"' + str(a).replace("\\", "/") + '"' for a in args), encoding="utf8"
    )
    subprocess.run([jdk / "javac.exe", "@" + str(argfile)], check=True)
    raw = out / "named.jar"
    jar = out / "starbridge-headless-diagnostics-0.1.0.jar"
    with zipfile.ZipFile(raw, "w", zipfile.ZIP_DEFLATED) as z:
        for file in classes.rglob("*.class"):
            z.write(file, file.relative_to(classes))
        z.write(QA / "src/main/resources/META-INF/mods.toml", "META-INF/mods.toml")
    remapper = next(cache.rglob("ForgeAutoRenamingTool-1.0.2-all.jar"))
    with (out / "remap.log").open("wb") as output:
        subprocess.run(
            [
                jdk / "java.exe",
                "-Djava.awt.headless=true",
                "-jar",
                remapper,
                "--input",
                raw,
                "--output",
                jar,
                "--names",
                research / "verified-official-srg.tsrg",
                "--threads",
                "2",
                "--lib",
                named,
            ],
            stdout=output,
            stderr=subprocess.STDOUT,
            check=True,
        )
    return jar


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Java/jdk-17/bin"))
    print(build(QA / ".local/build", p.parse_args().jdk))
