#!/usr/bin/env python3
"""Compatibility entry point; maintained in sdl-orbis."""
import sys
from pathlib import Path

_integration = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_integration / 'graphics-probe/tools'))
from repositories import run_recipe
run_recipe('sdl-orbis', 'build_sdl3.py', globals(), _integration)
