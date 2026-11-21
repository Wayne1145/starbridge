"""Publish the independently checked integrated-client results and the final screenshots."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from audit import audit, ROOT


def publish(runs):
    images = ROOT / "docs/images/integrated-fleet"
    images.mkdir(parents=True, exist_ok=True)
    items = []
    for run in runs:
        result = audit(run)
        img = images / (str(result["length"]) + "-home.png")
        shutil.copyfile(run / "screenshots/returned-deck.png", img)
        result["postMeasurementScreenshot"] = {
            "file": img.relative_to(ROOT).as_posix(),
            "sha256": hashlib.sha256(img.read_bytes()).hexdigest(),
        }
        items.append(result)
    data = {
        "date": "2026-11-22",
        "game": "Minecraft 1.20.1, Forge 47.4.28, Java 17, Starbridge 0.3.0-dev",
        "existingEvidenceReviewed": [
            "docs/large-roundtrips-verification.json",
            "docs/native-fleet-verification.json",
            "docs/orbital-controls-verification.json",
            "docs/connected-fleet-verification.json",
        ],
        "priorRawFrames": "The existing dedicated and LAN reports contain server timings, GUI inputs and screenshots. A workspace search found no raw per-frame time series for these loaded native hulls.",
        "topology": "Separate fresh singleplayer saves, one full rendered client with its integrated server on an isolated Windows desktop, no LAN guest, survival, cheats disabled. Other tests shared the host, including the ongoing orbital soak. Only one integrated-flight JVM ran at once.",
        "preparation": "Each save reopens the genuine original Advanced Rocketry large-dock manifest for that length. QA supplies energy and oxygen equipment, teleports the host onto the loaded ship and records a prepared home berth. The pilot opens the bridge to choose the Moon, then uses the paired terminal on the lunar deck for return. Initial assembly, normal material collection and wide multi-deck ship geometries are outside these trips.",
        "measurement": "Wall-clock time from one Forge client RenderTickEvent.END to the next consecutive render-thread frame, including time waiting for work and the display. Each interval is assigned to its starting phase; both phase labels, both dimension keys and both GUI names are retained in the raw CSV. Nearest-rank percentiles. All intervals within the ship phases are included, with the totals reflecting frame counts per phase. The final screenshot was taken only after the raw stream was closed. Time is frame cadence, not isolated GPU execution time.",
        "interpretation": "Each length had a functional round trip with independently measured support at both endpoints. The phase results must be used to assess smoothness: few very slow return frames can consume more wall time than numerous faster outbound frames.",
        "observedReturnStutter": {
            "presentAtEveryMeasuredLength": all(
                x["stableThirdPersonView"]["return-space"]["p50ms"] > 500 for x in items
            ),
            "measurement": "在当前视角和测试阶段均未发生改变的连续实际驾驶帧中计算。三个尺寸的返航驾驶帧间隔中位数均超过半秒，到达后画面恢复。",
            "cause": "这些样本能定位到阶段，尚不能将原因归于某个模组、CPU、GPU 或驱动。",
            "setting": "840 × 480，RTX 3080，垂直同步关闭，其他试验同时使用这台主机。",
        },
        "runs": items,
        "verifiedLengths": [q["length"] for q in items],
        "allThreeMeasured": set(q["length"] for q in items) == {100, 200, 300},
        "instructions": "qa/integrated-fleet/README.md",
    }
    (ROOT / "docs/integrated-fleet-verification.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf8"
    )
    print(
        [
            (
                r["length"],
                r["renderIntervalsByPhase"]["outbound-space"]["p50ms"],
                r["renderIntervalsByPhase"]["return-space"]["p50ms"],
            )
            for r in items
        ]
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("runs", type=Path, nargs="+")
    publish(p.parse_args().runs)
