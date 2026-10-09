#!/usr/bin/env python3
"""Print failed tests from Gradle's JUnit XML reports as GitHub error annotations."""
import glob
import sys
import xml.etree.ElementTree as ET

found = 0
for path in glob.glob("app/build/**/TEST-*.xml", recursive=True):
    for case in ET.parse(path).getroot().iter("testcase"):
        for problem in list(case.findall("failure")) + list(case.findall("error")):
            msg = (problem.get("message") or problem.text or "").strip().splitlines()
            first = msg[0][:300] if msg else ""
            print(f"::error title=Test failed: {case.get('classname','')}.{case.get('name','')}::{first}")
            found += 1
if not found:
    print("::error title=Tests failed::no failing test in the reports; the build, install or emulator failed")
sys.exit(0)
