"""Regression tests for the native Theta image test-report gate."""

import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).with_name("check-tests.py")
SPEC = importlib.util.spec_from_file_location("check_tests", SCRIPT)
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)
THETA = "DataSketchsThetaTest"
THETA_CASES = [
    line.split(",", 1)[1].split(")", 1)[0].strip()
    for line in checker.THETA_TEST_SOURCE.read_text().splitlines()
    if line.startswith("TEST_F(DataSketchsThetaTest,")
]


class CheckReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.reports = Path(self.temp.name)

    def report(self, name=THETA, cases=None, **attributes):
        if cases is None:
            cases = THETA_CASES
        suite = ET.Element("testsuite", name=name, tests=str(len(cases)), failures="0", errors="0", skipped="0")
        suite.attrib.update(attributes)
        for case in cases:
            ET.SubElement(suite, "testcase", name=case, classname=name)
        return suite

    def check(self, suite, name=THETA):
        ET.ElementTree(suite).write(self.reports / "report.xml")
        with contextlib.redirect_stdout(io.StringIO()):
            checker.check_reports(self.reports, name)

    def reject(self, suite, name=THETA):
        with self.assertRaises(AssertionError):
            self.check(suite, name)

    def test_complete_current_theta_suite_passes(self):
        self.assertEqual(len(THETA_CASES), 17)
        suite = self.report()
        for case in suite:
            case.set("status", "run")
            case.set("result", "completed")
        root = ET.Element("testsuites")
        root.append(suite)
        self.check(root)

    def test_existing_fe_class_selection_passes(self):
        root = ET.Element("testsuites")
        root.append(self.report("com.starrocks.FirstTest", ["first", "second"]))
        root.append(self.report("com.starrocks.SecondTest", ["third"]))
        self.check(root, "com.starrocks.FirstTest,com.starrocks.SecondTest")

    def test_missing_suite_is_rejected(self):
        self.reject(self.report("OtherTest"))

    def test_empty_suite_is_rejected(self):
        self.reject(self.report(cases=[]))

    def test_partial_theta_suite_is_rejected_even_with_matching_count(self):
        self.reject(self.report(cases=THETA_CASES[:-1]))

    def test_renamed_theta_case_is_rejected(self):
        self.reject(self.report(cases=THETA_CASES[:-1] + ["RenamedCase"]))

    def test_duplicate_theta_case_is_rejected(self):
        self.reject(self.report(cases=THETA_CASES[:-1] + [THETA_CASES[0]]))

    def test_fe_reported_count_must_match_cases(self):
        self.reject(self.report("ExampleTest", ["first"], tests="2"), "ExampleTest")

    def test_nonzero_suite_counters_are_rejected(self):
        for field in ("skipped", "disabled", "failures", "errors"):
            with self.subTest(field=field):
                self.reject(self.report(**{field: "1"}))

    def test_unexecuted_case_statuses_are_rejected(self):
        for attribute, value in (("status", "notrun"), ("result", "skipped"), ("result", "suppressed")):
            with self.subTest(attribute=attribute, value=value):
                suite = self.report()
                suite[0].set(attribute, value)
                self.reject(suite)

    def test_child_failures_errors_and_skips_are_rejected_with_zero_counters(self):
        for tag in ("failure", "error", "skipped"):
            for location in ("suite", "case"):
                with self.subTest(tag=tag, location=location):
                    suite = self.report()
                    ET.SubElement(suite if location == "suite" else suite[0], tag)
                    self.reject(suite)

    def test_duplicate_suite_cannot_hide_an_incomplete_report(self):
        ET.ElementTree(self.report(cases=THETA_CASES[:-1])).write(self.reports / "partial.xml")
        self.reject(self.report())

    def test_expected_theta_names_follow_source(self):
        source = self.reports / "theta.cpp"
        source.write_text("TEST_F(DataSketchsThetaTest, NewSourceCase) {}\n")
        with patch.object(checker, "THETA_TEST_SOURCE", source):
            self.check(self.report(cases=["NewSourceCase"]))
            self.reject(self.report())

    def test_existing_command_line_passes_and_rejects_partial_theta(self):
        for cases, success in ((THETA_CASES, True), (THETA_CASES[:-1], False)):
            with self.subTest(success=success):
                ET.ElementTree(self.report(cases=cases)).write(self.reports / "report.xml")
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), str(self.reports), THETA],
                    capture_output=True, text=True,
                )
                self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
