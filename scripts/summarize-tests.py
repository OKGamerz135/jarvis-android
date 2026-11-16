#!/usr/bin/env python3
"""Sums JUnit XML results so CI can show real numbers. Usage: summarize-tests.py <results_dir>. Prints 'N tests, F failures, E errors, S skipped'."""
import glob, sys, xml.etree.ElementTree as ET
t = f = e = s = 0
files = glob.glob(sys.argv[1].rstrip("/") + "/*.xml")
for p in files:
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
print(f"{t} tests, {f} failures, {e} errors, {s} skipped, {len(files)} files")
