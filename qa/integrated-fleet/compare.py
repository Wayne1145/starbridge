"""Archive render-thread samples and compare audited trips with the frozen pre-fix measurements."""

import argparse
import csv
import json
import shutil
from collections import Counter
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from audit import HERE, ROOT, audit, check, digest, read


def evidence(archive):
    return {"file": archive.relative_to(ROOT).as_posix(), "sha256": digest(archive)}


def raw_archive(run, names, issue=None):
    archive = HERE / "evidence" / (run.name + ".zip")
    with ZipFile(archive, "w", ZIP_DEFLATED, compresslevel=8) as out:
        for n in names:
            if (run / n).is_file():
                out.write(run / n, n)
    return {
        "run": run.name,
        **({"result": issue} if issue else {}),
        **evidence(archive),
    }


def stacks(run):
    groups = {}
    with (run / "profile-stacks.tsv").open(encoding="utf8") as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            if row["phase"] not in ("outbound-space", "return-space"):
                continue
            key = (row["phase"], row["thread"])
            g = groups.setdefault(
                key,
                {
                    "samples": 0,
                    "startNs": int(row["ns"]),
                    "lastNs": 0,
                    "stacks": Counter(),
                    "states": Counter(),
                },
            )
            g["samples"] += 1
            g["lastNs"] = int(row["ns"])
            g["states"][row["state"]] += 1
            for token in (
                "retrofit.forge.world.LegacyBlockEntity.tick:",
                "retrofit.forge.world.LegacyBridge.createTileEntity:",
                "dev.starbridge.client.OrbitScreen.",
            ):
                if token in row["stack"]:
                    g["stacks"][token] += 1
    grouped = {
        f"{phase}/{thread}": {
            "samples": v["samples"],
            "secondsBetweenFirstAndLast": round((v["lastNs"] - v["startNs"]) / 1e9, 3),
            "stackContains": dict(v["stacks"]),
            "threadStates": dict(v["states"]),
        }
        for (phase, thread), v in groups.items()
    }
    counters = {
        k: v
        for k, v in read(run / "profile-counters.json").items()
        if k.startswith("return-space:") and ">" not in k
    }
    archive = raw_archive(
        run,
        [
            "profile-stacks.tsv",
            "profile-counters.json",
            "frames.csv",
            "graphics.json",
            "launch-result.json",
            "pilot-result.json",
            "input.csv",
            "integrated-server.json",
        ],
    )
    return {
        "method": "100-block baseline, sampled render and integrated-server thread stacks nominally every 19 ms. Independent counters time OrbitScreen draws and wire serialization. Sampling is observational; sample fractions are not exact CPU percentages.",
        "groups": grouped,
        "returnCounters": counters,
        "stackFileSha256": digest(run / "profile-stacks.tsv"),
        "evidence": archive,
    }


def failure(run, reason):
    names = [
        "launch-result.json",
        "integrated-failure.json",
        "handoff-threads.txt",
        "handoff-heap.txt",
        "handoff-ray.jsonl",
        "handoff-ticks.jsonl",
        "input.csv",
        "frames.csv",
        "logs/latest.log",
        "visitor/logs/latest.log",
    ]
    names += ["visitor/guest-worlds.txt", "lan-connection.json"]
    for base in ("saves", "crash-reports"):
        for f in sorted((run / base).rglob("*")):
            if f.is_file() and (
                f.parent.name == "playerdata"
                and f.suffix == ".dat"
                or f.name == "starbridge_passages.dat"
                or base == "crash-reports"
                and f.suffix == ".txt"
            ):
                names.append(f.relative_to(run).as_posix())
    result = raw_archive(run, names, reason)
    trace = run / "handoff-ray.jsonl"
    if trace.is_file():
        ray = json.loads(trace.read_text(encoding="utf8").splitlines()[0])
        result["firstFallRay"] = {
            key: ray[key]
            for key in (
                "tick",
                "player",
                "world",
                "from",
                "to",
                "length",
                "delta",
                "fallDistance",
                "getLastShipStoodOn",
                "getAddedMovementLastTick",
            )
        }
    return result


def audit_publish(run):
    item = audit(run)
    (run / "audit.json").write_text(json.dumps(item, indent=2) + "\n", encoding="utf8")
    photo = ROOT / "docs/images/integrated-fleet" / (run.name + "-home.png")
    shutil.copy2(run / "screenshots/returned-deck.png", photo)
    item["postMeasurementScreenshot"] = evidence(photo)
    return item


def publish(args):
    report = ROOT / "docs/integrated-fleet-verification.json"
    data = read(report)
    baseline = {r["length"]: r for r in data["runs"]}
    for r in baseline.values():
        check(
            digest(ROOT / r["evidence"]["file"]) == r["evidence"]["sha256"],
            "Frozen baseline archive differs",
        )
    measured = [audit_publish(p.resolve()) for p in args.solo]
    observed_lan = audit_publish(args.lan.resolve()) if args.lan else None
    controls = {
        probe: {
            **read(directory / "validation.json"),
            "evidence": evidence(HERE / "evidence" / (directory.name + ".zip")),
        }
        for probe, directory in [
            ("dungeon", args.dungeon),
            ("withoutRetrofit", args.without),
        ]
    }
    profile = stacks(args.profile.resolve())
    changed = []
    for item in measured:
        n = item["length"]
        old = baseline[n]["stableThirdPersonView"]["return-space"]
        after = item["stableThirdPersonView"]["return-space"]
        check(
            item["sourceManifestSha256"] == baseline[n]["sourceManifestSha256"],
            "Different source ship",
        )
        changed.append(
            {
                "length": n,
                "run": item["run"],
                "before": old,
                "after": after,
                "medianRatio": round(old["p50ms"] / after["p50ms"], 2),
            }
        )
    attempts = [failure(Path(p), reason) for p, reason in args.failure]
    data["returnPerformanceFix"] = {
        "game": "Forge 1.20.1/47.4.28, Java 17, Starbridge 0.3.0-dev",
        "date": "2026-11-22",
        "originals": "AR + LibVulpes + Galacticraft Core and the original shipyard bridge. These frame benchmarks did not install ExtraPlanets or GalaxySpace.",
        "profile": profile,
        "change": "Only the locked Retrofit 0.6.2-beta Galacticraft moonblock ID uses its original hasTileEntity(metadata) to mark states without an original tile as non-ticking. Unrelated blocks and states with a tile use the normal library path.",
        "baselineMethod": "Current ForgeGradle reobf production mixin and config on the pre-fix ship benchmark JAR. Separate fresh survival saves with equal manifests, display settings, machine inventories and flight inputs. JAR digests per run. At least one other soak or test used this PC.",
        "framesByLength": changed,
        "singleplayer": measured,
        "lan": observed_lan,
        "compatibility": controls,
        "otherAttempts": attempts,
        "coverage": "Prepared cargo trips use real rendered clients on the test PC and GUI-driven pilot actions. The LAN visitor stays aboard. The measurement covers the stated narrow hulls and the tested hardware. Dungeon observation checks the ticking original skeleton-boss spawner with no player in the room.",
    }
    if observed_lan:
        portal_runs = [audit_publish(path.resolve()) for path in args.portal_solo]
        logs = {}
        for path in [args.lan] + args.portal_solo:
            text = (
                (path / "logs/latest.log")
                .read_text(encoding="utf8", errors="replace")
                .splitlines()
            )
            logs[path.name] = [line for line in text if "Native portal " in line]
        data["returnPerformanceFix"]["arrivalPhysics"] = {
            "scope": "Separate physical handoff failure, observed with only the lunar-ticker fix. The GUI pilot could enter the destination while the pending passenger was still falling. At 300 blocks, one disk save showed zero health on terrain at the correct X/Z, roughly 149 blocks below the deck.",
            "capture": "100-block LAN, two full clients. The captain had 21.8335 blocks of accumulated fall distance with a server motion of (0,0,19931.4834054). The recorded VS last-frame ship movement was roughly (-19760,-27.77,-19930). The fixture stopped before traversing the ray. See run-100-lanfix3.",
            "experiment": "At a Starbridge native dimensional transfer, advance the destination native ShipData history to the newly installed transform before transferring riders. VS 2.4.11 exposes updatePrevTickShipTransform(), which copies the current transform into the previous-tick field used by EntityDragger. Vehicle physics and ordinary fall damage remain active in the experiment.",
            "clientTest": "100-block LAN trip on the separately identified prototype candidate. Both clients visited the original Moon and home, with independent standing and save checks.",
            "sourceEvidence": evidence(HERE / "evidence/portal-transform-source.zip"),
            "candidateSha256": observed_lan["installed"][
                "starbridge-1.20.1-0.3.0-dev.jar"
            ],
            "frameSettings": "Presupplied 7 脳 6 cargo hulls, 840 脳 480, RTX 3080, render distance 6. The LAN visitor uses its own rendered client and shares the same GPU.",
            "presyncNativeTransforms": logs,
            "solo": portal_runs,
        }
    if args.release_solo:
        release_solo = [audit_publish(p.resolve()) for p in args.release_solo]
        release_lan = audit_publish(args.release_lan.resolve())
        check(
            {r["length"] for r in release_solo} == {100, 200, 300}
            and len(release_solo) == 3
            and release_lan["population"] == 2,
            "incomplete production profile",
        )
        name = "starbridge-1.20.1-0.3.0-dev.jar"
        sha = release_lan["installed"][name]
        build_archive = HERE / "evidence" / ("production-build-" + sha[:8] + ".zip")
        with ZipFile(build_archive) as captured:
            build = json.loads(captured.read("build.json"))
        check(
            build["productionSha256"] == sha
            and build["tests"] > 0
            and build["failures"] == build["errors"] == 0,
            "missing successful build receipt for this JAR",
        )
        probes = {}
        for mode, directory in [
            ("withoutRetrofit", args.release_without),
            ("dungeon", args.release_dungeon),
        ]:
            raw = read(directory / "validation.json")
            check(
                raw["pass"] and raw["installed"][name] == sha,
                "different production probe build",
            )
            probes[mode] = {
                **raw,
                "evidence": evidence(HERE / "evidence" / (directory.name + ".zip")),
            }
        check(
            all(
                r["population"] == 1
                and r["installed"][name] == sha
                and r["sourceManifestSha256"]
                == baseline[r["length"]]["sourceManifestSha256"]
                for r in release_solo
            ),
            "different production ship or JAR",
        )
        data["returnPerformanceFix"]["verifiedProduction"] = {
            "sha256": sha,
            "build": {**build, "evidence": evidence(build_archive)},
            "sourceChanges": [
                "src/main/java/dev/starbridge/mixin/OriginalMoonTickerMixin.java",
                "src/main/java/dev/starbridge/mixin/OriginalWorldMixinPlugin.java",
                "src/main/resources/starbridge.retrofit.mixins.json",
                "src/main/java/dev/starbridge/compat/vs/VsShipBackend.java",
            ],
            "changes": "Only moonblock metadata that the original Galacticraft declares tileless bypasses unnecessary Retrofit 0.6.2-beta ticking. Only Starbridge dimensional transfers of VS 2.4.11 ships update the native previous-tick transform immediately after its portal call and before rider delivery.",
            "conditions": "Separate saves on one machine. The 300/200 sequence and 100 LAN/solo sequence ran in parallel during parts of these trials, in addition to other background tests. FPS and rare stalls depend on host load. The 100/200/300 hulls are identically 7 wide and 6 tall, measured with the original Advanced Rocketry construction manifest, Galacticraft Moon and pre-provisioned cargo and equipment.",
            "baselineComparedToRelease": [
                {
                    "length": r["length"],
                    "old": baseline[r["length"]]["stableThirdPersonView"][
                        "return-space"
                    ],
                    "release": r["stableThirdPersonView"]["return-space"],
                }
                for r in release_solo
            ],
            "solo": release_solo,
            "lan": release_lan,
            "compatibility": probes,
        }
        if args.repeat:
            repeat = audit_publish(args.repeat.resolve())
            check(
                repeat["population"] == 1
                and repeat["installed"][name] == sha
                and repeat["sourceManifestSha256"]
                == baseline[repeat["length"]]["sourceManifestSha256"],
                "different follow-up workload or production JAR",
            )
            data["returnPerformanceFix"]["verifiedProduction"]["singleplayerRepeat"] = {
                "conditions": "One separate 100-block run after both integrated-flight test queues exited; other tasks on the same PC were not stopped. Same JAR, GPU options, source manifest and GUI inputs. No stack sampler.",
                "run": repeat,
            }
    report.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf8"
    )
    print([(r["length"], r["after"]["p50ms"], r["after"]["p95ms"]) for r in changed])


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--solo", type=Path, nargs="+", required=True)
    p.add_argument("--lan", type=Path)
    p.add_argument("--portal-solo", type=Path, nargs="*", default=[])
    p.add_argument("--release-solo", type=Path, nargs="*", default=[])
    p.add_argument("--release-lan", type=Path)
    p.add_argument("--repeat", type=Path)
    p.add_argument("--release-without", type=Path)
    p.add_argument("--release-dungeon", type=Path)
    p.add_argument("--profile", type=Path, required=True)
    p.add_argument("--dungeon", type=Path, required=True)
    p.add_argument("--without", type=Path, required=True)
    p.add_argument("--failure", nargs=2, action="append", default=[])
    publish(p.parse_args())
