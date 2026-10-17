#!/usr/bin/env python3
"""Reject a green test command that skipped a requested JUnit or gtest suite."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


report_dir, names = sys.argv[1:]
suites = {
    suite.get("name"): suite
    for report in Path(report_dir).glob("*.xml")
    for suite in ET.parse(report).getroot().iter("testsuite")
}
for name in names.split(","):
    assert name in suites, f"Missing test suite: {name}"
    suite = suites[name]
    assert int(suite.get("tests", "0")) > int(suite.get("skipped", "0")), f"No tests ran: {name}"
    executed = [
        case for case in suite.findall("testcase")
        if case.find("skipped") is None and case.get("status") != "notrun"
    ]
    assert executed, f"No tests executed: {name}"
    assert int(suite.get("failures", "0")) == 0, f"Failed tests: {name}"
    assert int(suite.get("errors", "0")) == 0, f"Errored tests: {name}"
    print(f"Verified {name}: {len(executed)} executed tests")
