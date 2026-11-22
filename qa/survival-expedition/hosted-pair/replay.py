"""Read the two clients' actions, server crafting events and stopped NBT directly from a hosted pair artifact."""

import argparse
from collections import Counter
from contextlib import contextmanager
import gzip
import io
import json
from pathlib import Path
import re
import struct
import shutil
import tempfile
import uuid
from zipfile import ZipFile

from trial import ROLES, goal, orders_for, read_json, sha
from yard import REGION, inspect_yard
from continuation import ARCHIVES, check_member
from inspect_checkpoint import read_compound
from runtime import PACK

CRAFT = re.compile(r"^(\d+) (OrbitCaptain|OrbitEngineer) crafted (\d+) (\S+)$")
ACTION = re.compile(r"^\d+ complete (\d+) (.+) at \(([^()]+)\)$")


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def read_world_nbt(world, filename):
    info = world.getinfo(filename)
    require(info.file_size <= 8 * 1024**2, "Oversized saved NBT record")
    raw = world.read(info)
    if raw.startswith(b"\x1f\x8b"):
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            raw = stream.read(16 * 1024**2 + 1)
        require(len(raw) <= 16 * 1024**2, "Oversized decompressed NBT record")
    return read_compound(raw)


def check_actions(text, orders, first, cursor):
    events = []
    for row in text.splitlines():
        found = ACTION.fullmatch(row)
        if found:
            events.append((int(found[1]), found[2]))
    require(0 <= first <= cursor <= len(orders), "Cursor outside recorded orders")
    require(
        events == list(enumerate(orders[first:cursor], start=first)),
        "Client action log differs from the saved order cursors",
    )
    return len(events)


def craft_counts(server_log):
    products = {k: Counter() for k in ROLES}
    for row in server_log.splitlines():
        match = CRAFT.fullmatch(row)
        if match:
            _, name, count, item = match.groups()
            products[name][item] += int(count)
    return products


@contextmanager
def evidence_folder(path):
    if path.is_dir():
        yield path
        return
    for known, digest in ARCHIVES.values():
        if path.resolve() == known.resolve():
            require("sha256:" + sha(path) == digest, "Archived checkpoint differs from GitHub")
    reports = {
        "cloud-world.zip",
        "installed.json",
        "pair-result.json",
        "server-latest.log",
        "server-survival-events.log",
        "server-ops.json",
    }
    for name, uid in ROLES.items():
        reports.add("server-first-join-" + uid + ".json")
        for suffix in ("survival-events.log", "expedition-cursor.txt", "latest.log"):
            reports.add(name + "-" + suffix)
    with tempfile.TemporaryDirectory(prefix="starbridge-pair-replay-") as tmp, ZipFile(path) as archive:
        base = Path(tmp)
        for member in archive.infolist():
            if member.filename in reports:
                limit = 512 * 1024**2 if member.filename == "cloud-world.zip" else 16 * 1024**2
                require(0 <= member.file_size <= limit, "Oversized evidence member")
                with archive.open(member) as source, (base / member.filename).open("xb") as output:
                    shutil.copyfileobj(source, output)
        yield base


def replay(evidence, previous=None):
    with evidence_folder(evidence) as current:
        parent = str(read_json(current / "pair-result.json")["previousRun"])
        if previous is None and parent in ARCHIVES:
            previous = ARCHIVES[parent][0]
        if previous:
            with evidence_folder(previous) as prior:
                return replay_folder(current, prior)
        return replay_folder(current, None)


def replay_folder(evidence, previous):
    summary = read_json(evidence / "pair-result.json")
    installation = read_json(evidence / "installed.json")
    require(
        all(
            installation["installed"][d["directory"] + "/" + d["file"]] == d["sha256"]
            for d in PACK["dependencies"]
        ),
        "Installed original mod set differs from its lock",
    )
    stage, completed = summary["stage"], summary["completed"]
    require(stage in ("workbench", "materials", "yard", "terminal"), "Unknown stage")
    receipt = summary["checkpoint"]
    checkpoint = evidence / "cloud-world.zip"
    require(
        receipt["archive"] == checkpoint.name
        and checkpoint.stat().st_size == receipt["bytes"]
        and sha(checkpoint) == receipt["sha256"],
        "Closed-world digest or size differs",
    )
    source = summary.get("previousCheckpoint")
    starts = summary.get("startingCursors", dict.fromkeys(ROLES, 0))
    prior_crafts = {r: Counter() for r in ROLES}
    prior = None
    if summary["previousRun"]:
        require(
            source is not None or previous is not None,
            "Previous checkpoint is required to verify this resumed run",
        )
        if previous:
            prior = read_json(previous / "pair-result.json")
            prior_archive = previous / "cloud-world.zip"
            require(
                sha(prior_archive) == prior["checkpoint"]["sha256"]
                and prior["firstJoin"] == summary["firstJoin"],
                "Previous world or empty starts differ",
            )
            if source:
                require(
                    prior["checkpoint"]["sha256"] == source["worldSha256"],
                    "Current run restored another checkpoint",
                )
            prior_crafts = craft_counts((previous / "server-survival-events.log").read_text("utf8"))
            prior_cursors = {r: prior["profiles"][r]["cursor"] for r in ROLES}
            require(
                "startingCursors" not in summary or starts == prior_cursors,
                "Starting cursors differ from the previous save",
            )
            starts = prior_cursors
    server_log = (evidence / "server-survival-events.log").read_text("utf8")
    products = craft_counts(server_log)
    server_exit = (evidence / "server-latest.log").read_text("utf8", errors="replace")
    require(
        "All dimensions are saved" in server_exit and summary.get("serverExit", 0) == 0,
        "Missing clean server save",
    )
    if (evidence / "server-ops.json").exists():
        require(read_json(evidence / "server-ops.json") == [], "Operator list is not empty")
    results = {}
    with ZipFile(checkpoint) as world:
        members = [check_member(entry, ROLES) for entry in world.infolist()]
        require(len(members) == len(set(members)), "Duplicate world members")
        require(
            {f"world/playerdata/{uid}.dat" for uid in ROLES.values()}
            == {n for n in world.namelist() if re.fullmatch(r"world/playerdata/[^/]+\.dat", n)},
            "Unexpected saved player identities",
        )
        level = read_world_nbt(world, "world/level.dat")["Data"]
        require(
            level["GameType"] == 0
            and not level["allowCommands"]
            and level["WorldGenSettings"]["seed"] == 576618501,
            "Wrong world seed, mode or cheats",
        )
        yard = None
        if stage == "yard":

            class SavedRegion:
                def read_bytes(self):
                    return world.read(REGION)

            yard = inspect_yard(SavedRegion(), completed)
            require(yard == summary["yard"], "Server and cold pad/seat readings differ")
        for role, uid in ROLES.items():
            origin = summary["firstJoin"][role]
            require(
                origin["name"] == role
                and not any(origin[k] for k in ("items", "creative", "op", "allowCommands")),
                "Missing empty survival start for " + role,
            )
            if not summary["previousRun"]:
                require(
                    "Login " + role + " {}" in server_log.splitlines(),
                    "Missing original empty login event for " + role,
                )
            login_file = evidence / ("server-first-join-" + uid + ".json")
            if login_file.is_file():
                login = read_json(login_file)
                require(
                    login["name"] == role and not any(login[k] for k in ("creative", "op", "allowCommands")),
                    "Unexpected session login for " + role,
                )
                if prior:
                    require(
                        login["items"] == prior["profiles"][role]["inventory"],
                        "Restored login inventory differs from the previous save for " + role,
                    )
            clean = (
                summary["cleanClientExits"].get(role) is True
                and summary.get("clientExitCodes", {}).get(role, 0) == 0
            )
            if completed:
                require(
                    clean
                    and "Stopping!"
                    in (evidence / (role + "-latest.log")).read_text("utf8", errors="replace"),
                    "Missing clean client exit for " + role,
                )
            player = read_world_nbt(world, "world/playerdata/" + uid + ".dat")
            require(
                str(uuid.UUID(bytes=struct.pack(">4i", *player["UUID"]))) == uid
                and player["playerGameType"] == 0
                and player["Dimension"] == "minecraft:overworld",
                "Wrong saved player or mode for " + role,
            )
            inventory = Counter()
            for slot in player["Inventory"]:
                inventory[slot["id"]] += slot["Count"]
            profile = summary["profiles"][role]
            require(
                dict(inventory) == profile["inventory"] and player["Health"] == profile["health"],
                "Player NBT differs from report for " + role,
            )
            orders = world.read(role + "/expedition-orders.txt").decode("utf8").splitlines()
            require(
                orders == orders_for(stage)[role], "Archived orders differ from expected stage for " + role
            )
            commands = [line.strip() for line in orders if line.strip() and not line.lstrip().startswith("#")]
            cursor_name = role + "/expedition-cursor.txt"
            cursor = int(world.read(cursor_name)) if cursor_name in world.namelist() else 0
            require(cursor == profile["cursor"], "Wrong exported cursor for " + role)
            cursor_tail = evidence / (role + "-expedition-cursor.txt")
            if cursor_tail.exists():
                require(
                    int(cursor_tail.read_text("ascii")) == cursor,
                    "Cursor differs from client evidence for " + role,
                )
            client_log = evidence / (role + "-survival-events.log")
            actions = check_actions(
                client_log.read_text("utf8") if client_log.exists() else "", commands, starts[role], cursor
            )
            statfile = "world/stats/" + uid + ".json"
            stats = json.loads(world.read(statfile))["stats"] if statfile in world.namelist() else {}
            crafted = stats.get("minecraft:crafted", {})
            if completed:
                require(cursor == goal(orders) and player["Health"] > 0, "Incomplete or dead player " + role)
                targets = (
                    {"minecraft:crafting_table": 1, "minecraft:wooden_pickaxe": 1}
                    if stage == "workbench"
                    else {"advancedrocketry:tile.concrete": 16}
                    if role == "OrbitCaptain"
                    else {"advancedrocketry:seat": 1}
                )
                if stage == "yard" and role == "OrbitCaptain":
                    targets["advancedrocketry:launchpad"] = 9
                if stage == "terminal":
                    targets = {"starbridge:navigation_slate": 1} if role == "OrbitEngineer" else {}
                for item, count in targets.items():
                    require(
                        crafted.get(item, 0) >= count
                        and (products[role] + prior_crafts[role])[item.split(":", 1)[1]] >= count,
                        "Missing genuine craft event and saved crafting statistic for " + role + " " + item,
                    )
                bag = (
                    {"minecraft:wooden_pickaxe": 1, "minecraft:cobblestone": 14}
                    if stage == "workbench"
                    else targets
                )
                require(
                    stage == "yard" or all(inventory[item] >= count for item, count in bag.items()),
                    "Products missing from inventory of " + role,
                )
            results[role] = {
                "startedCursor": starts[role],
                "cursor": cursor,
                "orders": len(commands),
                "actionsInThisSession": actions,
                "craftedEvents": dict(products[role]),
                "savedCraftedStats": crafted,
                "inventory": dict(inventory),
                "health": player["Health"],
                "position": player["Pos"],
                "clientCleanExit": clean,
            }
    return {
        "completed": completed,
        "stage": stage,
        "source": installation["source"],
        "previousRun": summary["previousRun"],
        "checkpointSha256": receipt["sha256"],
        "serverSaved": True,
        **({"yard": yard} if stage == "yard" else {}),
        "players": results,
    }


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("evidence", type=Path)
    cli.add_argument("--previous", type=Path)
    cli.add_argument("--output", type=Path)
    opt = cli.parse_args()
    result = replay(opt.evidence, opt.previous)
    raw = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
    if opt.output:
        opt.output.write_text(raw, "utf8")
    else:
        print(raw, end="")
