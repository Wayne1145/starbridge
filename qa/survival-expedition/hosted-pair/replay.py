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

from trial import ROLES, STAGES, TITANIUM_STAGES, WORKSHOP_SUPPLIES, YARD_STAGES, goal, orders_for, read_json, sha, terminal_guide, valid_saved_dimension
from yard import REGION, has_pressed_tin, has_titanium_ore, has_water_bottles, inspect_yard
from continuation import ARCHIVES, check_member, read_nether_exit
from inspect_checkpoint import read_compound, region_name, sample_blocks
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


def orders_in_save(world, role):
    raw = world.read(role + "/expedition-orders.txt").decode("utf8").splitlines()
    commands = [line.strip() for line in raw if line.strip() and not line.lstrip().startswith("#")]
    return raw, commands


def crafts_in_save(world, role):
    filename = "world/stats/" + ROLES[role] + ".json"
    if filename not in world.namelist():
        return {}
    require(world.getinfo(filename).file_size <= 8 * 1024**2, "Oversized saved player stats")
    return json.loads(world.read(filename))["stats"].get("minecraft:crafted", {})


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
        "OrbitEngineer-survival-first-guide.json",
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
    require(stage in STAGES, "Unknown stage")
    if "terminalGuide" in summary:
        require(stage == "terminal" and terminal_guide(evidence / "OrbitEngineer-survival-first-guide.json") == summary["terminalGuide"],
                "Reported terminal guide differs from the client receipt")
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
    prior_crafts = {r: {} for r in ROLES}
    prior_orders = {}
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
            prior_cursors = {r: prior["profiles"][r]["cursor"] for r in ROLES}
            with ZipFile(prior_archive) as stopped:
                for role, cursor in prior_cursors.items():
                    _, commands = orders_in_save(stopped, role)
                    cursor_file = role + "/expedition-cursor.txt"
                    saved_cursor = int(stopped.read(cursor_file)) if cursor_file in stopped.namelist() else 0
                    require(0 <= cursor == saved_cursor <= len(commands), "Invalid previous cursor for " + role)
                    prior_orders[role] = commands[:cursor]
                    prior_crafts[role] = crafts_in_save(stopped, role)
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
        if stage in YARD_STAGES:

            class SavedRegion:
                def read_bytes(self):
                    return world.read(REGION)

            yard = inspect_yard(SavedRegion(), completed, stage)
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
                and valid_saved_dimension(stage, role, completed, player["Dimension"]),
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
            orders, commands = orders_in_save(world, role)
            if role in prior_orders:
                require(commands[:starts[role]] == prior_orders[role], "Executed orders differ from previous save for " + role)
            cursor_name = role + "/expedition-cursor.txt"
            cursor = int(world.read(cursor_name)) if cursor_name in world.namelist() else 0
            require(cursor == profile["cursor"], "Wrong exported cursor for " + role)
            expected_orders = orders_for(stage)[role]
            if completed:
                require(orders == expected_orders, "Completed orders differ from the stage for " + role)
            else:
                expected = [line.strip() for line in expected_orders if line.strip() and not line.lstrip().startswith("#")]
                require(commands[:cursor] == expected[:cursor], "Executed order prefix differs for " + role)
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
            crafted = crafts_in_save(world, role)
            checked_previous = {}
            if completed:
                require(cursor == goal(orders) and player["Health"] > 0, "Incomplete or dead player " + role)
                targets = (
                    {"minecraft:crafting_table": 1, "minecraft:wooden_pickaxe": 1}
                    if stage == "workbench"
                    else {"advancedrocketry:tile.concrete": 16}
                    if role == "OrbitCaptain"
                    else {"advancedrocketry:seat": 1}
                )
                if stage in YARD_STAGES and role == "OrbitCaptain":
                    targets["advancedrocketry:launchpad"] = 9
                if stage == "terminal":
                    targets = {"starbridge:navigation_slate": 1} if role == "OrbitEngineer" else {}
                if stage in WORKSHOP_SUPPLIES:
                    workshop_crafts = {"minecraft:bucket": 2} if role == "OrbitCaptain" else {"minecraft:stone_axe": 1}
                    power_crafts = ({"minecraft:iron_pickaxe": 1} if role == "OrbitCaptain" else
                                    {"libvulpes:battery": 4, "libvulpes:tile.coalgenerator": 1})
                    portal_crafts = ({"minecraft:diamond_pickaxe": 1, "minecraft:flint_and_steel": 1}
                                     if role == "OrbitCaptain" else {})
                    for milestone, predecessor, crafted_here in (
                        ("surface-workshop", "surface-yard", workshop_crafts),
                        ("surface-power", "surface-press", power_crafts),
                        ("surface-portal-stock", "surface-metals", portal_crafts),
                        ("surface-brick-stock", "surface-nether-supplies",
                         {"minecraft:bricks": 24} if role == "OrbitEngineer" else {}),
                        ("surface-brew-water", "surface-brick-stock",
                         {"minecraft:glass_bottle": 12} if role == "OrbitEngineer" else {}),
                    ):
                        if YARD_STAGES.index(stage) < YARD_STAGES.index(milestone):
                            continue
                        for item, count in crafted_here.items():
                            if stage == milestone and previous and prior["stage"] == predecessor:
                                require(products[role][item.split(":", 1)[1]] >= count,
                                        "Missing workshop craft for " + role + " " + item)
                            targets[item] = count
                    needed = WORKSHOP_SUPPLIES[stage][role]
                    require(all(inventory[item] >= count for item, count in needed.items()),
                            "Workshop supplies missing from the closed inventory of " + role)
                    if stage == "surface-press" and role == "OrbitEngineer":
                        require(has_pressed_tin(player["Inventory"]), "Saved engineer lacks four original tin plates")
                    if stage in TITANIUM_STAGES and role == "OrbitCaptain":
                        require(has_titanium_ore(player["Inventory"]), "Saved captain lacks twelve original titanium ores")
                    if stage == "surface-brew-water" and role == "OrbitEngineer":
                        require(has_water_bottles(player["Inventory"]), "Saved engineer lacks twelve filled water bottles")
                for item, count in targets.items():
                    recorded_now = products[role][item.split(":", 1)[1]]
                    recorded_before = prior_crafts[role].get(item, 0)
                    require(
                        crafted.get(item, 0) >= count and recorded_now + recorded_before >= count,
                        "Missing genuine craft event or matching predecessor statistics for " + role + " " + item,
                    )
                    if recorded_now < count:
                        checked_previous[item] = recorded_before
                bag = (
                    {"minecraft:wooden_pickaxe": 1, "minecraft:cobblestone": 14}
                    if stage == "workbench"
                    else targets
                )
                require(
                    stage in YARD_STAGES or all(inventory[item] >= count for item, count in bag.items()),
                    "Products missing from inventory of " + role,
                )
            results[role] = {
                "startedCursor": starts[role],
                "cursor": cursor,
                "orders": len(commands),
                "actionsInThisSession": actions,
                "craftedEvents": dict(products[role]),
                "savedCraftedStats": crafted,
                **({"craftsVerifiedInPreviousSave": checked_previous} if checked_previous else {}),
                "inventory": dict(inventory),
                "health": player["Health"],
                "position": player["Pos"],
                **({"dimension": player["Dimension"]} if stage in ("surface-nether", "surface-nether-supplies") else {}),
                "clientCleanExit": clean,
            }
        if completed and stage == "surface-nether-supplies":
            exit_pos = read_nether_exit(world)
            region = "world/DIM-1/region/" + region_name(exit_pos[0], exit_pos[2])
            require(world.getinfo(region).file_size <= 64 * 1024**2, "Oversized saved Nether portal region")

            class ClosedNetherRegion:
                def read_bytes(self):
                    return world.read(region)

            require(sample_blocks(ClosedNetherRegion(), [exit_pos]).get(exit_pos) == "minecraft:nether_portal",
                    "The saved Nether exit must be an actual portal block")
            results["OrbitCaptain"]["netherExit"] = list(exit_pos)
    return {
        "completed": completed,
        "stage": stage,
        "source": installation["source"],
        "previousRun": summary["previousRun"],
        "checkpointSha256": receipt["sha256"],
        "serverSaved": True,
        **({"yard": yard} if stage in YARD_STAGES else {}),
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
