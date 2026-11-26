import base64
import contextlib
import http.server
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import central_deployments
from central_deployments import call_central, main, parse_uploads

ENV = {"MAVEN_CENTRAL_USERNAME": "user", "MAVEN_CENTRAL_PASSWORD": "secret"}


def upload_line(deployment_id, publishing_type="USER_MANAGED"):
    return f"Uploaded bundle to Central Portal as {publishing_type}, deployment id: {deployment_id}\n"


class FakeCentral:
    """Answers each status request with the next state scripted for that id, then repeats the last one."""

    def __init__(self, states, delete_status=204, clock=None, seconds_per_call=0):
        self.states = {deployment_id: list(sequence) for deployment_id, sequence in states.items()}
        self.delete_statuses = list(delete_status) if isinstance(delete_status, list) else [delete_status]
        self.clock = clock
        self.seconds_per_call = seconds_per_call
        self.calls = []

    def __call__(self, method, url, token):
        self.calls.append((method, url))
        if self.clock:
            self.clock.sleep(self.seconds_per_call)
        deployment_id = url.rsplit("=", 1)[-1] if method == "POST" else url.rsplit("/", 1)[-1]
        if method == "DELETE":
            return (self.delete_statuses.pop(0) if len(self.delete_statuses) > 1 else self.delete_statuses[0]), ""
        sequence = self.states[deployment_id]
        state = sequence.pop(0) if len(sequence) > 1 else sequence[0]
        if isinstance(state, int):
            return state, ""
        if isinstance(state, tuple):
            return state
        errors = {"pom": ["missing license"]} if state == "FAILED" else {}
        return 200, json.dumps({"deploymentState": state, "errors": errors})


class Clock:
    def __init__(self):
        self.seconds = 0.0

    def now(self):
        return self.seconds

    def sleep(self, seconds):
        self.seconds += seconds


class CentralDeploymentsTest(unittest.TestCase):
    def run_main(self, action, log_text, central, timeout_seconds=None, clock=None):
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as log:
            log.write(log_text)
        self.addCleanup(os.unlink, log.name)
        argv = [action, "--log", log.name]
        if timeout_seconds is not None:
            argv += ["--timeout-seconds", str(timeout_seconds)]
        clock = clock or Clock()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = main(argv, call=central, environ=ENV, now=clock.now, sleep=clock.sleep)
        return code, output.getvalue()

    def test_parse_uploads_separates_user_managed_ids_and_drops_repeats(self):
        log = "noise\n" + upload_line("a") + upload_line("b") + upload_line("a") + upload_line("c", "AUTOMATIC")
        self.assertEqual(parse_uploads(log), (["a", "b"], ["c"]))

    def test_call_central_sends_the_bearer_token_and_returns_error_bodies(self):
        seen = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_DELETE(self):
                seen["authorization"] = self.headers["Authorization"]
                body = b"not droppable"
                self.send_response(400)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        token = central_deployments.bearer_token("user", "secret")
        status, body = call_central("DELETE", f"http://127.0.0.1:{server.server_port}/deployment/a", token)
        self.assertEqual((status, body), (400, "not droppable"))
        self.assertEqual(seen["authorization"], "Bearer " + base64.b64encode(b"user:secret").decode())

    def test_wait_passes_once_every_deployment_is_validated(self):
        central = FakeCentral({"a": ["PENDING", "VALIDATING", "VALIDATED"], "b": [503, 404, "VALIDATED"]})
        code, output = self.run_main("wait", upload_line("a") + upload_line("b"), central)
        self.assertEqual(code, 0)
        self.assertIn("validated deployment a", output)
        self.assertIn("validated deployment b", output)

    def test_wait_fails_with_centrals_errors_for_a_failed_deployment(self):
        central = FakeCentral({"a": ["VALIDATED"], "b": ["VALIDATING", "FAILED"]})
        code, output = self.run_main("wait", upload_line("a") + upload_line("b"), central)
        self.assertEqual(code, 1)
        self.assertIn("::error::Central reports FAILED for deployment b", output)
        self.assertIn("missing license", output)

    def test_wait_fails_at_the_deadline_naming_the_last_state(self):
        central = FakeCentral({"a": ["VALIDATING"]})
        code, output = self.run_main("wait", upload_line("a"), central, timeout_seconds=60)
        self.assertEqual(code, 1)
        self.assertIn("::error::Central was still at VALIDATING for deployment a at the deadline", output)

    def test_wait_stops_polling_when_central_rejects_the_token(self):
        central = FakeCentral({"a": [401]})
        code, output = self.run_main("wait", upload_line("a"), central)
        self.assertEqual(code, 1)
        self.assertEqual(len(central.calls), 1)
        self.assertIn("::error::Central rejected the token (HTTP 401)", output)

    def test_wait_fails_when_the_log_names_no_user_managed_deployment(self):
        code, output = self.run_main("wait", "BUILD SUCCESSFUL\n", FakeCentral({}))
        self.assertEqual(code, 1)
        self.assertIn("names no USER_MANAGED deployment", output)

    def test_an_automatic_upload_fails_both_actions(self):
        log = upload_line("a") + upload_line("b", "AUTOMATIC")
        for action in ("wait", "drop"):
            with self.subTest(action=action):
                central = FakeCentral({"a": ["VALIDATED"], "b": ["FAILED"]})
                code, output = self.run_main(action, log, central)
                self.assertEqual(code, 1)
                self.assertIn("::error::deployment b was not uploaded as USER_MANAGED", output)
                if action == "drop":
                    self.assertIn("dropped deployment b", output)

    def test_drop_deletes_each_deployment_as_soon_as_central_settles_it(self):
        central = FakeCentral({"a": ["VALIDATING", "VALIDATED"], "b": ["FAILED"]})
        code, output = self.run_main("drop", upload_line("a") + upload_line("b"), central)
        self.assertEqual(code, 0)
        self.assertEqual(
            [(method, url.rsplit("/", 1)[-1].rsplit("=", 1)[-1]) for method, url in central.calls],
            [("POST", "a"), ("POST", "b"), ("DELETE", "b"), ("POST", "a"), ("DELETE", "a")],
        )
        self.assertIn("dropped deployment a", output)

    def test_drop_names_a_deployment_still_validating_at_the_deadline(self):
        central = FakeCentral({"a": ["VALIDATED"], "b": ["VALIDATING"]})
        code, output = self.run_main("drop", upload_line("a") + upload_line("b"), central, timeout_seconds=60)
        self.assertEqual(code, 1)
        self.assertIn("dropped deployment a", output)
        self.assertIn("::error::deployment b was still VALIDATING at the deadline", output)

    def test_drop_retries_a_delete_that_central_could_not_serve(self):
        central = FakeCentral({"a": ["VALIDATED"]}, delete_status=[503, 204])
        code, output = self.run_main("drop", upload_line("a"), central)
        self.assertEqual(code, 0)
        self.assertEqual([method for method, _ in central.calls].count("DELETE"), 2)

    def test_drop_keeps_polling_through_a_status_reply_that_is_not_json(self):
        central = FakeCentral({"a": [(200, "<html>gateway</html>"), (200, "[]"), "VALIDATED"]})
        code, output = self.run_main("drop", upload_line("a"), central)
        self.assertEqual(code, 0)
        self.assertEqual(central.calls[-1][0], "DELETE")
        self.assertIn("dropped deployment a", output)

    def test_drop_fails_when_central_refuses_to_delete(self):
        central = FakeCentral({"a": ["VALIDATED"]}, delete_status=400)
        code, output = self.run_main("drop", upload_line("a"), central)
        self.assertEqual(code, 1)
        self.assertIn("::error::could not drop deployment a (HTTP 400)", output)

    def test_drop_retries_an_unknown_deployment_until_the_deadline(self):
        central = FakeCentral({"a": [404, "VALIDATED"], "b": [404]})
        code, output = self.run_main("drop", upload_line("a") + upload_line("b"), central)
        self.assertEqual(code, 0)
        self.assertIn("dropped deployment a", output)
        self.assertIn("::warning::Central never knew deployment b", output)
        self.assertNotIn(("DELETE", "b"), [(method, url.rsplit("/", 1)[-1]) for method, url in central.calls])

    def test_drop_stops_requesting_at_the_deadline_when_central_is_slow(self):
        clock = Clock()
        ids = [f"id{n}" for n in range(10)]
        central = FakeCentral({i: ["VALIDATING"] for i in ids}, clock=clock, seconds_per_call=30)
        code, _ = self.run_main("drop", "".join(upload_line(i) for i in ids), central, clock=clock)
        self.assertEqual(code, 1)
        self.assertLessEqual(clock.seconds, 230)

    def test_drop_with_no_upload_has_nothing_to_do(self):
        central = FakeCentral({})
        code, output = self.run_main("drop", "upload never started\n", central)
        self.assertEqual(code, 0)
        self.assertEqual(central.calls, [])
        self.assertIn("::warning::the log names no deployment", output)


if __name__ == "__main__":
    unittest.main()
