"""Install a fresh crew test on GitHub Linux and read both stopped survival profiles."""

import argparse
import json
from pathlib import Path
import shutil
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "qa/survival-expedition/hosted-pair"))
from trial import ROLES, player_cmd, read_nbt  # noqa: E402 - shared pair runtime
from prepare import MC, FORGE, prepare_installation  # noqa: E402
from run import launch, stop_server, stop_own_process  # noqa: E402
from runtime import hosted_only  # noqa: E402
from smoke import png  # noqa: E402

NAMES = ("crew-auto", "crew-manual", "crew-resumed")
CAPTAIN, ENGINEER = ROLES


def require(ok, message):
    if not ok:
        raise ValueError(message)


def load(path):
    return json.loads(path.read_bytes())


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", "utf8")


def configure(root, fixture):
    server = root / "server"
    clients = [root / r for r in ROLES]
    installed = prepare_installation(root, fixture, clients)
    (server / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=26751\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nmax-players=2\nallow-flight=false\nview-distance=3\nsimulation-distance=3\ngamemode=survival\ndifficulty=peaceful\nlevel-seed=576618501\nenable-command-block=false\nenable-rcon=false\nenable-query=false\n",
        "utf8",
    )
    for directory in (server, *clients):
        (directory / "crew-display.fixture").touch()
    for client in clients:
        (client / "options.txt").write_text(
            "version:3465\nlang:zh_cn\nonboardAccessibility:false\nfullscreen:false\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:3\nguiScale:2\nmaxFps:25\nmipmapLevels:0\ntutorialStep:none\n",
            "utf8",
        )
        (client / "config/forge-client.toml").write_text("[client]\nshowLoadWarnings=false\n", "utf8")
    return installed


def wait(root, file, processes, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        for directory in (root / "server", *(root / name for name in ROLES)):
            error = directory / "crew-error.json"
            if error.exists():
                raise RuntimeError(directory.name + ": " + error.read_text("utf8"))
        closed = root / "server/crew-server.json"
        if closed.exists() and load(closed).get("pass") is not True:
            raise RuntimeError(closed.read_text("utf8"))
        require(all(p.poll() is None for p in processes), "A participant exited before " + file.name)
        if file.exists():
            return
        time.sleep(1)
    raise TimeoutError(file.name)


def verify(report):
    server = report["server"]
    controls = report["captain"]
    crew = report["engineer"]
    frames = report["frames"]
    require(
        server["pass"]
        and server["target"] == controls["target"] == "moon"
        and controls["ship"] == server["ship"],
        "Same active flight",
    )
    require(
        server["nonOp"]
        and not server["engineerOwnsHelm"]
        and server["captain"] == ROLES[CAPTAIN]
        and server["engineer"] == ROLES[ENGINEER],
        "Ordinary crew identities and ownership",
    )
    require(
        controls["inputs"] == ["button orbit.starbridge.take", "button orbit.starbridge.cruise"]
        and controls["cruise"],
        "Captain took and returned control in the flight screen",
    )
    require(
        [row["mode"] for row in server["motion"]] == ["cruise", "manual", "cruise"],
        "Dedicated server observed both captain commands",
    )
    require(
        [row["block"] for row in server["uses"]] == ["helm", "furnace"]
        and all(row["emptyHand"] for row in server["uses"]),
        "Real main-hand block uses by the engineer",
    )
    require(
        server["smelted"] == 1
        and server["machineDistances"][0] - server["machineDistances"][1] > 5
        and crew["closed"],
        "Furnace use during continuing cruise",
    )
    actions = crew["inputs"]
    require(
        len(actions) == 6
        and actions[0].startswith("use helm ")
        and actions[1] == "button gui.done"
        and actions[2].startswith("use furnace ")
        and [v.split()[1] for v in actions[3:]] == ["raw_iron", "coal", "iron_ingot"],
        "Crew used ordinary display, furnace and slots",
    )
    readings = [frames[n]["reading"] for n in NAMES]
    require(
        [r["motion"] for r in readings] == ["CRUISE", "MANUAL", "CRUISE"]
        and [frames[n]["modeLabel"] for n in NAMES] == ["自动巡航", "人工驾驶", "自动巡航"],
        "Mode visible in Chinese",
    )
    require(
        len({r["session"] for r in readings}) == 1
        and all(
            r["ship"] == server["ship"]
            and r["from"] == "home"
            and r["to"] == "moon"
            and r["captain"] == CAPTAIN
            and r["issuer"]["station"] == "HELM"
            and r["issuer"]["computer"] == -1
            for r in readings
        ),
        "Stable viewer session and bridge command issuer",
    )
    require(
        server["fare"] > 0
        and all(r["energy"] > r["reserve"] == server["reserve"] >= server["fare"] for r in readings),
        "Server protection and energy on every display",
    )
    require(
        all(r["speed"] > 0 and 0 < r["remaining"] <= r["span"] for r in readings)
        and readings[0]["remaining"] > readings[-1]["remaining"],
        "Live range and speed readings",
    )
    require(
        all(
            frames[n]["title"] == "船员航行仪表"
            and all(p and not p.startswith("route.") for p in frames[n]["ports"])
            for n in NAMES
        ),
        "Chinese title and localized planet names",
    )
    require(
        all(report["exits"][who] == 0 for who in ("server", *ROLES))
        and report["save"]["ops"] == []
        and not report["save"]["cheats"],
        "Clean shutdown and non-cheat world",
    )
    require(
        all(p["health"] > 0 and p["survival"] for p in report["save"]["players"].values())
        and report["save"]["players"][ENGINEER]["iron"] == 1,
        "Closed NBT retains the engineer’s own ingot",
    )


def collect(root, evidence):
    for role in ("server", *ROLES):
        side = root / role
        for p in (
            *side.glob("crew-*.json"),
            *side.glob("crew-inputs.log"),
            *(side / "screenshots").glob("crew-*.png"),
        ):
            shutil.copyfile(p, evidence / (role + "-" + p.name))
        for name in ("java.log", "logs/latest.log"):
            log = side / name
            if log.exists():
                with log.open("rb") as f:
                    f.seek(max(0, log.stat().st_size - 96 * 1024))
                    (evidence / (role + "-" + log.name)).write_bytes(f.read())


def run(root, evidence, fixture):
    hosted_only()
    evidence.mkdir(parents=True, exist_ok=True)
    server = root / "server"
    peers = {}
    dedicated = None
    try:
        install = configure(root, fixture)
        write(evidence / "installed.json", install)
        dedicated = launch(
            [
                "java",
                "-XX:ActiveProcessorCount=2",
                "-Xms384m",
                "-Xmx3072m",
                "-Djava.awt.headless=true",
                "-Dio.netty.eventLoopThreads=8",
                "-Dretrofit.minecraftHome=" + str(server / "legacy-launcher"),
                f"@libraries/net/minecraftforge/forge/{MC}-{FORGE}/unix_args.txt",
                "nogui",
            ],
            server,
        )
        wait(root, server / "crew-server-ready.json", [dedicated], 480)
        for name in ROLES:
            side = root / name
            peers[name] = launch(player_cmd(root, side), side)
            wait(root, side / "crew-online.json", [dedicated, *peers.values()], 350)
        wait(root, server / "crew-server.json", [dedicated, *peers.values()], 420)
        for name in ROLES:
            (root / name / "crew.stop").touch()
        exits = {name: p.wait(timeout=65) for name, p in peers.items()}
        stop_server(dedicated)
        exits["server"] = dedicated.wait(timeout=100)
        require(
            "All dimensions are saved" in (server / "logs/latest.log").read_text("utf8", errors="replace"),
            "Dedicated server saved",
        )
        world = server / "world"
        level = read_nbt(world / "level.dat")["Data"]
        saved = {"ops": load(server / "ops.json"), "cheats": bool(level["allowCommands"]), "players": {}}
        for name, uuid in ROLES.items():
            path = world / "playerdata" / (uuid + ".dat")
            p = read_nbt(path)
            shutil.copyfile(path, evidence / (name + ".dat"))
            saved["players"][name] = {
                "survival": p["playerGameType"] == 0,
                "health": p["Health"],
                "position": p["Pos"],
                "iron": sum(s["Count"] for s in p["Inventory"] if s["id"] == "minecraft:iron_ingot"),
            }
        shutil.copyfile(world / "level.dat", evidence / "level.dat")
        shutil.copyfile(server / "ops.json", evidence / "ops.json")
        report = {
            "source": install["source"],
            "server": load(server / "crew-server.json"),
            "captain": load(root / CAPTAIN / "crew-controls.json"),
            "engineer": load(root / ENGINEER / "crew-machine.json"),
            "frames": {n: load(root / ENGINEER / (n + ".json")) for n in NAMES},
            "screenshots": {
                n: png(root / ENGINEER / "screenshots" / (n + ".png")) for n in (*NAMES, "crew-furnace")
            },
            "save": saved,
            "exits": exits,
        }
        verify(report)
        write(evidence / "crew-result.json", report)
    except BaseException as err:
        write(evidence / "failure.json", {"error": str(err) or type(err).__name__})
        raise
    finally:
        for name, peer in peers.items():
            (root / name / "crew.stop").touch(exist_ok=True)
            stop_own_process(peer)
        stop_server(dedicated)
        stop_own_process(dedicated)
        collect(root, evidence)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("root", "evidence", "fixture"):
        parser.add_argument("--" + key, type=Path, required=True)
    a = parser.parse_args()
    run(a.root.resolve(), a.evidence.resolve(), a.fixture.resolve(strict=True))
