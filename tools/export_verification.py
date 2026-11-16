"""Export measured runtime results without player identifiers or generated ship names."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
fields = ("passed", "detail", "requestedLength", "requestedMachines", "actualBlocks",
          "actualBlockEntities", "assemblyMillis", "generationMillis", "arrivalDistance",
          "arrivalSpeed", "arrivalDrift", "arrivalStableTicks", "energyConsumed",
          "totalExternalFe", "externalPowerPerTickLimit", "tickTimings", "runtimeEnvironment",
          "observerPresent", "noPlayerMode")
results = []
for name in ("run-smoke-server", "run-smoke-server-2", "run-smoke-server-player",
             "run-smoke-arrival", "run-smoke-100", "run-smoke-300",
             "run-smoke-300-guarded", "run-smoke-300-lifecycle", "run-smoke-100-final"):
    file = root / name / "starbridge-smoke-result.json"
    if file.exists():
        data = json.loads(file.read_text(encoding="utf-8-sig"))
        results.append({"source": str(file.relative_to(root)).replace("\\", "/"),
                        **{key: data[key] for key in fields if key in data}})
suites = []
for path in sorted((root / "build/test-results/test").glob("TEST-*.xml")):
    suite = ET.parse(path).getroot()
    suites.append({"name": suite.get("name"), "tests": int(suite.get("tests", 0)),
                   "failures": int(suite.get("failures", 0)), "errors": int(suite.get("errors", 0))})
output = {"environment": {"minecraft": "1.20.1", "forge": "47.4.10", "vs": "2.4.11",
                          "kff": "4.11.0", "cc_tweaked": "1.116.2"},
          "unit_suites": suites, "native_runs": results,
          "scope": "Short isolated tests; not certification of 10–30 players, offline cargo, or long-run stability."}
target = root / "docs/verification-results.json"
target.write_text(json.dumps(output, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"Exported {len(suites)} unit suites and {len(results)} real runtime results.")
