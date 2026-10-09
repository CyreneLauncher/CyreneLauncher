#!/usr/bin/env python3
"""Compatibility entry point; maintained in mesa-orbis."""
import sys
from pathlib import Path

_integration = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_integration / 'graphics-probe/tools'))
from repositories import run_recipe
run_recipe('mesa-orbis', 'build_mesa.py', globals(), _integration)
