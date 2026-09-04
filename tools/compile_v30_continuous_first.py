#!/usr/bin/env python3
"""Compile a V30 variant that flies continuous blocks before scattered scans."""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

from compile_v30_active_recapture_to_survey_missions import compile_all


TASK_ORDER = [
    16, 10, 12,
    13, 14, 8, 9, 11, 7, 6, 18, 15, 17,
    19, 2, 3, 4, 5, 20, 21, 1, 0, 22,
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--wgs84-fit", type=Path, required=True)
    parser.add_argument("--speed-mps", type=float, default=2.8)
    args = parser.parse_args()

    original_bytes = args.source.read_bytes()
    original = json.loads(original_bytes)
    tasks = original["continuous_scan_tasks"]
    if sorted(TASK_ORDER) != list(range(len(tasks))):
        raise ValueError("continuous-first task order must contain every source task exactly once")

    reordered = dict(original)
    reordered["continuous_scan_tasks"] = [tasks[index] for index in TASK_ORDER]
    reordered_bytes = (json.dumps(reordered, ensure_ascii=False, indent=2) + "\n").encode()
    fit = json.loads(args.wgs84_fit.read_text())
    reordered_hash = hashlib.sha256(reordered_bytes).hexdigest()
    missions, validation = compile_all(
        reordered,
        fit,
        reordered_hash,
        args.speed_mps,
        None,
        None,
        int(time.time() * 1000),
    )
    if len(missions) != 1:
        raise ValueError(f"expected one mission, got {len(missions)}")

    mission = missions[0]
    mission["name"] = "two_buildings V30 补缺航线（连续大块优先）"
    output_name = "openfly-active-recapture-two-buildings-v30-continuous-first.json"
    manifest_name = "openfly-active-recapture-two-buildings-v30-continuous-first-manifest.json"
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output_path = args.output_dir / output_name
    output_path.write_text(json.dumps(mission, ensure_ascii=False, indent=2) + "\n")

    manifest = {
        "schema_version": 1,
        "variant": "CONTINUOUS_BLOCKS_THEN_SMALL_CROSS_THEN_SCATTERED",
        "source": str(args.source),
        "source_sha256": hashlib.sha256(original_bytes).hexdigest(),
        "reordered_source_sha256": reordered_hash,
        "task_order": TASK_ORDER,
        "wgs84_fit": str(args.wgs84_fit),
        "speed_mps": args.speed_mps,
        "validation": validation,
        "sorties": [{
            "sortie": 1,
            "file": output_name,
            "photos": mission["estimated_photo_count"],
            "waypoints": len(mission["waypoints"]),
            "path_m": round(mission["estimated_path_m"], 3),
            "flight_s": round(mission["estimated_flight_s"], 3),
            "altitude_min_m": min(item["point"]["altitude_m"] for item in mission["waypoints"]),
            "altitude_max_m": max(item["point"]["altitude_m"] for item in mission["waypoints"]),
        }],
    }
    (args.output_dir / manifest_name).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
