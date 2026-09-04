#!/usr/bin/env python3
"""Generate Android legacy and adaptive launcher assets from the shipping iOS icon."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


BACKGROUND = (10, 14, 22)
FOREGROUND_PALETTE = (
    (246, 249, 252),  # ring
    (25, 207, 232),   # aircraft body
    (129, 235, 249),  # upper facet
    (125, 181, 190),  # lower facet
)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def extract_foreground(source: Image.Image) -> Image.Image:
    output: list[tuple[int, int, int, int]] = []
    for pixel in source.convert("RGB").getdata():
        vector = tuple(pixel[channel] - BACKGROUND[channel] for channel in range(3))
        best_color = FOREGROUND_PALETTE[0]
        best_alpha = 0.0
        best_residual = float("inf")
        for color in FOREGROUND_PALETTE:
            direction = tuple(color[channel] - BACKGROUND[channel] for channel in range(3))
            denominator = sum(component * component for component in direction)
            alpha = max(0.0, min(1.0, sum(vector[i] * direction[i] for i in range(3)) / denominator))
            residual = sum(
                (pixel[i] - (BACKGROUND[i] + alpha * direction[i])) ** 2
                for i in range(3)
            )
            if residual < best_residual:
                best_color, best_alpha, best_residual = color, alpha, residual
        output.append((*best_color, round(best_alpha * 255.0) if best_alpha >= 1.0 / 255.0 else 0))
    foreground = Image.new("RGBA", source.size)
    foreground.putdata(output)
    return foreground


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--res", type=Path, default=Path("app/src/main/res"))
    args = parser.parse_args()
    source = Image.open(args.source).convert("RGB")
    if source.size != (1024, 1024):
        raise SystemExit(f"Expected a 1024x1024 iOS icon, got {source.size}")
    if source.getpixel((0, 0)) != BACKGROUND:
        raise SystemExit("The iOS icon background no longer matches Android ic_launcher_background")
    foreground = extract_foreground(source)
    for density, (legacy_size, canvas_size) in DENSITIES.items():
        directory = args.res / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        source.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS).save(
            directory / "ic_launcher.png",
            optimize=True,
        )
        inner_size = canvas_size * 19 // 27
        inset = (canvas_size - inner_size) // 2
        inner = foreground.convert("RGBa").resize(
            (inner_size, inner_size),
            Image.Resampling.LANCZOS,
        ).convert("RGBA")
        canvas = Image.new("RGBA", (canvas_size, canvas_size))
        canvas.alpha_composite(inner, (inset, inset))
        canvas.save(directory / "ic_launcher_foreground.png", optimize=True)


if __name__ == "__main__":
    main()
