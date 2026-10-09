#!/usr/bin/env python3
"""Compatibility entry point; maintained in lwjgl-orbis."""
import sys
from pathlib import Path

_integration = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_integration / 'graphics-probe/tools'))
from repositories import run_recipe
run_recipe('lwjgl-orbis', 'build_lwjgl_facade.py', globals(), _integration)
