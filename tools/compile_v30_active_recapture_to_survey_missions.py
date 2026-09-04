#!/usr/bin/env python3
"""Compile the V30 research route into executable SurveyMission v11 sorties."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import time
import uuid
from dataclasses import dataclass
from pathlib import Path


METERS_PER_DEGREE_LATITUDE = 111_132.0
SUPPORTED_CAPTURE_VIEWS = {
    "NADIR",
    "FORWARD_OBLIQUE",
    "BACKWARD_OBLIQUE",
    "LEFT_OBLIQUE",
    "RIGHT_OBLIQUE",
    "LOCAL_OBLIQUE",
}


@dataclass(frozen=True)
class Strip:
    task_index: int
    strip_index: int
    task_class: str
    capture_view: str
    region_id: str
    source: dict
    poses: tuple[dict, ...]

    @property
    def photo_count(self) -> int:
        return len(self.poses)


def angle_delta(start: float, end: float) -> float:
    return (end - start + 540.0) % 360.0 - 180.0


def horizontal_distance(a: dict, b: dict) -> float:
    mean_latitude = math.radians((a["latitude"] + b["latitude"]) / 2.0)
    north = (b["latitude"] - a["latitude"]) * METERS_PER_DEGREE_LATITUDE
    east = (b["longitude"] - a["longitude"]) * 111_320.0 * math.cos(mean_latitude)
    return math.hypot(north, east)


def world_to_wgs84(world_xyz: list[float], fit: dict, altitude_m: float) -> dict:
    if len(world_xyz) != 3:
        raise ValueError("V30 pose world_xyz_m must contain x/y/z")
    x, y = (float(value) for value in world_xyz[:2])
    affine = fit["world_xy_to_east_north_affine"]
    east = affine[0][0] * x + affine[1][0] * y + affine[2][0]
    north = affine[0][1] * x + affine[1][1] * y + affine[2][1]
    latitude_origin = float(fit["latitude_origin"])
    longitude_origin = float(fit["longitude_origin"])
    return {
        "latitude": latitude_origin + north / METERS_PER_DEGREE_LATITUDE,
        "longitude": longitude_origin + east / (
            111_320.0 * math.cos(math.radians(latitude_origin))
        ),
        "altitude_m": float(altitude_m),
    }


def absolute_target(world_xyz: list[float], fit: dict) -> dict:
    point = world_to_wgs84(world_xyz, fit, 0.0)
    altitude_scale, altitude_offset = fit["world_z_to_absolute_altitude_linear"]
    point["absolute_altitude_m"] = altitude_scale * float(world_xyz[2]) + altitude_offset
    point.pop("altitude_m")
    return point


def pose_to_waypoint(pose: dict, fit: dict, capture_view: str) -> dict:
    altitude = float(pose["relative_altitude_m"])
    if pose.get("altitude_frame") != "DJI_RELATIVE_TO_TAKEOFF":
        raise ValueError("V30 pose is not relative to the DJI takeoff point")
    return {
        "point": world_to_wgs84(pose["world_xyz_m"], fit, altitude),
        "heading_deg": float(pose["aircraft_yaw_deg"]) % 360.0,
        "gimbal_pitch_deg": float(pose["gimbal_pitch_deg"]),
        "kind": "TRANSIT",
        "capture_action": "NONE",
        "capture_interval_m": None,
        "pass_index": -1,
        "capture_view": capture_view,
    }


def interpolate_waypoint(start: dict, end: dict, ratio: float) -> dict:
    start_point = start["point"]
    end_point = end["point"]
    return {
        "point": {
            "latitude": start_point["latitude"] +
                (end_point["latitude"] - start_point["latitude"]) * ratio,
            "longitude": start_point["longitude"] +
                (end_point["longitude"] - start_point["longitude"]) * ratio,
            "altitude_m": start_point["altitude_m"] +
                (end_point["altitude_m"] - start_point["altitude_m"]) * ratio,
        },
        "heading_deg": (start["heading_deg"] +
            angle_delta(start["heading_deg"], end["heading_deg"]) * ratio) % 360.0,
        "gimbal_pitch_deg": start["gimbal_pitch_deg"] +
            (end["gimbal_pitch_deg"] - start["gimbal_pitch_deg"]) * ratio,
        "kind": "TRANSIT",
        "capture_action": "NONE",
        "capture_interval_m": None,
        "pass_index": -1,
        "capture_view": end["capture_view"],
    }


def densify(
    waypoints: list[dict],
    max_distance_m: float = 25.0,
    max_yaw_deg: float = 45.0,
    max_pitch_deg: float = 15.0,
    max_vertical_m: float = 5.0,
) -> list[dict]:
    if len(waypoints) < 2:
        return [dict(waypoint) for waypoint in waypoints]
    result = [dict(waypoints[0])]
    for start, end in zip(waypoints, waypoints[1:]):
        steps = max(
            1,
            math.ceil(horizontal_distance(start["point"], end["point"]) / max_distance_m),
            math.ceil(abs(angle_delta(start["heading_deg"], end["heading_deg"])) / max_yaw_deg),
            math.ceil(abs(end["gimbal_pitch_deg"] - start["gimbal_pitch_deg"]) / max_pitch_deg),
            math.ceil(abs(end["point"]["altitude_m"] - start["point"]["altitude_m"]) / max_vertical_m),
        )
        result.extend(interpolate_waypoint(start, end, step / steps) for step in range(1, steps + 1))
    return result


def flatten_strips(source: dict) -> list[Strip]:
    if source.get("version") != "v30_takeoff_relative_altitude_corrected":
        raise ValueError("input is not the reviewed V30 relative-height route")
    if source.get("altitude_frame") != "DJI_RELATIVE_TO_TAKEOFF":
        raise ValueError("V30 route altitude frame is not DJI_RELATIVE_TO_TAKEOFF")
    if source.get("horizontal_frame") != "WORLD_X_EAST_Y_NORTH":
        raise ValueError("V30 route must declare WORLD_X_EAST_Y_NORTH")
    if source.get("heading_frame") != "DJI_NORTH_CLOCKWISE":
        raise ValueError("V30 route must declare DJI_NORTH_CLOCKWISE headings")
    strips: list[Strip] = []
    for task_index, task in enumerate(source["continuous_scan_tasks"]):
        if not isinstance(task, list) or not task:
            raise ValueError(f"task {task_index} must contain at least one strip")
        for strip_index, raw_strip in enumerate(task):
            poses = tuple(raw_strip.get("poses", []))
            if not poses:
                raise ValueError(f"task {task_index} strip {strip_index} has no poses")
            for pose_index, pose in enumerate(poses):
                camera = pose["world_xyz_m"]
                target = pose["look_at_world_xyz_m"]
                east = float(target[0]) - float(camera[0])
                north = float(target[1]) - float(camera[1])
                if math.hypot(east, north) > 1.0e-6:
                    target_heading = math.degrees(math.atan2(east, north)) % 360.0
                    error = abs(angle_delta(float(pose["aircraft_yaw_deg"]), target_heading))
                    if error > 1.0e-6:
                        raise ValueError(
                            f"task {task_index} strip {strip_index} pose {pose_index} "
                            f"heading differs from target bearing by {error:.3f} degrees"
                        )
            task_class = str(raw_strip["task_class"])
            capture_view = raw_strip.get("capture_view")
            if capture_view not in SUPPORTED_CAPTURE_VIEWS:
                capture_view = "LOCAL_OBLIQUE"
            region_value = raw_strip.get("region_id")
            if region_value is None:
                region_value = raw_strip.get("parent_v22_region_id")
            if region_value is None:
                region_value = raw_strip.get("subregion_id")
            region_id = f"V30_{task_class}_{region_value if region_value is not None else task_index}"
            strips.append(Strip(
                task_index=task_index,
                strip_index=strip_index,
                task_class=task_class,
                capture_view=capture_view,
                region_id=region_id,
                source=raw_strip,
                poses=poses,
            ))
    return strips


def strip_capture_distance(strip: Strip, fit: dict) -> float:
    points = [pose_to_waypoint(pose, fit, strip.capture_view)["point"] for pose in strip.poses]
    return sum(horizontal_distance(a, b) for a, b in zip(points, points[1:]))


def transition_distance(previous: Strip, current: Strip, fit: dict) -> float:
    previous_point = pose_to_waypoint(previous.poses[-1], fit, previous.capture_view)["point"]
    current_point = pose_to_waypoint(current.poses[0], fit, current.capture_view)["point"]
    return horizontal_distance(previous_point, current_point)


def split_sorties(
    strips: list[Strip],
    fit: dict,
    max_distance_m: float | None,
    max_photos: int | None,
) -> list[list[Strip]]:
    if max_distance_m is None and max_photos is None:
        return [strips]

    def exceeds_limits(items: list[Strip]) -> bool:
        return (
            max_distance_m is not None and route_distance(items) > max_distance_m
        ) or (
            max_photos is not None and photo_count(items) > max_photos
        )

    groups: list[list[Strip]] = []
    for strip in strips:
        if not groups or groups[-1][0].task_index != strip.task_index:
            groups.append([strip])
        else:
            groups[-1].append(strip)

    def route_distance(items: list[Strip]) -> float:
        return sum(strip_capture_distance(item, fit) for item in items) + sum(
            transition_distance(a, b, fit) for a, b in zip(items, items[1:])
        )

    def photo_count(items: list[Strip]) -> int:
        return sum(item.photo_count for item in items)

    chunks: list[list[Strip]] = []
    for group in groups:
        if not exceeds_limits(group):
            chunks.append(group)
            continue
        current: list[Strip] = []
        for strip in group:
            candidate = current + [strip]
            if current and exceeds_limits(candidate):
                chunks.append(current)
                current = []
            current.append(strip)
        if current:
            chunks.append(current)

    sorties: list[list[Strip]] = []
    current = []
    for chunk in chunks:
        candidate = current + chunk
        if current and exceeds_limits(candidate):
            sorties.append(current)
            current = []
        current.extend(chunk)
    if current:
        sorties.append(current)
    return sorties


def convex_hull(waypoints: list[dict]) -> list[dict]:
    points = sorted({
        (waypoint["point"]["longitude"], waypoint["point"]["latitude"])
        for waypoint in waypoints
    })
    if len(points) < 3:
        longitude, latitude = points[0]
        delta = 1.0 / METERS_PER_DEGREE_LATITUDE
        points = [(longitude - delta, latitude - delta), (longitude + delta, latitude - delta), (longitude, latitude + delta)]

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
    altitude = min(waypoint["point"]["altitude_m"] for waypoint in waypoints)
    return [
        {"latitude": latitude, "longitude": longitude, "altitude_m": altitude}
        for longitude, latitude in lower[:-1] + upper[:-1]
    ]


def make_capture_unit(strip: Strip, fit: dict, pass_index: int) -> tuple[list[dict], dict]:
    source_waypoints = [pose_to_waypoint(pose, fit, strip.capture_view) for pose in strip.poses]
    if len(source_waypoints) == 1:
        waypoint = source_waypoints[0]
        waypoint.update({
            "kind": "CAPTURE_POINT",
            "capture_action": "CAPTURE_ON_REACH",
            "pass_index": pass_index,
        })
        waypoints = [waypoint]
        role = "LOCAL_PRECISE_CAPTURE"
    else:
        waypoints = densify(source_waypoints)
        distance = sum(
            horizontal_distance(a["point"], b["point"])
            for a, b in zip(waypoints, waypoints[1:])
        )
        interval = distance / (len(strip.poses) - 1) * (1.0 + 1.0e-9)
        for index, waypoint in enumerate(waypoints):
            waypoint["pass_index"] = pass_index
            if index == 0:
                waypoint.update({
                    "kind": "PASS_START",
                    "capture_action": "START_DISTANCE_INTERVAL",
                    "capture_interval_m": interval,
                })
            elif index == len(waypoints) - 1:
                waypoint.update({"kind": "PASS_END", "capture_action": "STOP_DISTANCE_INTERVAL"})
        role = strip.task_class
    return waypoints, {
        "pass_index": pass_index,
        "region_id": strip.region_id,
        "role": role,
        "capture_role": "SURVEY",
        "source": "V30_RELATIVE_HEIGHT_ROUTE",
        "required_for_reconstruction_bridge": False,
    }


def make_transit_unit(previous: Strip, current: Strip, fit: dict, pass_index: int) -> tuple[list[dict], dict]:
    start = pose_to_waypoint(previous.poses[-1], fit, previous.capture_view)
    end = pose_to_waypoint(current.poses[0], fit, current.capture_view)
    waypoints = densify([start, end])
    for waypoint in waypoints:
        waypoint.update({
            "kind": "TRANSIT",
            "capture_action": "NONE",
            "capture_interval_m": None,
            "pass_index": pass_index,
        })
    return waypoints, {
        "pass_index": pass_index,
        "region_id": f"TRANSIT_{previous.task_index}_{current.task_index}",
        "role": "SAFE_TRANSIT",
        "capture_role": "NONE",
        "source": "V30_ANDROID_COMPILER",
        "required_for_reconstruction_bridge": False,
    }


def mission_path(waypoints: list[dict]) -> float:
    return sum(
        horizontal_distance(a["point"], b["point"])
        for a, b in zip(waypoints, waypoints[1:])
    )


def estimated_flight_seconds(waypoints: list[dict], speed: float) -> float:
    seconds = 0.0
    for start, end in zip(waypoints, waypoints[1:]):
        horizontal = horizontal_distance(start["point"], end["point"])
        vertical = abs(end["point"]["altitude_m"] - start["point"]["altitude_m"])
        seconds += max(horizontal / speed, vertical / 0.5)
    return seconds


def task_region_metadata(strips: list[Strip], passes: list[dict], fit: dict) -> list[dict]:
    pass_indices_by_region: dict[str, list[int]] = {}
    for item in passes:
        if item["capture_role"] != "NONE":
            pass_indices_by_region.setdefault(item["region_id"], []).append(item["pass_index"])
    result = []
    for priority, region_id in enumerate(dict.fromkeys(strip.region_id for strip in strips), start=1):
        region_strips = [strip for strip in strips if strip.region_id == region_id]
        target_pose = region_strips[0].poses[len(region_strips[0].poses) // 2]
        target_xyz = target_pose.get("look_at_world_xyz_m", target_pose["world_xyz_m"])
        result.append({
            "region_id": region_id,
            "priority": priority,
            "kind": region_strips[0].task_class,
            "risk_score": max(float(strip.source.get("risk_score_max", 0.0)) for strip in region_strips),
            "reasons": ["v30_active_recapture", "sfm_surface_support_deficit"],
            "target_wgs84": absolute_target(target_xyz, fit),
            "pass_indices": pass_indices_by_region[region_id],
            "suggested_survey_photos": sum(strip.photo_count for strip in region_strips),
        })
    return result


def compile_sortie(
    strips: list[Strip],
    fit: dict,
    source_hash: str,
    sortie_number: int,
    sortie_count: int,
    speed_mps: float,
    created_at_epoch_ms: int,
) -> dict:
    waypoints: list[dict] = []
    passes: list[dict] = []
    next_pass_index = 0
    for strip_index, strip in enumerate(strips):
        if strip_index:
            transit_waypoints, transit_metadata = make_transit_unit(
                strips[strip_index - 1], strip, fit, next_pass_index,
            )
            waypoints.extend(transit_waypoints)
            passes.append(transit_metadata)
            next_pass_index += 1
        capture_waypoints, capture_metadata = make_capture_unit(strip, fit, next_pass_index)
        waypoints.extend(capture_waypoints)
        passes.append(capture_metadata)
        next_pass_index += 1

    photo_count = sum(strip.photo_count for strip in strips)
    path_meters = mission_path(waypoints)
    views = list(dict.fromkeys(strip.capture_view for strip in strips))
    mission_seed = f"{source_hash}:mission" if sortie_count == 1 else f"{source_hash}:sortie:{sortie_number}"
    return {
        "schema_version": 11,
        "id": str(uuid.uuid5(uuid.NAMESPACE_URL, mission_seed)),
        "name": "two_buildings V30 补缺航线" if sortie_count == 1 else
            f"two_buildings V30 补缺航线 {sortie_number:02d}/{sortie_count:02d}",
        "created_at_epoch_ms": created_at_epoch_ms,
        "coordinate_frame": "WGS84",
        "camera_profile": {
            "id": "dji-runtime-selected-photo-4x3",
            "image_width_px": 4000,
            "image_height_px": 3000,
            "horizontal_fov_deg": 73.7,
            "vertical_fov_deg": 53.1,
            "minimum_capture_interval_s": 2.0,
        },
        "constraints": {
            "altitude_agl_m": 50.0,
            "forward_overlap": 0.70,
            "side_overlap": 0.70,
            "speed_mps": speed_mps,
            "gimbal_pitch_deg": -90.0,
            "route_heading_deg": float(strips[0].source.get("route_heading_deg", 0.0)),
            "crosshatch": False,
            "collection_mode": "OBLIQUE_FIVE_DIRECTION",
            "oblique_gimbal_pitch_deg": -45.0,
            "boundary_margin_m": 0.0,
            "altitude_mode": "RELATIVE_TO_TAKEOFF",
            "target_surface_to_takeoff_m": 0.0,
            "safe_takeoff_altitude_m": max(
                float(pose["relative_altitude_m"]) for strip in strips for pose in strip.poses
            ),
            "takeoff_speed_mps": 3.0,
            "takeoff_mode": "MANUAL",
            "start_point_mode": "AUTO_NEAREST",
            "completion_action": "RETURN_TO_HOME",
            "capture_trigger_mode": "DISTANCE",
            "timed_capture_interval_s": 2.0,
            "oblique_forward_overlap": 0.60,
            "oblique_side_overlap": 0.60,
            "oblique_heading_mode": "FIXED_CAPTURE_DIRECTION",
            "enabled_capture_views": views,
        },
        "roi": convex_hull(waypoints),
        "waypoints": waypoints,
        "estimated_path_m": path_meters,
        "estimated_photo_count": photo_count,
        "estimated_flight_s": estimated_flight_seconds(waypoints, speed_mps),
        "terrain_plan": None,
        "active_mapping": {
            "schema_version": 1,
            "selection_method": "V30 relative-height active recapture route",
            "ground_truth_used": False,
            "gs_used_for_selection": bool(strips[0].source.get("gs_used_for_selection", False)),
            "ordinary_gps_used": True,
            "source_capture_count": photo_count,
            "survey_capture_count": photo_count,
            "bridge_capture_count": 0,
            "source_estimated_route_distance_m": path_meters,
            "regions": task_region_metadata(strips, passes, fit),
            "passes": passes,
        },
    }


def validate_outputs(source: dict, missions: list[dict]) -> dict:
    total_photos = sum(mission["estimated_photo_count"] for mission in missions)
    expected_photos = int(source["statistics"]["capture_poses"])
    if total_photos != expected_photos:
        raise ValueError(f"compiled photos {total_photos} != V30 source {expected_photos}")
    capture_waypoints = [
        waypoint for mission in missions for waypoint in mission["waypoints"]
        if waypoint["capture_action"] != "NONE" or waypoint["kind"] in {"PASS_START", "PASS_END"}
    ]
    altitude_counts: dict[str, int] = {}
    for mission in missions:
        for strip_pass in mission["active_mapping"]["passes"]:
            if strip_pass["capture_role"] == "NONE":
                continue
            pass_waypoints = [
                waypoint for waypoint in mission["waypoints"]
                if waypoint["pass_index"] == strip_pass["pass_index"]
            ]
            altitude = str(round(pass_waypoints[0]["point"]["altitude_m"], 3))
            if pass_waypoints[0]["kind"] == "CAPTURE_POINT":
                count = 1
            else:
                length = sum(horizontal_distance(a["point"], b["point"]) for a, b in zip(pass_waypoints, pass_waypoints[1:]))
                count = max(2, math.ceil(length / pass_waypoints[0]["capture_interval_m"]) + 1)
            altitude_counts[altitude] = altitude_counts.get(altitude, 0) + count
    if altitude_counts != {"50.0": 372, "52.0": 4, "70.0": 260}:
        raise ValueError(f"unexpected capture altitude distribution: {altitude_counts}")
    high_rise_views = {
        waypoint["capture_view"]
        for mission in missions
        for metadata in mission["active_mapping"]["passes"]
        if metadata["role"] == "HIGH_RISE_FIVE_DIRECTION"
        for waypoint in mission["waypoints"]
        if waypoint["pass_index"] == metadata["pass_index"]
    }
    required_views = SUPPORTED_CAPTURE_VIEWS - {"LOCAL_OBLIQUE"}
    if high_rise_views != required_views:
        raise ValueError(f"high-rise views incomplete: {sorted(high_rise_views)}")
    max_distance = max(
        horizontal_distance(a["point"], b["point"])
        for mission in missions for a, b in zip(mission["waypoints"], mission["waypoints"][1:])
    )
    max_yaw = max(
        abs(angle_delta(a["heading_deg"], b["heading_deg"]))
        for mission in missions for a, b in zip(mission["waypoints"], mission["waypoints"][1:])
    )
    max_pitch = max(
        abs(a["gimbal_pitch_deg"] - b["gimbal_pitch_deg"])
        for mission in missions for a, b in zip(mission["waypoints"], mission["waypoints"][1:])
    )
    if max_distance > 25.0 + 1.0e-6 or max_yaw > 45.0 + 1.0e-6 or max_pitch > 15.0 + 1.0e-6:
        raise ValueError("compiled route exceeds Android adjacency limits")
    return {
        "sortie_count": len(missions),
        "photo_count": total_photos,
        "capture_waypoint_count": len(capture_waypoints),
        "altitude_photo_counts": altitude_counts,
        "high_rise_views": sorted(high_rise_views),
        "maximum_adjacent_distance_m": max_distance,
        "maximum_yaw_step_deg": max_yaw,
        "maximum_gimbal_pitch_step_deg": max_pitch,
    }


def compile_all(
    source: dict,
    fit: dict,
    source_hash: str,
    speed_mps: float,
    max_sortie_distance_m: float | None,
    max_sortie_photos: int | None,
    created_at_epoch_ms: int,
) -> tuple[list[dict], dict]:
    strips = flatten_strips(source)
    if sum(strip.photo_count for strip in strips) != 636:
        raise ValueError("reviewed V30 route must contain exactly 636 capture poses")
    sorties = split_sorties(strips, fit, max_sortie_distance_m, max_sortie_photos)
    missions = [
        compile_sortie(
            sortie,
            fit,
            source_hash,
            index,
            len(sorties),
            speed_mps,
            created_at_epoch_ms,
        )
        for index, sortie in enumerate(sorties, start=1)
    ]
    return missions, validate_outputs(source, missions)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--wgs84-fit", type=Path, required=True)
    parser.add_argument("--speed-mps", type=float, default=2.8)
    parser.add_argument("--max-sortie-distance-m", type=float)
    parser.add_argument("--max-sortie-photos", type=int)
    args = parser.parse_args()
    if args.speed_mps <= 0.0:
        raise ValueError("speed must be positive")
    if args.max_sortie_distance_m is not None and args.max_sortie_distance_m <= 0.0:
        raise ValueError("sortie distance limit must be positive")
    if args.max_sortie_photos is not None and args.max_sortie_photos <= 0:
        raise ValueError("sortie photo limit must be positive")

    source_bytes = args.source.read_bytes()
    source = json.loads(source_bytes)
    fit = json.loads(args.wgs84_fit.read_text())
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    created_at_epoch_ms = int(time.time() * 1000)
    missions, validation = compile_all(
        source,
        fit,
        source_hash,
        args.speed_mps,
        args.max_sortie_distance_m,
        args.max_sortie_photos,
        created_at_epoch_ms,
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for stale in args.output_dir.glob("openfly-active-recapture-two-buildings-v30-sortie-*.json"):
        stale.unlink()
    (args.output_dir / "openfly-active-recapture-two-buildings-v30.json").unlink(missing_ok=True)
    summaries = []
    for index, mission in enumerate(missions, start=1):
        output = args.output_dir / (
            "openfly-active-recapture-two-buildings-v30.json" if len(missions) == 1 else
            f"openfly-active-recapture-two-buildings-v30-sortie-{index:02d}.json"
        )
        output.write_text(json.dumps(mission, ensure_ascii=False, indent=2) + "\n")
        summaries.append({
            "sortie": index,
            "file": output.name,
            "photos": mission["estimated_photo_count"],
            "waypoints": len(mission["waypoints"]),
            "path_m": round(mission["estimated_path_m"], 3),
            "flight_s": round(mission["estimated_flight_s"], 3),
            "altitude_min_m": min(item["point"]["altitude_m"] for item in mission["waypoints"]),
            "altitude_max_m": max(item["point"]["altitude_m"] for item in mission["waypoints"]),
        })
    manifest = {
        "schema_version": 1,
        "source": str(args.source),
        "source_sha256": source_hash,
        "wgs84_fit": str(args.wgs84_fit),
        "speed_mps": args.speed_mps,
        "max_sortie_distance_m": args.max_sortie_distance_m,
        "max_sortie_photos": args.max_sortie_photos,
        "validation": validation,
        "sorties": summaries,
    }
    manifest_path = args.output_dir / "openfly-active-recapture-two-buildings-v30-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
