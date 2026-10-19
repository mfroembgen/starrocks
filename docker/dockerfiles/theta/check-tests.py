#!/usr/bin/env python3
"""Reject a green test command that skipped a requested JUnit or gtest suite."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


THETA_TEST_SOURCE = Path(__file__).resolve().parents[3] / "be/test/exprs/agg/data_sketch/ds_theta_test.cpp"


def check_reports(report_dir, names):
    suites = [
        suite
        for report in Path(report_dir).glob("*.xml")
        for suite in ET.parse(report).getroot().iter("testsuite")
    ]
    for name in names.split(","):
        selected = [suite for suite in suites if suite.get("name") == name]
        assert selected, f"Missing test suite: {name}"
        assert len(selected) == 1, f"Duplicate test suite: {name}"
        suite = selected[0]
        cases = suite.findall("testcase")
        assert cases and len(cases) == int(suite.get("tests", "0")), f"Incomplete test suite: {name}"
        for field in ("skipped", "disabled", "failures", "errors"):
            assert int(suite.get(field, "0")) == 0, f"Nonzero {field}: {name}"
        assert not any(child.tag in ("skipped", "failure", "error") for child in suite.iter()), \
            f"Unsuccessful test suite: {name}"
        for case in cases:
            assert case.get("status") in (None, "run"), f"Test did not run: {name}.{case.get('name')}"
            assert case.get("result") in (None, "completed"), f"Test did not complete: {name}.{case.get('name')}"
        if name == "DataSketchsThetaTest":
            expected = set(re.findall(
                r"^TEST_F\(\s*DataSketchsThetaTest\s*,\s*(\w+)\s*\)",
                THETA_TEST_SOURCE.read_text(), re.MULTILINE,
            ))
            assert expected, "No Theta tests found in source"
            actual = [case.get("name") for case in cases]
            assert len(actual) == len(expected) and set(actual) == expected, \
                f"Incomplete Theta cases: missing={sorted(expected - set(actual))}, unexpected={sorted(set(actual) - expected)}"
        print(f"Verified {name}: {len(cases)} executed tests")


if __name__ == "__main__":
    check_reports(*sys.argv[1:])
