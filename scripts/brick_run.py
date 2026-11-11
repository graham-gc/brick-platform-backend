#!/usr/bin/env python3
"""Run saved Brick tests and produce CI verdicts using only Python's standard library."""

import argparse
import json
import math
import os
from pathlib import Path
import queue
import socket
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


class RunError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def require(condition, message="Brick returned incomplete or inconsistent run records."):
    if not condition:
        raise RunError("invalid_result", message)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def positive_id(value):
    try:
        result = int(value)
        if result > 0:
            return result
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("must be a positive integer")


def positive_seconds(value):
    try:
        result = float(value)
        if math.isfinite(result) and result > 0:
            return result
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("must be a finite number greater than zero")


def service_url(value):
    try:
        parsed = urllib.parse.urlsplit(value)
        valid = (parsed.scheme in ("http", "https") and parsed.hostname and parsed.port != 0
                 and not parsed.username and not parsed.password
                 and not parsed.query and not parsed.fragment
                 and not any(c.isspace() or ord(c) < 32 for c in value))
    except ValueError:
        valid = False
    if not valid:
        raise argparse.ArgumentTypeError("use an HTTP(S) URL without credentials, query or fragment")
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class BrickClient:
    def __init__(self, base_url, timeout, token):
        self.base_url = base_url
        self.deadline = time.monotonic() + timeout
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def remaining(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise RunError("timeout", "The wait limit expired. Brick may still be executing; no run was retried or cancelled.")
        return remaining

    def request(self, method, path, body=None):
        remaining = self.remaining()
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        result = queue.Queue(maxsize=1)

        def send():
            try:
                with self.opener.open(request, timeout=remaining) as response:
                    raw = response.read(64 * 1024 * 1024 + 1)
                    require(len(raw) <= 64 * 1024 * 1024, "Brick's response exceeded the 64 MiB limit.")
                envelope = json.loads(raw)
                require(isinstance(envelope, dict), "Brick returned an invalid API response.")
                if envelope.get("success") is not True:
                    raise RunError("api_error", "Brick rejected the API request. Check the service and selected test ID.")
                require(isinstance(envelope.get("data"), dict), "Brick returned no run data.")
                result.put(envelope["data"])
            except urllib.error.HTTPError as exc:
                exc.close()
                result.put(RunError("http_error", "Brick API returned HTTP %s. Check the service address and access configuration." % exc.code))
            except (socket.timeout, TimeoutError):
                result.put(RunError("timeout", "The wait limit expired. Brick may still be executing; no run was retried or cancelled."))
            except (urllib.error.URLError, OSError):
                result.put(RunError("connection_error", "Could not complete the API request. Brick may have accepted the run; it was not retried."))
            except (ValueError, UnicodeError):
                result.put(RunError("invalid_result", "Brick returned unreadable JSON."))
            except RunError as exc:
                result.put(exc)
            except Exception:
                result.put(RunError("invalid_result", "Could not read Brick's response."))

        # Bound the entire exchange, including slow response bodies, by one wall-clock deadline.
        # A timed-out execution request is deliberately never resent.
        threading.Thread(target=send, daemon=True).start()
        try:
            response = result.get(timeout=self.remaining())
        except queue.Empty:
            raise RunError("timeout", "The wait limit expired. Brick may still be executing; no run was retried or cancelled.")
        if isinstance(response, RunError):
            raise response
        return response

    def detail(self, path, key, run_id, target_key, target_id):
        while True:
            detail = self.request("GET", path)
            run = detail.get(key)
            require(isinstance(run, dict))
            require(run.get("id") == run_id and run.get(target_key) == target_id)
            require(run.get("status") in ("running", "success", "failed"))
            if run["status"] != "running":
                return detail
            time.sleep(min(0.5, self.remaining()))


def flow_result(run_id, flow_id, web_url):
    result = {"flowId": flow_id, "runId": run_id, "outcome": "error", "issues": []}
    if web_url and run_id:
        result["url"] = "%s/runs/%s" % (web_url, run_id)
    return result


def evaluate_flow(detail, result, allow_no_assertions, suite_run_id=None):
    run, nodes = detail.get("run"), detail.get("nodes")
    require(isinstance(run, dict) and isinstance(nodes, list))
    require(run.get("id") == result["runId"] and run.get("flowId") == result["flowId"])
    if suite_run_id is not None:
        require(run.get("suiteRunId") == suite_run_id)
    status, business = run.get("status"), run.get("businessStatus")
    require(status in ("success", "failed"))
    require(business in ("passed", "failed", "not_configured", "not_evaluated"))
    result.update(status=status, businessStatus=business)
    if integer(run.get("durationMs")):
        result["durationMs"] = run["durationMs"]
    require(nodes or status == "failed", "The successful flow has no node results.")

    seen_nodes, seen_snapshots, seen_assertions = set(), set(), set()
    assertions_total = 0
    observed_business = []
    execution_failed = status == "failed"
    for node in nodes:
        require(isinstance(node, dict))
        node_id, snapshot_id = node.get("nodeId"), node.get("id")
        require(integer(node_id, 1) and integer(snapshot_id, 1)
                and node_id not in seen_nodes and snapshot_id not in seen_snapshots
                and node.get("runId") == result["runId"])
        seen_nodes.add(node_id)
        seen_snapshots.add(snapshot_id)
        node_status, node_business = node.get("status"), node.get("businessStatus")
        require(node_status in ("success", "failed", "blocked"))
        require(node_business in ("passed", "failed", "not_configured", "not_evaluated"))
        observed_business.append(node_business)
        assertions = node.get("assertions")
        require(isinstance(assertions, list))
        counts = [node.get(k) for k in ("assertionTotalCount", "assertionPassedCount", "assertionFailedCount")]
        require(all(integer(c) for c in counts))
        total, passed, failed = counts
        require(total == len(assertions) and passed + failed == total)
        failed_ids = []
        for assertion in assertions:
            require(isinstance(assertion, dict))
            assertion_id = assertion.get("id")
            require(integer(assertion_id, 1) and assertion_id not in seen_assertions
                    and assertion.get("runNodeId") == snapshot_id
                    and assertion.get("status") in ("passed", "failed"))
            seen_assertions.add(assertion_id)
            if assertion["status"] == "failed":
                failed_ids.append(assertion_id)
        require(len(failed_ids) == failed)
        if node_business == "passed":
            require(total > 0 and failed == 0 and node_status == "success")
        elif node_business == "failed":
            require(failed > 0 and node_status == "success")
        elif node_business == "not_configured":
            require(total == 0 and node_status == "success")
        assertions_total += total
        if node_status != "success":
            execution_failed = True
            result["issues"].append({"nodeId": node_id, "code": "node_" + node_status})
        if failed_ids:
            result["issues"].append({"nodeId": node_id, "code": "assertion_failed", "assertionSnapshotIds": failed_ids})
        if node_business == "not_evaluated" and node_status == "success":
            result["issues"].append({"nodeId": node_id, "code": "assertions_not_evaluated"})

    result["assertionCount"] = assertions_total
    if execution_failed or "failed" in observed_business:
        result.update(outcome="failed", reason="execution_failed" if execution_failed else "assertion_failed")
    elif "not_evaluated" in observed_business or business == "not_evaluated":
        result.update(outcome="error", reason="assertions_not_evaluated")
    else:
        expected = "passed" if assertions_total else "not_configured"
        require(business == expected, "The flow summary disagrees with its assertion records.")
        if assertions_total:
            result.update(outcome="passed", reason="assertions_passed")
        elif allow_no_assertions:
            result.update(outcome="skipped", reason="no_assertions_allowed")
        else:
            result.update(outcome="failed", reason="no_assertions")


def record_error(summary, exc):
    summary["errors"].append({"code": exc.code, "message": str(exc)})


def collect_flow(client, summary, run_id, flow_id, args, suite_run_id=None):
    result = flow_result(run_id, flow_id, args.web_base_url)
    summary["flows"].append(result)
    try:
        detail = client.detail("/flows/runs/%s" % run_id, "run", run_id, "flowId", flow_id)
        evaluate_flow(detail, result, args.allow_no_assertions, suite_run_id)
    except RunError as exc:
        result.update(outcome="error", reason=exc.code)
        result["issues"].append({"code": exc.code, "message": str(exc)})


def execute(args, summary):
    client = BrickClient(args.base_url, args.timeout, os.environ.get("BRICK_API_TOKEN", ""))
    kind, target_id = summary["target"]["type"], summary["target"]["id"]
    if kind == "flow":
        run = client.request("POST", "/flows/%s/run" % target_id, {"operator": args.operator, "runType": 0})
    else:
        path = "/test-suites/%s/run?%s" % (target_id, urllib.parse.urlencode({"operator": args.operator}))
        run = client.request("POST", path)
    run_id = run.get("id")
    require(integer(run_id, 1), "Brick did not return a run ID; the execution request will not be retried.")
    summary["runId"] = run_id
    if kind == "flow":
        collect_flow(client, summary, run_id, target_id, args)
        return

    detail = client.detail("/test-suites/runs/%s" % run_id, "suiteRun", run_id, "suiteId", target_id)
    suite, children = detail["suiteRun"], detail.get("flowRuns")
    require(integer(suite.get("totalFlows"), 1) and isinstance(children, list))
    summary["expectedFlows"] = suite["totalFlows"]
    if len(children) != suite["totalFlows"]:
        record_error(summary, RunError("missing_flow_runs", "The suite's flow records do not match its expected flow count."))
    seen_runs, seen_flows = set(), set()
    for child in children:
        if not (isinstance(child, dict) and integer(child.get("id"), 1) and integer(child.get("flowId"), 1)):
            record_error(summary, RunError("invalid_result", "The suite contains an unreadable flow reference."))
            continue
        if child["id"] in seen_runs or child["flowId"] in seen_flows:
            record_error(summary, RunError("invalid_result", "The suite contains duplicate flow results."))
            continue
        seen_runs.add(child["id"])
        seen_flows.add(child["flowId"])
        collect_flow(client, summary, child["id"], child["flowId"], args, run_id)

    # A suite aggregate can say 'passed' even when a child has no assertions.
    # Validate its counters, but make the gate decision from individual snapshots.
    flows = summary["flows"]
    counter_fields = {"passed": "businessPassedFlows", "failed": "businessFailedFlows",
                      "not_configured": "businessNotConfiguredFlows", "not_evaluated": "businessNotEvaluatedFlows"}
    for status, field in counter_fields.items():
        expected = sum(f.get("businessStatus") == status for f in flows)
        require(integer(suite.get(field)) and suite[field] == expected)
    successes = sum(f.get("status") == "success" for f in flows)
    require(suite.get("successFlows") == successes
            and suite.get("failedFlows") == len(flows) - successes)
    require(suite["status"] == ("success" if successes == len(flows) else "failed"))
    expected_business = next((s for s in ("failed", "not_evaluated", "passed")
                              if any(f.get("businessStatus") == s for f in flows)), "not_configured")
    require(suite.get("businessStatus") == expected_business)


def finalize(summary):
    counts = {outcome: sum(f["outcome"] == outcome for f in summary["flows"])
              for outcome in ("passed", "failed", "error", "skipped")}
    summary["counts"] = counts
    if summary["errors"] or counts["error"] or not summary["flows"]:
        summary.update(verdict="error", exitCode=2)
    elif counts["failed"]:
        summary.update(verdict="failed", exitCode=1)
    else:
        summary.update(verdict="passed", exitCode=0)
    summary["finishedAt"] = datetime.now(timezone.utc).isoformat()


def atomic_write(path, content):
    temp_name = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=str(path.parent), delete=False) as output:
            temp_name = output.name
            output.write(content)
        os.replace(temp_name, path)
    finally:
        if temp_name and os.path.exists(temp_name):
            os.unlink(temp_name)


def write_reports(directory, summary):
    directory.mkdir(parents=True, exist_ok=True)
    root = ET.Element("testsuite", name="Brick regression", tests=str(len(summary["flows"]) + len(summary["errors"])),
                      failures=str(summary["counts"]["failed"]), errors=str(summary["counts"]["error"] + len(summary["errors"])),
                      skipped=str(summary["counts"]["skipped"]))
    properties = ET.SubElement(root, "properties")
    for name, value in (("targetType", summary["target"]["type"]), ("targetId", summary["target"]["id"]),
                        ("runId", summary.get("runId", "")), ("allowNoAssertions", summary["policy"]["allowNoAssertions"])):
        ET.SubElement(properties, "property", name=name, value=str(value))
    for flow in summary["flows"]:
        case = ET.SubElement(root, "testcase", classname="brick.flow.%s" % flow["flowId"],
                             name="Flow %s (run %s)" % (flow["flowId"], flow["runId"]),
                             time=str(flow.get("durationMs", 0) / 1000))
        if flow["outcome"] != "passed":
            tag = {"failed": "failure", "error": "error", "skipped": "skipped"}[flow["outcome"]]
            ET.SubElement(case, tag, message=flow["reason"]).text = json.dumps(flow["issues"], ensure_ascii=False)
        ET.SubElement(case, "system-out").text = json.dumps({k: flow[k] for k in ("runId", "flowId", "url", "reason") if k in flow})
    for error in summary["errors"]:
        case = ET.SubElement(root, "testcase", classname="brick.runner", name=error["code"])
        ET.SubElement(case, "error", message=error["code"]).text = error["message"]
    atomic_write(directory / "results.xml", ET.tostring(root, encoding="unicode", xml_declaration=True))
    atomic_write(directory / "summary.json", json.dumps(summary, ensure_ascii=False, indent=2) + "\n")


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", type=service_url, default=os.environ.get("BRICK_API_BASE_URL", "http://localhost:8080/api/brick"),
                        help="complete Brick API prefix (or BRICK_API_BASE_URL)")
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--flow-id", type=positive_id)
    selection.add_argument("--suite-id", type=positive_id)
    parser.add_argument("--operator", default="ci")
    parser.add_argument("--report-dir", type=Path, default=Path("brick-reports"))
    parser.add_argument("--web-base-url", type=service_url, help="Brick frontend URL for run links")
    parser.add_argument("--timeout", type=positive_seconds, default=900, help="total wait limit in seconds (default: 900)")
    parser.add_argument("--allow-no-assertions", action="store_true", help="allow connectivity-only flows; mark them skipped in JUnit")
    args = parser.parse_args(argv)
    if not args.operator.strip() or any(ord(c) < 32 for c in args.operator):
        parser.error("--operator must be a nonempty single-line value")
    if any(ord(c) < 32 or ord(c) > 126 for c in os.environ.get("BRICK_API_TOKEN", "")):
        parser.error("BRICK_API_TOKEN must contain only printable ASCII characters")
    return args


def main(argv=None):
    args = parse_args(argv)
    summary = {"schemaVersion": 1, "startedAt": datetime.now(timezone.utc).isoformat(),
               "target": {"type": "flow" if args.flow_id else "suite", "id": args.flow_id or args.suite_id},
               "policy": {"allowNoAssertions": args.allow_no_assertions}, "flows": [], "errors": []}
    try:
        # Replace stale reports and prove the output location works BEFORE triggering a run.
        record_error(summary, RunError("incomplete", "The runner has not completed. No passing result is available."))
        finalize(summary)
        write_reports(args.report_dir, summary)
    except OSError:
        print("Cannot write the report directory. No run was triggered.", file=sys.stderr)
        return 2
    summary["errors"].clear()
    try:
        execute(args, summary)
    except RunError as exc:
        record_error(summary, exc)
    except KeyboardInterrupt:
        record_error(summary, RunError("interrupted", "The runner was interrupted. Brick may still be executing; no run was cancelled."))
    finalize(summary)
    try:
        write_reports(args.report_dir, summary)
    except OSError:
        print("Could not save the final reports. The gate result is an error.", file=sys.stderr)
        return 2
    print("Brick regression: %s (exit %s); %s" % (summary["verdict"], summary["exitCode"], args.report_dir))
    for flow in summary["flows"]:
        print("  Flow %s / run %s: %s [%s]%s" % (flow["flowId"], flow["runId"], flow["outcome"], flow["reason"],
                                                " " + flow["url"] if flow.get("url") else ""))
    for error in summary["errors"]:
        print("  %s: %s" % (error["code"], error["message"]), file=sys.stderr)
    return summary["exitCode"]


if __name__ == "__main__":
    sys.exit(main())
