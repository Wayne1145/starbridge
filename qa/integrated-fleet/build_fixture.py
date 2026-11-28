"""Build this isolated Forge 1.20.1 QA add-on with the checkout's Java 17 caches."""

import os
import subprocess
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
JDK = Path("C:/Program Files/Java/jdk-17/bin")
OUT = HERE / ".local/build"
OUT.mkdir(parents=True, exist_ok=True)
cache = ROOT / ".gradle-user"
prefix = ROOT / ".research/orbit-dev/original"
named = next(
    (cache / "caches/forge_gradle/minecraft_user_repo").rglob(
        "forge-1.20.1-47.4.28_mapped_official_1.20.1.jar"
    )
)
libs = sorted((prefix / "launcher/libraries").rglob("*.jar"))
classes = OUT / "classes"
classes.mkdir(exist_ok=True)
files = [*map(str, (HERE / "src/main/java").rglob("*.java"))]
args = [
    "-proc:none",
    "-encoding",
    "UTF-8",
    "-cp",
    os.pathsep.join(
        map(
            str,
            [
                named,
                ROOT / "build/classes/java/main",
                prefix / "qa/named-main-classes.jar",
                *libs,
            ],
        )
    ),
    "-d",
    str(classes),
    *files,
]
af = OUT / "javac.args"
af.write_text(
    "\n".join('"' + a.replace("\\", "/") + '"' for a in args), encoding="utf8"
)
subprocess.run([str(JDK / "javac.exe"), "@" + str(af)], check=True)
with ZipFile(OUT / "named.jar", "w", ZIP_DEFLATED) as z:
    for f in classes.rglob("*.class"):
        z.write(f, f.relative_to(classes))
    z.write(HERE / "src/main/resources/META-INF/mods.toml", "META-INF/mods.toml")
    z.write(
        HERE / "src/main/resources/starbridge-integrated-qa.mixins.json",
        "starbridge-integrated-qa.mixins.json",
    )
    z.writestr(
        "META-INF/MANIFEST.MF",
        "Manifest-Version: 1.0\r\nMixinConfigs: starbridge-integrated-qa.mixins.json\r\n\r\n",
    )
tool = next(cache.rglob("ForgeAutoRenamingTool-1.0.2-all.jar"))
jar = OUT / "starbridge-integrated-qa.jar"
with (OUT / "remap.log").open("wb") as f:
    subprocess.run(
        list(
            map(
                str,
                [
                    JDK / "java.exe",
                    "-jar",
                    tool,
                    "--input",
                    OUT / "named.jar",
                    "--output",
                    jar,
                    "--names",
                    prefix / "verified-official-srg.tsrg",
                    "--threads",
                    2,
                    "--lib",
                    named,
                ],
            )
        ),
        check=True,
        stdout=f,
        stderr=subprocess.STDOUT,
    )
print(jar)
