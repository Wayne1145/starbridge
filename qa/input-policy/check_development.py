"""Exercise the real shell/build guards in disposable projects containing *only* inert file-writing tasks."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from test_policy import ROOT, linked_checkout, mark

DISABLED = "Local graphical game tests are disabled on this workstation"


def check_command(command, cwd, environment, *, denied):
    result = subprocess.run(
        command,
        cwd=cwd,
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
        timeout=90,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if denied:
        if result.returncode == 0 or DISABLED not in result.stdout:
            raise AssertionError(result.stdout[-3000:])
    elif result.returncode != 0:
        raise AssertionError(result.stdout[-3000:])


def check_powershell(shell, base, main, linked, env):
    for project in (main, linked):
        scripts = project / "scripts"
        scripts.mkdir()
        for name in (
            "local-client-policy.ps1",
            "dev.ps1",
            "launch-test-client.ps1",
            "launch-observer-client.ps1",
        ):
            shutil.copyfile(ROOT / "scripts" / name, scripts / name)
        # A failed guard could only reach this fake wrapper. There is no Forge build in this directory.
        (project / "gradlew.bat").write_text(
            '@echo off\necho reached > "unexpected-client"\nexit /b 0\n'
        )
    cases = []
    for project in (main, linked):
        for entry, args in [
            ("dev.ps1", ["-Task", "client"]),
            ("launch-test-client.ps1", []),
            ("launch-observer-client.ps1", []),
        ]:
            check_command(
                [
                    str(shell),
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    str(project / "scripts" / entry),
                    *args,
                ],
                project,
                env,
                denied=True,
            )
            assert (
                not (project / "unexpected-client").exists()
                and not (project / "build").exists()
            )
            cases.append(project.name + "/" + entry)
    return cases


def check_gradle(gradle, base, main, linked, env):
    for project in (main, linked):
        (project / "gradle").mkdir()
        shutil.copyfile(
            ROOT / "gradle/local-client.gradle", project / "gradle/local-client.gradle"
        )
        (project / "settings.gradle").write_text(
            "rootProject.name = 'mock-input-policy'\n"
        )
        (
            project / "build.gradle"
        ).write_text("""apply from: 'gradle/local-client.gradle'
tasks.register('runClient') { doLast { file('inert-client-action').text = 'test-only' } }
tasks.register('indirectDependency') { dependsOn 'runClient' }
tasks.register('serverCheck') { doLast { file('inert-server-action').text = 'test-only' } }
tasks.register('markDuringBuild') { doLast { file('.tools').mkdirs(); file('.tools/no-local-game-clients').text = 'fixture' } }
tasks.named('runClient') { mustRunAfter 'markDuringBuild' }
""")

    def execute(project, *tasks, denied):
        check_command(
            [
                str(gradle),
                "--offline",
                "--no-daemon",
                "--console=plain",
                "--max-workers=1",
                *tasks,
            ],
            project,
            env,
            denied=denied,
        )
        if denied:
            assert not (project / "inert-client-action").exists()

    execute(main, "runClient", denied=True)
    execute(linked, "indirectDependency", denied=True)
    execute(linked, "serverCheck", denied=False)
    assert (linked / "inert-server-action").read_text() == "test-only"
    (
        main / ".tools/no-local-game-clients"
    ).unlink()  # A synthetic marker inside this temporary directory.
    execute(linked, "markDuringBuild", "runClient", denied=True)
    return [
        "marked direct task",
        "inherited indirect dependency",
        "marked server task allowed",
        "marker added by prerequisite",
    ]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--powershell", type=Path)
    parser.add_argument("--gradle", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (args.powershell or args.gradle):
        parser.error("Choose a shell and/or offline Gradle installation.")
    receipt = {}
    with tempfile.TemporaryDirectory(prefix="starbridge-input-policy-") as directory:
        base = Path(directory)
        main, linked = linked_checkout(base)
        mark(main)
        if args.powershell:
            receipt["PowerShell"] = check_powershell(
                args.powershell.resolve(strict=True),
                base,
                main,
                linked,
                os.environ.copy(),
            )
        if args.gradle:
            receipt["Gradle"] = check_gradle(
                args.gradle.resolve(strict=True), base, main, linked, os.environ.copy()
            )
    print(json.dumps(receipt, indent=2))
    if args.output:
        args.output.write_text(json.dumps(receipt, indent=2) + "\n", "utf8")
