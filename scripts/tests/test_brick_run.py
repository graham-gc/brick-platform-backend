import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).resolve().parents[1] / "brick_run.py"
SECRET = "private-payload-that-must-not-reach-reports"


def flow_detail(flow_id=7, run_id=101, business="passed", suite_run_id=None):
    assertions = [] if business in ("not_configured", "not_evaluated") else [{
        "id": run_id * 10, "runNodeId": run_id * 100, "assertionId": 88,
        "status": "failed" if business == "failed" else "passed",
        "assertionType": "json_path", "fieldPath": "$.status",
        "actualValue": SECRET, "expectedValue": SECRET, "errorMsg": SECRET,
    }]
    failed = int(business == "failed")
    return {
        "run": {"id": run_id, "flowId": flow_id, "status": "success", "businessStatus": business,
                "suiteRunId": suite_run_id, "durationMs": 234, "errorMsg": SECRET},
        "nodes": [{"id": run_id * 100, "runId": run_id, "nodeId": 23, "status": "success",
                   "businessStatus": business, "assertionTotalCount": len(assertions),
                   "assertionPassedCount": len(assertions) - failed, "assertionFailedCount": failed,
                   "assertions": assertions, "requestHeaders": SECRET, "fullResponse": SECRET,
                   "requestUrl": "https://example.test/?token=" + SECRET}],
    }


def suite_detail(details, expected=None):
    flows = [detail["run"] for detail in details]
    businesses = [flow["businessStatus"] for flow in flows]
    succeeded = sum(flow["status"] == "success" for flow in flows)
    business = next((s for s in ("failed", "not_evaluated", "passed") if s in businesses), "not_configured")
    run = {"id": 901, "suiteId": 5, "status": "success" if succeeded == len(flows) else "failed",
           "businessStatus": business, "totalFlows": len(flows) if expected is None else expected,
           "successFlows": succeeded, "failedFlows": len(flows) - succeeded,
           "businessPassedFlows": businesses.count("passed"), "businessFailedFlows": businesses.count("failed"),
           "businessNotConfiguredFlows": businesses.count("not_configured"),
           "businessNotEvaluatedFlows": businesses.count("not_evaluated")}
    return {"suiteRun": run, "flowRuns": flows}


class FixtureServer:
    def __init__(self):
        self.routes = {}
        self.calls = []
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def respond(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                fixture.calls.append((self.command, self.path, body, dict(self.headers)))
                route = fixture.routes.get((self.command, self.path), (404, {"success": False}))
                if callable(route):
                    route = route()
                status, payload = route
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                if status == 302:
                    self.send_header("Location", "/must-not-follow")
                self.end_headers()
                try:
                    self.wfile.write(payload if isinstance(payload, bytes) else json.dumps(payload).encode())
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = "http://127.0.0.1:%s/api/brick" % self.server.server_port

    def set(self, method, path, data):
        self.routes[(method, "/api/brick" + path)] = (200, {"success": True, "data": data})

    def flow(self, detail):
        run = detail["run"]
        self.set("POST", "/flows/%s/run" % run["flowId"], run)
        self.set("GET", "/flows/runs/%s" % run["id"], detail)

    def suite(self, details, expected=None):
        detail = suite_detail(details, expected)
        self.set("POST", "/test-suites/5/run?operator=ci", detail["suiteRun"])
        self.set("GET", "/test-suites/runs/901", detail)
        for flow in details:
            self.flow(flow)
        return detail

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.fixture = FixtureServer()
        self.addCleanup(self.fixture.close)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "reports"

    def run_cli(self, *args, expected=0, env=None):
        import os
        process_env = dict(os.environ)
        process_env.pop("BRICK_API_TOKEN", None)
        process_env.pop("BRICK_API_BASE_URL", None)
        if env:
            process_env.update(env)
        result = subprocess.run([sys.executable, str(SCRIPT), "--base-url", self.fixture.base_url,
                                 "--report-dir", str(self.output), *args],
                                capture_output=True, text=True, timeout=8, env=process_env)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def reports(self):
        return json.loads((self.output / "summary.json").read_text()), ET.parse(self.output / "results.xml").getroot()

    def assert_one_trigger(self):
        self.assertEqual(sum(method == "POST" for method, *_ in self.fixture.calls), 1)

    def test_saved_flow_passes_with_links_and_safe_reports(self):
        self.fixture.flow(flow_detail())
        result = self.run_cli("--flow-id", "7", "--web-base-url", "https://brick.example.test", env={"BRICK_API_TOKEN": "fixture-token"})
        summary, junit = self.reports()
        self.assertEqual(summary["verdict"], "passed")
        self.assertEqual(summary["runId"], 101)
        self.assertEqual(summary["flows"][0]["url"], "https://brick.example.test/runs/101")
        self.assertEqual(junit.attrib["failures"], "0")
        self.assertEqual(junit.find("testcase").attrib["time"], "0.234")
        self.assertIn("https://brick.example.test/runs/101", result.stdout)
        self.assert_one_trigger()
        trigger = self.fixture.calls[0]
        self.assertEqual(json.loads(trigger[2]), {"operator": "ci", "runType": 0})
        self.assertEqual(trigger[3]["Authorization"], "Bearer fixture-token")
        for text in ((self.output / "summary.json").read_text(), (self.output / "results.xml").read_text(), result.stdout + result.stderr):
            self.assertNotIn(SECRET, text)
            self.assertNotIn("fixture-token", text)

    def test_http_success_with_failed_business_assertion_blocks_pipeline(self):
        self.fixture.flow(flow_detail(business="failed"))
        self.run_cli("--flow-id", "7", expected=1)
        summary, junit = self.reports()
        self.assertEqual(summary["flows"][0]["reason"], "assertion_failed")
        self.assertEqual(summary["flows"][0]["issues"][0]["assertionSnapshotIds"], [1010])
        self.assertIsNotNone(junit.find("testcase/failure"))

    def test_helpers_without_assertions_do_not_block_a_verified_flow(self):
        detail = flow_detail()
        helper = flow_detail(business="not_configured")["nodes"][0]
        helper.update(id=20200, nodeId=24)
        detail["nodes"].append(helper)
        self.fixture.flow(detail)
        self.run_cli("--flow-id", "7")
        self.assertEqual(self.reports()[0]["flows"][0]["assertionCount"], 1)

    def test_no_assertions_is_rejected_unless_explicitly_allowed(self):
        self.fixture.flow(flow_detail(business="not_configured"))
        self.run_cli("--flow-id", "7", expected=1)
        self.assertEqual(self.reports()[0]["flows"][0]["reason"], "no_assertions")
        self.run_cli("--flow-id", "7", "--allow-no-assertions")
        summary, junit = self.reports()
        self.assertTrue(summary["policy"]["allowNoAssertions"])
        self.assertEqual(summary["counts"]["skipped"], 1)
        self.assertIsNotNone(junit.find("testcase/skipped"))

    def test_allow_no_assertions_cannot_bypass_failed_or_unevaluated_assertions(self):
        for business, code in (("failed", 1), ("not_evaluated", 2)):
            with self.subTest(business=business):
                self.fixture.flow(flow_detail(business=business))
                self.run_cli("--flow-id", "7", "--allow-no-assertions", expected=code)

    def test_request_failure_and_blocked_node_are_regression_failures(self):
        for node_status in ("failed", "blocked"):
            with self.subTest(status=node_status):
                detail = flow_detail(business="not_evaluated")
                detail["run"]["status"] = "failed"
                detail["nodes"][0]["status"] = node_status
                self.fixture.flow(detail)
                self.run_cli("--flow-id", "7", expected=1)
                self.assertEqual(self.reports()[0]["flows"][0]["reason"], "execution_failed")

    def test_engine_failure_before_any_node_is_still_a_failed_regression(self):
        detail = flow_detail(business="not_evaluated")
        detail["run"]["status"] = "failed"
        detail["nodes"] = []
        self.fixture.flow(detail)
        self.run_cli("--flow-id", "7", expected=1)

    def test_mixed_suite_preserves_all_flow_results(self):
        self.fixture.suite([flow_detail(7, 101, suite_run_id=901), flow_detail(8, 102, "failed", 901)])
        self.run_cli("--suite-id", "5", expected=1)
        summary, junit = self.reports()
        self.assertEqual(summary["runId"], 901)
        self.assertEqual([f["outcome"] for f in summary["flows"]], ["passed", "failed"])
        self.assertEqual(junit.attrib["tests"], "2")
        self.assertEqual(junit.attrib["failures"], "1")
        self.assert_one_trigger()

    def test_fully_verified_suite_passes(self):
        self.fixture.suite([flow_detail(7, 101, suite_run_id=901), flow_detail(8, 102, suite_run_id=901)])
        self.run_cli("--suite-id", "5")
        summary, junit = self.reports()
        self.assertEqual(summary["counts"], {"passed": 2, "failed": 0, "error": 0, "skipped": 0})
        self.assertEqual(junit.attrib["tests"], "2")
        self.assert_one_trigger()

    def test_empty_suite_cannot_pass(self):
        self.fixture.suite([])
        self.run_cli("--suite-id", "5", expected=2)
        summary, junit = self.reports()
        self.assertEqual(summary["verdict"], "error")
        self.assertEqual(junit.attrib["errors"], "1")

    def test_suite_passed_aggregate_does_not_hide_unverified_child(self):
        self.fixture.suite([flow_detail(7, 101, suite_run_id=901), flow_detail(8, 102, "not_configured", 901)])
        self.run_cli("--suite-id", "5", expected=1)
        self.assertEqual([f["outcome"] for f in self.reports()[0]["flows"]], ["passed", "failed"])
        self.run_cli("--suite-id", "5", "--allow-no-assertions")
        self.assertEqual(self.reports()[0]["counts"]["skipped"], 1)

    def test_missing_suite_child_is_error_and_available_results_survive(self):
        self.fixture.suite([flow_detail(suite_run_id=901)], expected=2)
        self.run_cli("--suite-id", "5", expected=2)
        summary, junit = self.reports()
        self.assertEqual(summary["flows"][0]["outcome"], "passed")
        self.assertEqual(summary["errors"][0]["code"], "missing_flow_runs")
        self.assertIsNotNone(junit.find("testcase[@classname='brick.runner']/error"))

    def test_one_unreadable_child_does_not_discard_other_results(self):
        self.fixture.suite([flow_detail(7, 101, suite_run_id=901), flow_detail(8, 102, suite_run_id=901)])
        self.fixture.routes[("GET", "/api/brick/flows/runs/101")] = (503, {"message": SECRET})
        self.run_cli("--suite-id", "5", expected=2)
        self.assertEqual([f["outcome"] for f in self.reports()[0]["flows"]], ["error", "passed"])

    def test_incomplete_snapshots_never_pass(self):
        def remove_assertion(detail):
            detail["nodes"][0]["assertions"] = []

        def remove_node(detail):
            detail["nodes"] = []

        def wrong_run(detail):
            detail["run"]["id"] = 999

        def inconsistent_summary(detail):
            detail["run"]["businessStatus"] = "not_configured"

        def wrong_assertion_owner(detail):
            detail["nodes"][0]["assertions"][0]["runNodeId"] = 999

        def unknown_business(detail):
            detail["nodes"][0]["businessStatus"] = None

        for mutate in (remove_assertion, remove_node, wrong_run, inconsistent_summary, wrong_assertion_owner, unknown_business):
            with self.subTest(case=mutate.__name__):
                detail = flow_detail()
                mutate(detail)
                self.fixture.flow(flow_detail())
                self.fixture.set("GET", "/flows/runs/101", detail)
                self.run_cli("--flow-id", "7", expected=2)
                self.assertEqual(self.reports()[0]["verdict"], "error")

    def test_duplicate_suite_entries_and_wrong_suite_owner_are_errors(self):
        detail = flow_detail(suite_run_id=777)
        suite = self.fixture.suite([detail])
        self.run_cli("--suite-id", "5", expected=2)
        suite["flowRuns"].append(copy.deepcopy(suite["flowRuns"][0]))
        suite["suiteRun"]["totalFlows"] = 2
        self.run_cli("--suite-id", "5", expected=2)

    def test_polling_uses_known_run_without_retriggering(self):
        detail = flow_detail()
        self.fixture.flow(detail)
        running = copy.deepcopy(detail)
        running["run"]["status"] = "running"
        payloads = [running, detail]
        self.fixture.routes[("GET", "/api/brick/flows/runs/101")] = lambda: (200, {"success": True, "data": payloads.pop(0)})
        self.run_cli("--flow-id", "7", "--timeout", "4")
        self.assert_one_trigger()
        self.assertEqual(sum(method == "GET" for method, *_ in self.fixture.calls), 2)

    def test_post_timeout_is_bounded_and_not_retried(self):
        def slow():
            time.sleep(2)
            return 200, {"success": True, "data": flow_detail()["run"]}

        self.fixture.routes[("POST", "/api/brick/flows/7/run")] = slow
        start = time.monotonic()
        self.run_cli("--flow-id", "7", "--timeout", "0.2", expected=2)
        self.assertLess(time.monotonic() - start, 1.5)
        self.assert_one_trigger()
        self.assertEqual(self.reports()[0]["errors"][0]["code"], "timeout")

    def test_poll_timeout_retains_known_run_id(self):
        detail = flow_detail()
        self.fixture.flow(detail)
        detail["run"]["status"] = "running"
        self.run_cli("--flow-id", "7", "--timeout", "0.2", expected=2)
        summary, junit = self.reports()
        self.assertEqual(summary["runId"], 101)
        self.assertEqual(summary["flows"][0]["runId"], 101)
        self.assertIsNotNone(junit.find("testcase/error"))
        self.assert_one_trigger()

    def test_http_redirect_api_error_and_invalid_json_are_not_retried(self):
        for status, payload in ((302, {}), (500, {"message": SECRET}), (200, {"success": False, "message": SECRET}), (200, b"not json")):
            with self.subTest(status=status, payload=payload):
                self.fixture.calls.clear()
                self.fixture.routes[("POST", "/api/brick/flows/7/run")] = (status, payload)
                result = self.run_cli("--flow-id", "7", expected=2)
                self.assertEqual(len(self.fixture.calls), 1)
                self.assertEqual(self.reports()[0]["verdict"], "error")
                self.assertNotIn(SECRET, result.stdout + result.stderr)

    def test_unreachable_service_produces_error_reports(self):
        import socket
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
            self.run_cli("--flow-id", "7", "--base-url", "http://127.0.0.1:%s/api/brick" % port, expected=2)
        self.assertEqual(self.reports()[0]["errors"][0]["code"], "connection_error")

    def test_bad_local_configuration_never_triggers_execution(self):
        cases = [("--flow-id", "0"), ("--flow-id", "7", "--suite-id", "5"),
                 ("--flow-id", "7", "--timeout", "nan"), ("--flow-id", "7", "--timeout", "inf"),
                 ("--flow-id", "7", "--base-url", "https://user:password@example.test/api/brick"),
                 ("--flow-id", "7", "--operator", "")]
        for args in cases:
            with self.subTest(args=args):
                self.run_cli(*args, expected=2)
        self.output.mkdir()
        (self.output / "results.xml").mkdir()
        self.run_cli("--flow-id", "7", expected=2)
        self.assertEqual(self.fixture.calls, [])

    def test_old_green_reports_are_replaced_on_new_failure(self):
        self.fixture.flow(flow_detail())
        self.run_cli("--flow-id", "7")
        self.fixture.routes[("POST", "/api/brick/flows/7/run")] = (500, {})
        self.run_cli("--flow-id", "7", expected=2)
        summary, junit = self.reports()
        self.assertEqual(summary["verdict"], "error")
        self.assertNotIn("runId", summary)
        self.assertEqual(junit.attrib["errors"], "1")


if __name__ == "__main__":
    unittest.main()
