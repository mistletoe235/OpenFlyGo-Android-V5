#!/usr/bin/env python3
"""Compile research capture events into an executable OpenFly SurveyMission v11."""

from __future__ import annotations

import argparse
import json
import math
import time
import uuid
from pathlib import Path


METERS_PER_DEGREE_LATITUDE = 111_132.0


def horizontal_distance(a: dict, b: dict) -> float:
    mean_latitude = math.radians((a["latitude"] + b["latitude"]) / 2.0)
    north = (b["latitude"] - a["latitude"]) * METERS_PER_DEGREE_LATITUDE
    east = (b["longitude"] - a["longitude"]) * 111_320.0 * math.cos(mean_latitude)
    return math.hypot(north, east)


def world_to_wgs84(world_xyz: list[float], fit: dict) -> dict:
    if len(world_xyz) != 3:
        raise ValueError("active mapping target must contain world x/y/z")
    x, y, z = (float(value) for value in world_xyz)
    affine = fit["world_xy_to_east_north_affine"]
    east = affine[0][0] * x + affine[1][0] * y + affine[2][0]
    north = affine[0][1] * x + affine[1][1] * y + affine[2][1]
    latitude_origin = float(fit["latitude_origin"])
    longitude_origin = float(fit["longitude_origin"])
    altitude_scale, altitude_offset = fit["world_z_to_absolute_altitude_linear"]
    return {
        "latitude": latitude_origin + north / METERS_PER_DEGREE_LATITUDE,
        "longitude": longitude_origin + east / (
            111_320.0 * math.cos(math.radians(latitude_origin))
        ),
        "absolute_altitude_m": altitude_scale * z + altitude_offset,
    }


def path_distance(events: list[dict]) -> float:
    return sum(horizontal_distance(a, b) for a, b in zip(events, events[1:]))


def convex_hull(events: list[dict]) -> list[dict]:
    points = sorted({(event["longitude"], event["latitude"]) for event in events})
    if len(points) < 3:
        raise ValueError("active recapture task requires at least three distinct horizontal points")

    def cross(origin: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> float:
        return (a[0] - origin[0]) * (b[1] - origin[1]) - (a[1] - origin[1]) * (b[0] - origin[0])

    lower: list[tuple[float, float]] = []
    for point in points:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], point) <= 0:
            lower.pop()
        lower.append(point)
    upper: list[tuple[float, float]] = []
    for point in reversed(points):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], point) <= 0:
            upper.pop()
        upper.append(point)
    altitude = min(event["altitudeM"] for event in events)
    return [
        {"latitude": latitude, "longitude": longitude, "altitude_m": altitude}
        for longitude, latitude in lower[:-1] + upper[:-1]
    ]


def contiguous_runs(events: list[dict]) -> list[list[dict]]:
    runs: list[list[dict]] = []
    for event in events:
        key = (event["blockKind"], str(event["regionRank"]), event["passIndex"])
        if not runs or runs[-1][0]["_run_key"] != key:
            copied = dict(event)
            copied["_run_key"] = key
            runs.append([copied])
        else:
            copied = dict(event)
            copied["_run_key"] = key
            runs[-1].append(copied)
    return runs


def waypoint(event: dict, pass_index: int, kind: str, action: str, interval: float | None) -> dict:
    return {
        "point": {
            "latitude": event["latitude"],
            "longitude": event["longitude"],
            "altitude_m": event["altitudeM"],
        },
        "heading_deg": event["headingDeg"],
        "gimbal_pitch_deg": event["gimbalPitchDeg"],
        "kind": kind,
        "capture_action": action,
        "capture_interval_m": interval,
        "pass_index": pass_index,
        "capture_view": event["captureView"],
    }


def compile_units(events: list[dict]) -> tuple[list[dict], list[dict]]:
    waypoints: list[dict] = []
    pass_metadata: list[dict] = []
    next_pass_index = 0
    for run in contiguous_runs(events):
        for event in run:
            event.pop("_run_key", None)
        ordinary_point = (
            len(run) == 1
            and run[0]["blockKind"] == "ordinary_micro_sequence"
            and run[0]["captureRole"] == "SURVEY"
        )
        region_id = str(run[0]["regionRank"])
        if ordinary_point:
            waypoints.append(waypoint(
                run[0], next_pass_index, "CAPTURE_POINT", "CAPTURE_ON_REACH", None,
            ))
            role = "LOCAL_PRECISE_CAPTURE"
            capture_role = "SURVEY"
            source = "ACTIVE_MAPPING_RESEARCH"
            required_bridge = False
        else:
            if len(run) < 2:
                raise ValueError(f"continuous capture run {next_pass_index} contains fewer than two events")
            distance = path_distance(run)
            if distance <= 0.0:
                raise ValueError(f"continuous capture run {next_pass_index} has zero length")
            interval = distance / (len(run) - 1) * (1.0 + 1.0e-9)
            for index, event in enumerate(run):
                if index == 0:
                    kind, action, capture_interval = "PASS_START", "START_DISTANCE_INTERVAL", interval
                elif index == len(run) - 1:
                    kind, action, capture_interval = "PASS_END", "STOP_DISTANCE_INTERVAL", None
                else:
                    kind, action, capture_interval = "TRANSIT", "NONE", None
                waypoints.append(waypoint(
                    event, next_pass_index, kind, action, capture_interval,
                ))
            high_rise = run[0]["blockKind"] == "android_surveyplanner_highrise"
            role = "HIGH_RISE_SCAN" if high_rise else "RECONSTRUCTION_BRIDGE"
            capture_role = "MIXED" if high_rise else "BRIDGE"
            source = "ANDROID_SURVEY_PLANNER" if high_rise else "ACTIVE_MAPPING_COMPILER"
            required_bridge = any(event["captureRole"] == "BRIDGE" for event in run)
        pass_metadata.append({
            "pass_index": next_pass_index,
            "region_id": region_id,
            "role": role,
            "capture_role": capture_role,
            "source": source,
            "required_for_reconstruction_bridge": required_bridge,
        })
        next_pass_index += 1
    return waypoints, pass_metadata


def region_metadata(regions: dict, pass_metadata: list[dict], fit: dict) -> list[dict]:
    result = []
    for plan in regions.get("plans", []):
        rank = int(plan["rank"])
        high_rise = rank >= 8
        region_id = f"R{rank}" if high_rise else str(rank)
        pass_indices = [item["pass_index"] for item in pass_metadata if (
            item["region_id"] == "R8_R9" if high_rise else item["region_id"] == region_id
        )]
        result.append({
            "region_id": region_id,
            "priority": rank,
            "kind": "HIGH_RISE_AREA_SCAN" if high_rise else "LOCAL_PRECISE_RECAPTURE",
            "risk_score": plan["risk_score"],
            "reason": [
                "insufficient_high_oblique_coverage" if high_rise
                else "online_reconstruction_risk",
                "sfm_surface_support_deficit",
            ],
            "target_wgs84": world_to_wgs84(plan["target_surface_world_xyz_m"], fit),
            "pass_indices": pass_indices,
            "suggested_survey_photos": 40 if high_rise else plan["suggested_images"],
        })
    return result


def mission_path(waypoints: list[dict]) -> float:
    return sum(
        horizontal_distance(a["point"], b["point"])
        for a, b in zip(waypoints, waypoints[1:])
    )


def estimated_flight_seconds(waypoints: list[dict], speed: float) -> float:
    seconds = 0.0
    for a, b in zip(waypoints, waypoints[1:]):
        horizontal = horizontal_distance(a["point"], b["point"])
        vertical = abs(b["point"]["altitude_m"] - a["point"]["altitude_m"])
        seconds += max(horizontal / speed, vertical / 0.5)
    return seconds


def compile_mission(source: dict, regions: dict, fit: dict, name: str, speed: float) -> dict:
    events = source["captureEvents"]
    if len(events) != source["surveyCaptureCount"] + source["bridgeCaptureCount"]:
        raise ValueError("source capture counts do not match captureEvents")
    if any(event["captureView"] not in {
        "NADIR", "FORWARD_OBLIQUE", "BACKWARD_OBLIQUE",
        "LEFT_OBLIQUE", "RIGHT_OBLIQUE", "LOCAL_OBLIQUE",
    } for event in events):
        raise ValueError("source contains an unsupported capture view")
    waypoints, passes = compile_units(events)
    path_meters = mission_path(waypoints)
    minimum_interval = min(
        waypoint["capture_interval_m"] for waypoint in waypoints
        if waypoint["capture_interval_m"] is not None
    )
    if speed * 2.0 > minimum_interval + 1.0e-6:
        raise ValueError(
            f"speed {speed:.2f} m/s exceeds the 2 s camera cadence limit for "
            f"minimum interval {minimum_interval:.2f} m"
        )
    return {
        "schema_version": 11,
        "id": str(uuid.uuid4()),
        "name": name,
        "created_at_epoch_ms": int(time.time() * 1000),
        "coordinate_frame": "WGS84",
        "camera_profile": {
            "id": "dji-mini-2-photo-4x3",
            "image_width_px": 4000,
            "image_height_px": 3000,
            "horizontal_fov_deg": 73.7,
            "vertical_fov_deg": 53.1,
            "minimum_capture_interval_s": 2.0,
        },
        "constraints": {
            "altitude_agl_m": 78.0,
            "forward_overlap": 0.70,
            "side_overlap": 0.70,
            "speed_mps": speed,
            "gimbal_pitch_deg": -90.0,
            "route_heading_deg": 60.0,
            "crosshatch": False,
            "collection_mode": "OBLIQUE_FIVE_DIRECTION",
            "oblique_gimbal_pitch_deg": -45.0,
            "boundary_margin_m": 0.0,
            "altitude_mode": "RELATIVE_TO_TAKEOFF",
            "target_surface_to_takeoff_m": 0.0,
            "safe_takeoff_altitude_m": max(event["altitudeM"] for event in events),
            "takeoff_speed_mps": 3.0,
            "takeoff_mode": "MANUAL",
            "start_point_mode": "AUTO_NEAREST",
            "completion_action": "RETURN_TO_HOME",
            "capture_trigger_mode": "DISTANCE",
            "timed_capture_interval_s": 2.0,
            "oblique_forward_overlap": 0.60,
            "oblique_side_overlap": 0.60,
            "oblique_heading_mode": "FIXED_CAPTURE_DIRECTION",
            "enabled_capture_views": [
                "NADIR", "FORWARD_OBLIQUE", "BACKWARD_OBLIQUE",
                "LEFT_OBLIQUE", "RIGHT_OBLIQUE", "LOCAL_OBLIQUE",
            ],
        },
        "roi": convex_hull(events),
        "waypoints": waypoints,
        "estimated_path_m": path_meters,
        "estimated_photo_count": len(events),
        "estimated_flight_s": estimated_flight_seconds(waypoints, speed),
        "terrain_plan": None,
        "active_mapping": {
            "schema_version": 1,
            "selection_method": source["method"],
            "ground_truth_used": source["groundTruthUsed"],
            "gs_used_for_selection": source["gsUsedForSelection"],
            "ordinary_gps_used": source["ordinaryGpsUsed"],
            "source_capture_count": len(events),
            "survey_capture_count": source["surveyCaptureCount"],
            "bridge_capture_count": source["bridgeCaptureCount"],
            "source_estimated_route_distance_m": source["estimatedRouteDistanceM"],
            "regions": region_metadata(regions, passes, fit),
            "passes": passes,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("regions", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--wgs84-fit", type=Path)
    parser.add_argument("--name", default="two_buildings 主动补拍")
    parser.add_argument("--speed-mps", type=float, default=2.8)
    args = parser.parse_args()
    if args.speed_mps <= 0.0:
        raise ValueError("speed must be positive")
    source = json.loads(args.source.read_text())
    regions = json.loads(args.regions.read_text())
    fit_path = args.wgs84_fit or args.regions.with_name("wgs84_fit_report.json")
    if not fit_path.is_file():
        raise FileNotFoundError(
            f"WGS84 fit report not found: {fit_path}; pass --wgs84-fit explicitly"
        )
    fit = json.loads(fit_path.read_text())
    mission = compile_mission(source, regions, fit, args.name, args.speed_mps)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(mission, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({
        "output": str(args.output),
        "waypoints": len(mission["waypoints"]),
        "passes": len(mission["active_mapping"]["passes"]),
        "photos": mission["estimated_photo_count"],
        "path_m": round(mission["estimated_path_m"], 3),
        "flight_s": round(mission["estimated_flight_s"], 3),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
