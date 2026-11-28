"""Check the shipped Lua peripheral, MCP process and pilot takeover on a hosted Linux game."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "qa/linux-fleet"))
sys.path.insert(0, str(ROOT / "qa/remote-ui"))
from prepare import FORGE, MC, prepare_installation
from run import launch, stop_own_process, stop_server, write_json
from runtime import command, hosted_only, sha
from smoke import png

PORT = 26877
PILOT = "LunarTester"


def require(check, message):
    if not check:
        raise ValueError(message)


def wait_until(condition, processes, limit, activity):
    end = time.monotonic() + limit
    while time.monotonic() < end:
        require(
            all(p.poll() is None for p in processes),
            "A process exited during " + activity,
        )
        if condition():
            return
        time.sleep(0.75)
    raise TimeoutError(activity)


def read(path):
    return json.loads(path.read_bytes())


def run(root, evidence, fixture):
    hosted_only()
    require(
        os.environ.get("DISPLAY", "").startswith(":"),
        "Expected the hosted Xvfb display",
    )
    server_dir, pilot = root / "server", root / PILOT
    evidence.mkdir(parents=True, exist_ok=True)
    installed = prepare_installation(root, fixture, [pilot])
    write_json(evidence / "installed.json", installed)
    (server_dir / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nmax-players=1\nallow-flight=true\nview-distance=3\nsimulation-distance=3\ngamemode=survival\ndifficulty=peaceful\nlevel-seed=576618501\nenable-command-block=false\nenable-rcon=false\n",
        "utf8",
    )
    for side in (server_dir, pilot):
        (side / "starbridge-controls.fixture").touch()
    (pilot / "lunar-server.txt").write_text(f"127.0.0.1:{PORT}", "ascii")
    (pilot / "options.txt").write_text(
        "version:3465\nlang:zh_cn\nonboardAccessibility:false\nfullscreen:false\nkey_key.starbridge.orbit.forward:key.keyboard.i\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:3\nguiScale:2\nmaxFps:30\ntutorialStep:none\n",
        "utf8",
    )
    (pilot / "config/forge-client.toml").write_text(
        "[client]\nshowLoadWarnings=false\n", "utf8"
    )
    bridge_dir = root / "bridge-process"
    bridge_dir.mkdir()
    dedicated = client = bridge = None
    try:
        dedicated = launch(
            [
                "java",
                "-XX:ActiveProcessorCount=2",
                "-Djava.awt.headless=true",
                "-Xms384m",
                "-Xmx3072m",
                "-Dio.netty.eventLoopThreads=8",
                "-Dretrofit.minecraftHome=" + str(server_dir / "legacy-launcher"),
                f"@libraries/net/minecraftforge/forge/{MC}-{FORGE}/unix_args.txt",
                "nogui",
            ],
            server_dir,
        )
        log = server_dir / "logs/latest.log"
        wait_until(
            lambda: log.is_file()
            and "Done (" in log.read_text("utf8", errors="replace"),
            [dedicated],
            480,
            "Forge startup",
        )
        args = command(root / "launcher", pilot)
        args[args.index("--username") + 1] = PILOT
        digest = hashlib.md5(("OfflinePlayer:" + PILOT).encode()).digest()
        args[args.index("--uuid") + 1] = uuid.UUID(bytes=digest, version=3).hex
        args[1:1] = [
            "-Dio.netty.eventLoopThreads=6",
            "-Dretrofit.minecraftHome=" + str(pilot / "legacy-launcher"),
        ]
        client = launch(args + ["--quickPlayMultiplayer", f"127.0.0.1:{PORT}"], pilot)
        wait_until(
            (server_dir / "controls-ticket.json").is_file,
            [dedicated, client],
            360,
            "player and computer setup",
        )
        bridge = launch(
            [sys.executable, "-B", str(HERE / "mcp_session.py"), str(server_dir)],
            bridge_dir,
        )

        def finished():
            require(bridge.poll() in (None, 0), "MCP observer failed")
            return (server_dir / "controls-result.json").is_file()

        wait_until(finished, [dedicated, client], 360, "Lua, MCP and manual control")
        require(bridge.wait(timeout=30) == 0, "MCP did not exit normally")
        mission = read(server_dir / "controls-result.json")
        mcp = read(server_dir / "mcp-results.json")
        require(
            mission["pass"]
            and mission["ownerNonOp"]
            and mission["survival"] == "survival",
            "Owner and native controls",
        )
        require(
            mission["pairingHistory"]
            == [-1, mission["computer"], -1, mission["computer"]]
            and mission["actualBlock"].startswith("computercraft:"),
            "Native computer pairing",
        )
        require(
            [e["stage"] for e in mcp]
            == [
                "not authorized",
                "authorized chart",
                "agent control",
                "manual priority",
                "external control restored",
            ],
            "Agent controls in order",
        )
        require(
            mcp[2]["holdAccepted"]
            and mcp[2]["courseAccepted"]
            and mcp[4]["courseAccepted"],
            "MCP acceptance",
        )
        require(
            mission["manual-denied"]
            and mission["braked"]
            and mission["moving"]
            and mission["manualClaim"],
            "Lua takeover and release",
        )
        (pilot / "controls.stop").touch()
        require(client.wait(timeout=75) == 0, "Client failed to exit normally")
        stop_server(dedicated)
        require(dedicated.wait(timeout=90) == 0, "Server failed to save")
        require(
            "All dimensions are saved" in log.read_text("utf8", errors="replace")
            and read(server_dir / "ops.json") == [],
            "Closed world and operator list",
        )
        pics = {f.name: png(f) for f in sorted((pilot / "screenshots").glob("*.png"))}
        require(
            {
                "01-computer-found.png",
                "02-computer-paired.png",
                "03-computer-revoked.png",
            }
            <= pics.keys(),
            "Missing player device views",
        )
        write_json(
            evidence / "controls-hosted.json",
            {
                "source": installed["source"],
                "fixture": sha(fixture),
                "survival": True,
                "serverExit": dedicated.returncode,
                "clientExit": client.returncode,
                "bridgeExit": bridge.returncode,
                "screenshots": pics,
            },
        )
    finally:
        if client is not None:
            (pilot / "controls.stop").touch()
            stop_own_process(client)
        stop_server(dedicated)
        stop_own_process(dedicated)
        stop_own_process(bridge)
        # Only named scenario observations are exported. The local agent credential stays in the runtime.
        for side, names in [
            (
                server_dir,
                (
                    "controls-result.json",
                    "controls-live.json",
                    "mcp-results.json",
                    "mcp-failure.json",
                    "ops.json",
                ),
            ),
            (pilot, ("controls-input.log",)),
        ]:
            for name in names:
                if (side / name).is_file():
                    shutil.copyfile(side / name, evidence / name)
        for img in (pilot / "screenshots").glob("*.png"):
            shutil.copyfile(img, evidence / img.name)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for arg in ("root", "evidence", "fixture"):
        cli.add_argument("--" + arg, required=True, type=Path)
    opts = cli.parse_args()
    run(opts.root.resolve(), opts.evidence.resolve(), opts.fixture.resolve(strict=True))
