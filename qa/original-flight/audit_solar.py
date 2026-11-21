"""Audit the original full-mod solar-exposure control and a completed protected lunar mission."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def endpoint(root: Path) -> dict:
    folders = ("server", "OrbitCaptain", "OrbitEngineer")
    jars = {name: {p.name: sha(p) for p in sorted((root / "server" / name).glob("*.jar"))} for name in ("mods", "retrofit/mods")}
    agreed = {}
    for file in sorted((root / "server/mods").glob("starbridge-*.jar")):
        agreed[file.name] = {folder: sha(root / folder / "mods" / file.name) for folder in folders}
    files = ["server/flight-result.json", "server/crew-health.jsonl", "server/console.log", "server/retrofit/config/GalaxySpace/core.conf", "OrbitCaptain/flight-input.log", "OrbitEngineer/flight-input.log", "launch-result.json"]
    return {"run": root.name, "installedJars": jars, "threeEndpointHashes": agreed,
            "rawEvidenceSha256": {name: sha(root / name) for name in files},
            "solarSystemEnabled": "B:enableSolarRadiationSystem=true" in (root / files[3]).read_text(encoding="utf-8"),
            "game": read(root / files[0]), "launcher": read(root / files[-1])}


def samples(root: Path) -> list[dict]:
    return [json.loads(line) for line in (root / "server/crew-health.jsonl").read_text(encoding="utf-8").splitlines()]


def audit(control: Path, protected: Path) -> dict:
    bad, good = endpoint(control), endpoint(protected)
    baseline = [x for x in samples(control) if x.get("who") == "OrbitCaptain"]
    native = [x for x in baseline if x.get("phase") == "original:LivingHurtEvent"]
    modern = [x for x in baseline if x.get("phase") == "damage:false"]
    death = next(x for x in baseline if x.get("phase") == "death:false")
    bad["damage"] = {"nativeAcceptedTicks": [x["tick"] for x in native], "modernAcceptedTicks": [x["tick"] for x in modern], "source": native[0]["source"], "sourceClass": native[0]["sourceClass"], "hostType": modern[0]["type"], "amount": native[0]["amount"], "originalEquipmentAtDeath": death["oldArmor"], "gcAtDeath": death["GC"], "position": death["position"], "dimension": death["world"], "deathEffects": death["effects"], "inWall": death["inWall"], "fallDistance": death["fallDistance"], "fullHealthBeforeFirstHit": native[0]["health"], "callToHost": modern[0]["caller"]}
    rows = samples(protected)
    good["lunarCrew"] = {}
    for who in ("OrbitCaptain", "OrbitEngineer"):
        observed = [x for x in rows if x.get("who") == who and x.get("world") == "retrofit:legacy_dim_-28"]
        good["lunarCrew"][who] = {"samples":len(observed), "firstTick":observed[0]["tick"], "lastTick":observed[-1]["tick"], "minimumHP":min(x["health"] for x in observed), "originalDamageSources":dict(Counter(x["source"] for x in observed if x.get("phase") == "original:LivingHurtEvent")), "wearing":observed[-1].get("oldArmor"), "gcEquipment":observed[-1].get("GC")}
    trip = good["game"]
    result = {
        "solarCauseConfirmed": bool(native) and all(x["source"] == "solar" for x in native) and any("galaxyspace.core.prefab.potions.Radiation" in c for c in modern[0]["caller"]),
        "nativeAcceptedHitsMatchHost": [(x["tick"], x["amount"]) for x in native] == [(x["tick"], x["amount"]) for x in modern],
        "controlDiedDespiteOxygen": bad["game"]["pass"] is False and death["health"] == 0 and death["GC"]["oxygenSetupValid"] and death["GC"]["airRemaining"] > 0 and not death["inWall"],
        "solarStayedEnabled": bad["solarSystemEnabled"] and good["solarSystemEnabled"],
        "allNativeRecipeOutputs": all(len(s["recipes"]) == 4 and all("diamond" in recipe["reactorInputs"] and "retrofit:spacebreathing" in recipe["originalTag"] for recipe in s["recipes"]) for s in trip["suits"]),
        "healthyCaptainWithNoSolarHits": good["lunarCrew"]["OrbitCaptain"]["minimumHP"] == 20 and all("solar" not in v["originalDamageSources"] for v in good["lunarCrew"].values()),
        "dungeonCargoAndCrewHome": trip["pass"] and trip["originalBossAndDroppedKey"] and trip["bothAliveAboard"] and trip["disconnectAndRecovery"] and trip["returnLoot"] == trip["originalReward"]["count"] and not trip["pendingPassage"],
        "distinctExactFares": trip["outboundAndHomeFares"] == [12000,12000] and trip["moonReceipt"] != trip["homeReceipt"],
        "matchedEndpointsPerRun": all(len(set(h.values())) == 1 for run in (bad,good) for h in run["threeEndpointHashes"].values()),
    }
    log = (protected / "server/console.log").read_text(encoding="utf-8",errors="replace")
    result["serviceSavedNormally"] = "All dimensions are saved" in log and not good["launcher"]["watchdog"] and "OutOfMemoryError" not in log and not list((protected / "server/crash-reports").glob("*.txt"))
    witness = protected / "server/arrival-witness.json"
    if witness.is_file():
        sample = read(witness)
        good["lastOutboundShipSample"] = sample
        good["rawEvidenceSha256"]["server/arrival-witness.json"] = sha(witness)
    good["serverBacklogsMsIncludingStartup"] = [int(n) for n in re.findall(r"Running (\d+)ms or \d+ ticks behind",log)]
    return {"date":"2026-11-22", "minecraft":"1.20.1", "forge":"47.4.28", "topology":"Dedicated server, two independently rendered non-op survival clients on loopback. Full original pack, including ExtraPlanets and GalaxySpace. Stock solar exposure configuration in both trials.", "provisions":"The fixture supplied the original assembly materials and energy, combat equipment and the engineer's combat potions. It also positioned the engineer in the naturally generated boss room, at the chest and back aboard. The captain had no combat potions. The protected test put actual pre-made original recipe outputs on both crew. Crafting either suit from ingredients in the rendered machine GUI was outside this scenario.", "checks": result, "pass": all(result.values()), "control": bad,"protected":good}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("control", type=Path)
    parser.add_argument("protected", type=Path)
    parser.add_argument("--deck-failure", type=Path, help="read the stopped partial-deck landing and its journals")
    parser.add_argument("--first-observation", type=Path, help="original mission that had no damage trace")
    parser.add_argument("--prior-protected", type=Path, help="an earlier complete test with the native suit")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit(args.control, args.protected)
    if args.deck_failure:
        from inspect_deck import inspect as inspect_deck
        result["partialDeckFailure"] = inspect_deck(args.deck_failure)
    if args.first_observation:
        instance = args.first_observation
        first = read(instance / "server/flight-result.json")
        result["initialObservation"] = {
            "run": instance.name, "failedPhase": first.get("failedStage"),
            "reportedFailure": first.get("failure"), "moonPosition": first.get("moonShipPosition"),
            "resultSha256": sha(instance / "server/flight-result.json"),
            "logSha256": sha(instance / "server/console.log"),
        }
    if args.prior_protected:
        previous = audit(args.control, args.prior_protected)
        result["priorProtectedRun"] = {
            "run": args.prior_protected.name, "pass": previous["pass"],
            "installed": previous["protected"]["threeEndpointHashes"],
            "reward": previous["protected"]["game"]["originalReward"],
            "lunarCrew": previous["protected"]["lunarCrew"],
            "resultSha256": sha(args.prior_protected / "server/flight-result.json"),
        }
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    for name, passed in result["checks"].items():
        print(name, passed)
    raise SystemExit(0 if result["pass"] else 1)
