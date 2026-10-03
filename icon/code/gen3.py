#!/usr/bin/env python3

"""
Draw a stylised 3D-ish wave orbit.

The underlying orbit is a circle in 3D. A sine or square wave
moves above/below the orbital plane. The result is projected
into a flat 2D image.

This is deliberately an illustration rather than a physically
accurate 3D renderer.
"""

import math
import random

from PIL import Image, ImageDraw


# ============================================================
# PARAMETERS
# ============================================================

OUTPUT_SIZE = 512
OUTPUT_FILE = "orbit.png"


# ------------------------------------------------------------
# Orbit
# ------------------------------------------------------------

# Radius of the actual circular orbit.
ORBIT_RADIUS = 175

# Tilt of the orbital plane.
#
# 0   = viewed edge-on
# 90  = viewed from directly above
#
# Around 20-40 gives a nice compressed ellipse.
ORBIT_TILT = 28

# Rotate the resulting orbit in the image.
ORBIT_ROTATION = 0


# ------------------------------------------------------------
# Wave
# ------------------------------------------------------------

# "sine" or "square"
WAVE_TYPE = "square"

# Number of oscillations during one complete orbit.
WAVE_CYCLES = 9

# Height of the wave above/below the orbital plane.
WAVE_HEIGHT = 28

RANDOM_SEED = 49
WAVE_IRREGULARITY = 0.5
WAVE_ROTATION = 0.5  # fraction of a full orbit, 0.0–1.0

# How much the wave's up/down direction is tilted.
#
# 0 = wave rises vertically in the image
# positive/negative = lean the wave axis
WAVE_TILT = 0


# ------------------------------------------------------------
# Fake perspective
# ------------------------------------------------------------

# 0 = completely flat orthographic projection.
# Larger values make things closer to the viewer slightly larger.
PERSPECTIVE = 0.0

# How much the depth affects the apparent position.
# This is mostly an artistic exaggeration control.
DEPTH_PROJECTION = 1.0


# ------------------------------------------------------------
# Appearance
# ------------------------------------------------------------

LINE_WIDTH = 5

BACKGROUND = (255, 255, 255, 255)
LINE_COLOUR = (0, 0, 0, 255)


# ============================================================
# SETUP
# ============================================================

image = Image.new(
    "RGBA",
    (OUTPUT_SIZE, OUTPUT_SIZE),
    BACKGROUND,
)

draw = ImageDraw.Draw(image)

cx = OUTPUT_SIZE / 2
cy = OUTPUT_SIZE / 2

tilt = math.radians(ORBIT_TILT)
rotation = math.radians(ORBIT_ROTATION)

cos_tilt = math.cos(tilt)
sin_tilt = math.sin(tilt)

cos_rot = math.cos(rotation)
sin_rot = math.sin(rotation)


# ============================================================
# WAVE
# ============================================================

random.seed(RANDOM_SEED)

# Generate one random length for each half-cycle.
half_cycles = WAVE_CYCLES * 2

MIN_STINT = 0.9 
RANDOM_STINT = 1.3

lengths = []

for _ in range(half_cycles):
    lengths.append(
        MIN_STINT + random.random() * RANDOM_STINT
    )

# Normalise them so they still add up to exactly one complete orbit.
total = sum(lengths)
lengths = [x / total for x in lengths]

# Convert lengths into transition positions from 0 to 1.
transitions = [0.0]
for length in lengths:
    transitions.append(transitions[-1] + length)

EDGE_ANGLE = math.radians(19)

def wave_value(theta):
    # Freeze the wave at +1 around the left/right extrema.
    theta = theta % math.tau

    if (
        theta < EDGE_ANGLE
        or theta > math.tau - EDGE_ANGLE
        or abs(theta - math.pi) < EDGE_ANGLE
    ):
        return 1.0

    position = ((theta / math.tau) + WAVE_ROTATION) % 1.0

    for i in range(len(lengths)):
        if position < transitions[i + 1]:
            return 1.0 if i % 2 == 0 else -1.0

    return -1.0

# ============================================================
# 3D -> 2D PROJECTION
# ============================================================


def project(theta):
    # Base orbit
    orbit_x = ORBIT_RADIUS * math.cos(theta)
    orbit_y = ORBIT_RADIUS * math.sin(theta) * math.cos(cos_tilt)
    orbit_z = ORBIT_RADIUS * math.sin(theta) * math.sin(cos_tilt)

    # Rotate the orbit in the image plane
    x = orbit_x * cos_rot - orbit_y * sin_rot
    y = orbit_x * sin_rot + orbit_y * cos_rot

    # Wave is added AFTER the orbit rotation, so its angle is
    # independent of the orbit's rotation.
    wave = WAVE_HEIGHT * wave_value(theta)
    wave_angle = math.radians(WAVE_TILT)

    x += wave * math.sin(wave_angle)
    y += wave * math.cos(wave_angle)

    return cx + x, cy + y, orbit_z

# ============================================================
# GENERATE CURVE
# ============================================================

POINTS = max(3000, WAVE_CYCLES * 300)

points = []

for i in range(POINTS + 1):

    theta = (
        i / POINTS
    ) * math.tau

    x, y, z = project(theta)

    points.append((x, y, z))


# ============================================================
# DRAW
# ============================================================

# For now, draw as one continuous line.
#
# We intentionally don't do real 3D hidden-surface handling.
# The goal is a graphic symbol, not a renderer.

xy_points = [
    (x, y)
    for x, y, z in points
]

draw.line(
    xy_points,
    fill=LINE_COLOUR,
    width=LINE_WIDTH,
    joint="curve",
)


# ------------------------------------------------------------
# Save
# ------------------------------------------------------------

image.save(OUTPUT_FILE)

print(f"Wrote {OUTPUT_FILE}")
