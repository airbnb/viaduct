#!/usr/bin/env python3
"""Waits for, then drops, the Maven Central deployments a release rehearsal uploaded.

Reads the deployment ids from the log of `./gradlew publishToMavenCentral` run
with CENTRAL_REHEARSAL=true. That run uploads every bundle as USER_MANAGED, so
Central validates it but publishes it only if someone presses Publish.

`wait` polls each deployment until Central has validated it or failed it.
`drop` deletes each deployment as soon as Central has validated or failed it,
so a short cleanup window still drops every deployment that is ready.

Reads the Central token from MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD.

Usage: central_deployments.py wait --log publish.log [--timeout-seconds 1800]
       central_deployments.py drop --log publish.log [--timeout-seconds 200]

Exit codes:
  0 - wait: every deployment is VALIDATED; drop: every deployment is gone
  1 - wait: the log names no deployment, an upload was not USER_MANAGED, or a
      deployment failed or is still being validated at the deadline;
      drop: an upload was not USER_MANAGED, or a deployment could not be dropped
"""

import argparse
import base64
import http.client
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://central.sonatype.com/api/v1/publisher"
DEPLOYMENTS_PAGE = "https://central.sonatype.com/publishing/deployments"
UPLOADED = re.compile(r"Uploaded bundle to Central Portal as (\w+), deployment id: (\S+)")
DROPPABLE_STATES = {"VALIDATED", "FAILED"}
PUBLISHING_STATES = {"PUBLISHING", "PUBLISHED"}
SETTLED_STATES = DROPPABLE_STATES | PUBLISHING_STATES
NOT_FOUND = "NOT_FOUND"


class TokenRejected(Exception):
    pass


def parse_uploads(log_text):
    """Returns the USER_MANAGED deployment ids and the ids uploaded any other way, each in log order."""
    user_managed, other = [], []
    for publishing_type, deployment_id in UPLOADED.findall(log_text):
        ids = user_managed if publishing_type == "USER_MANAGED" else other
        if deployment_id not in ids:
            ids.append(deployment_id)
    return user_managed, other


def bearer_token(username, password):
    return base64.b64encode(f"{username}:{password}".encode()).decode()


def call_central(method, url, token):
    request = urllib.request.Request(url, method=method, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode(errors="replace")
    except urllib.error.HTTPError as error:
        with error:
            try:
                return error.code, error.read().decode(errors="replace")
            except (OSError, http.client.HTTPException):
                return error.code, ""
    except (OSError, http.client.HTTPException):
        return 0, ""


def parse_deployment(body):
    try:
        deployment = json.loads(body)
    except ValueError:
        return None
    return deployment if isinstance(deployment, dict) else None


def is_retryable(status):
    return status == 0 or status == 429 or status >= 500


def poll(deployment_id, call):
    """Returns (state, errors), or None when Central's answer carries no state yet."""
    status, body = call("POST", f"{API}/status?id={urllib.parse.quote(deployment_id, safe='')}")
    if status in (401, 403):
        raise TokenRejected(status)
    if status == 404:
        return NOT_FOUND, None
    deployment = parse_deployment(body) if status == 200 else None
    if deployment is None:
        return None
    return deployment.get("deploymentState", "UNKNOWN"), deployment.get("errors")


def wait_until_settled(ids, call, timeout_seconds, sleep_seconds=15, now=time.monotonic, sleep=time.sleep):
    """Returns each id's last (state, errors); an id still being validated keeps its last state."""
    deadline = now() + timeout_seconds
    results = {deployment_id: ("UNKNOWN", None) for deployment_id in ids}
    pending = list(ids)
    while True:
        for deployment_id in list(pending):
            result = poll(deployment_id, call)
            if result is None:
                continue
            results[deployment_id] = result
            if result[0] in SETTLED_STATES:
                pending.remove(deployment_id)
        remaining = deadline - now()
        if not pending or remaining <= 0:
            return results, pending
        sleep(min(sleep_seconds, remaining))


def report_wait(results, pending):
    failed = False
    for deployment_id, (state, errors) in results.items():
        if state == "VALIDATED":
            print(f"✅ Central validated deployment {deployment_id}")
            continue
        failed = True
        if deployment_id in pending:
            print(f"::error::Central was still at {state} for deployment {deployment_id} at the deadline")
        else:
            print(f"::error::Central reports {state} for deployment {deployment_id}")
        if errors:
            print(json.dumps(errors, indent=2))
    return 1 if failed else 0


def drop(ids, call, timeout_seconds, sleep_seconds=15, now=time.monotonic, sleep=time.sleep):
    """Deletes each deployment once Central lets it; returns 1 if any is left or is being published."""
    deadline = now() + timeout_seconds
    states = {deployment_id: "UNKNOWN" for deployment_id in ids}
    pending = list(ids)
    failed = False
    while True:
        for deployment_id in list(pending):
            if now() >= deadline:
                break
            result = poll(deployment_id, call)
            if result is not None:
                states[deployment_id] = result[0]
            state = states[deployment_id]
            if state in PUBLISHING_STATES:
                failed = True
                pending.remove(deployment_id)
                print(f"::error::Central reports {state} for deployment {deployment_id}, so it cannot be dropped")
                continue
            if state not in DROPPABLE_STATES:
                continue
            status, body = call("DELETE", f"{API}/deployment/{urllib.parse.quote(deployment_id, safe='')}")
            if is_retryable(status):
                continue
            pending.remove(deployment_id)
            if status == 204:
                print(f"✅ dropped deployment {deployment_id}")
            elif status == 404:
                print(f"::warning::Central does not know deployment {deployment_id}; check {DEPLOYMENTS_PAGE}")
            else:
                failed = True
                print(f"::error::could not drop deployment {deployment_id} (HTTP {status});"
                      f" drop it at {DEPLOYMENTS_PAGE}")
                if body:
                    print(body)
        remaining = deadline - now()
        if not pending or remaining <= 0:
            break
        sleep(min(sleep_seconds, remaining))
    for deployment_id in pending:
        if states[deployment_id] == NOT_FOUND:
            print(f"::warning::Central never knew deployment {deployment_id}; check {DEPLOYMENTS_PAGE}")
            continue
        failed = True
        print(f"::error::deployment {deployment_id} was still {states[deployment_id]} at the deadline;"
              f" drop it at {DEPLOYMENTS_PAGE}")
    return 1 if failed else 0


def main(argv=None, call=call_central, environ=os.environ, now=time.monotonic, sleep=time.sleep):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("action", choices=["wait", "drop"])
    parser.add_argument("--log", required=True, help="log of the publishToMavenCentral run")
    parser.add_argument("--timeout-seconds", type=int, help="default: 1800 for wait, 200 for drop")
    args = parser.parse_args(argv)

    with open(args.log, encoding="utf-8", errors="replace") as log:
        user_managed, other = parse_uploads(log.read())
    for deployment_id in other:
        print(f"::error::deployment {deployment_id} was not uploaded as USER_MANAGED, so Central may publish it")

    token = bearer_token(environ["MAVEN_CENTRAL_USERNAME"], environ["MAVEN_CENTRAL_PASSWORD"])

    def call_with_token(method, url):
        return call(method, url, token)

    if args.action == "drop":
        if not user_managed and not other:
            print(f"::warning::the log names no deployment; if the upload started, check {DEPLOYMENTS_PAGE}")
            return 0
        timeout_seconds = 200 if args.timeout_seconds is None else args.timeout_seconds
        try:
            code = drop(user_managed + other, call_with_token, timeout_seconds, now=now, sleep=sleep)
        except TokenRejected as rejected:
            print(f"::error::Central rejected the token (HTTP {rejected}); drop every deployment at {DEPLOYMENTS_PAGE}")
            return 1
        return 1 if other else code

    if not user_managed:
        print("::error::the log names no USER_MANAGED deployment, so Central validated nothing")
        return 1
    print(f"Waiting for Central to validate {len(user_managed)} deployments")
    timeout_seconds = 1800 if args.timeout_seconds is None else args.timeout_seconds
    try:
        results, pending = wait_until_settled(user_managed, call_with_token, timeout_seconds, now=now, sleep=sleep)
    except TokenRejected as rejected:
        print(f"::error::Central rejected the token (HTTP {rejected})")
        return 1
    code = report_wait(results, pending)
    return 1 if other else code


if __name__ == "__main__":
    sys.exit(main())
