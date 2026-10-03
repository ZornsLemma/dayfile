#!/usr/bin/env python3

"""
Generate a wavy orbital path around an ellipse.

Requires:
    pip install pillow

The output is intended as a visual starting point for an icon,
not as a physically/mathematically exact orbital curve.
"""

import math
import random

from PIL import Image, ImageDraw


# ============================================================
# PARAMETERS
# ============================================================

# Final image size
OUTPUT_SIZE = 512

# Render larger internally, then downsample for antialiasing.
# 4 is usually plenty; 8 gives a very smooth source.
SUPERSAMPLE = 1

# Output filename
OUTPUT_FILE = "orbit.png"


# ------------------------------------------------------------
# Orbit shape
# ------------------------------------------------------------

# Fraction of the canvas occupied by the orbit's major axis.
# 0.8 means roughly 80% of the image width.
ORBIT_SIZE = 0.72

# Eccentricity-ish control.
#
# 0.0 = circle
# 0.3 = mildly elliptical
# 0.5 = clearly elliptical
# 0.7 = quite elongated
#
# This is deliberately not the strict mathematical definition
# of eccentricity; it is simply a convenient visual parameter.
ORBIT_ECCENTRICITY = 0.25

# Rotate the ellipse in the image.
ORBIT_ROTATION = -20.0       # degrees


# ------------------------------------------------------------
# Wave
# ------------------------------------------------------------

# "sine" or "square"
WAVE_TYPE = "sine"

# Number of complete waves around the orbit.
WAVE_CYCLES = 64

# Distance the wave moves away from the underlying ellipse,
# as a fraction of the orbit's minor radius.
WAVE_AMPLITUDE = 0.055

# For the square-ish wave, controls how rounded the corners are.
# 0 = very square
# 1 = very rounded
SQUARE_ROUNDING = 0.15


# ------------------------------------------------------------
# Irregularity
# ------------------------------------------------------------

# 0 = perfectly regular
# 1 = quite irregular
WAVE_IRREGULARITY = 0.0

# Random seed so you get reproducible results.
# Change this number to try another irregular pattern.
RANDOM_SEED = 7


# ------------------------------------------------------------
# Appearance
# ------------------------------------------------------------

# Line width in final pixels.
LINE_WIDTH = 5

# "round" or "butt"
LINE_CAP = "round"

# Background:
# (0, 0, 0, 0) = transparent
# (255, 255, 255, 255) = white
BACKGROUND = (255, 255, 255, 255)

# Line colour.
# Keep black for the eventual monochrome icon.
LINE_COLOUR = (0, 0, 0, 255)


# ============================================================
# IMPLEMENTATION
# ============================================================

random.seed(RANDOM_SEED)

S = SUPERSAMPLE
SIZE = OUTPUT_SIZE * S

image = Image.new("RGBA", (SIZE, SIZE), BACKGROUND)
draw = ImageDraw.Draw(image)


def smoothstep(x):
    """0..1 smooth interpolation."""
    return x * x * (3 - 2 * x)


def interpolate(a, b, t):
    return a + (b - a) * t


def make_irregular_wave(cycles, irregularity, samples_per_cycle):
    """
    Make a smooth-ish irregular sequence of wave amplitudes.

    The result is deliberately fairly simple: each half-cycle can
    vary slightly in amplitude and duration.
    """

    half_cycles = cycles * 2

    # Random amplitude for each half-cycle.
    amplitudes = []
    for _ in range(half_cycles):
        variation = random.uniform(-1, 1) * irregularity
        amplitudes.append(1.0 + variation)

    # Random phase spacing.
    spacings = []
    for _ in range(half_cycles):
        variation = random.uniform(-1, 1) * irregularity
        spacings.append(1.0 + variation)

    total_spacing = sum(spacings)
    spacings = [x / total_spacing * half_cycles for x in spacings]

    boundaries = [0.0]
    for spacing in spacings:
        boundaries.append(boundaries[-1] + spacing)

    return amplitudes, boundaries



def wave_value(t, wave_type, cycles, irregularity):
    """Return a clean periodic wave around the orbit."""

    phase = t * cycles * 2 * math.pi

    if wave_type == "sine":
        value = math.sin(phase)

    elif wave_type == "square":
        value = 1.0 if math.sin(phase) >= 0 else -1.0

    else:
        raise ValueError(f"Unknown wave type: {wave_type}")

    # Mild amplitude variation, if wanted.
    if irregularity > 0:
        # Several broad random variations rather than randomising
        # every individual point.
        variation = (
            math.sin(t * 2 * math.pi * 1.7 + 1.2) * 0.5
            + math.sin(t * 2 * math.pi * 2.3 + 4.1) * 0.5
        )
        value *= 1.0 + variation * irregularity

    return value



# ------------------------------------------------------------
# Ellipse geometry
# ------------------------------------------------------------

cx = SIZE / 2
cy = SIZE / 2

major_radius = SIZE * ORBIT_SIZE / 2

# Eccentricity here simply controls how much the minor axis shrinks.
minor_radius = major_radius * (1.0 - ORBIT_ECCENTRICITY)

angle = math.radians(ORBIT_ROTATION)

cos_a = math.cos(angle)
sin_a = math.sin(angle)

amplitude = minor_radius * WAVE_AMPLITUDE


def ellipse_point(theta, radial_offset):
    """
    Point on the ellipse, with radial-ish displacement.

    The displacement is applied along the local normal direction.
    This gives a more natural-looking wave than simply changing
    the x/y coordinates independently.
    """

    x = major_radius * math.cos(theta)
    y = minor_radius * math.sin(theta)

    # Approximate outward normal of the ellipse.
    nx = math.cos(theta) / major_radius
    ny = math.sin(theta) / minor_radius

    length = math.hypot(nx, ny)
    nx /= length
    ny /= length

    x += nx * radial_offset
    y += ny * radial_offset

    # Rotate ellipse.
    xr = x * cos_a - y * sin_a
    yr = x * sin_a + y * cos_a

    return cx + xr, cy + yr


# ------------------------------------------------------------
# Generate points
# ------------------------------------------------------------

# Lots of points because this will ultimately be vector traced.
POINTS = max(2000, WAVE_CYCLES * 300)

points = []

for i in range(POINTS + 1):

    t = i / POINTS
    theta = t * 2 * math.pi

    displacement = wave_value(
        t,
        WAVE_TYPE,
        WAVE_CYCLES,
        WAVE_IRREGULARITY,
    )

    displacement *= amplitude

    points.append(
        ellipse_point(theta, displacement)
    )


# ------------------------------------------------------------
# Draw
# ------------------------------------------------------------

width = max(1, round(LINE_WIDTH * S))

draw.line(
    points,
    fill=LINE_COLOUR,
    width=width,
    joint="curve",
)


# Pillow's line caps are not always exactly what you want,
# so explicitly add round caps if requested.
if LINE_CAP == "round":
    radius = width / 2

    for x, y in (points[0], points[-1]):
        draw.ellipse(
            (
                x - radius,
                y - radius,
                x + radius,
                y + radius,
            ),
            fill=LINE_COLOUR,
        )


# ------------------------------------------------------------
# Downsample
# ------------------------------------------------------------

image.save(OUTPUT_FILE)

print(f"Wrote {OUTPUT_FILE}")
