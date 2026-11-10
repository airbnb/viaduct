"""Render successful, checker-free resolution recordings using Python 3's standard library.

Run through the runtime2 animate Gradle task, or pass --recordings DIR --test CLASS.METHOD
--output DIR. Repeat --test to build several animations and refresh their shared gallery.
Replaces each test's animation; no network access.
"""

import argparse
from datetime import datetime, timezone
from hashlib import sha256
from html import escape
import json
from pathlib import Path
import re
import shutil


SOURCE = Path(__file__).parent
RUNTIME2 = SOURCE.parents[1]
MAIN_SOURCE = "src/main/kotlin/viaduct/engine/runtime2/"
CODE_POINTERS = {
    "root": ("Create the root OER and orchestration task", MAIN_SOURCE + "resolution/Resolver.kt", (("val result: ObjectEngineResult =", "dispatcher.dispatchOrchestration(orchestration)"),)),
    "object": ("Allocate the returned object's OER", MAIN_SOURCE + "resolution/framework/SharedPassiveValueResolutionLogic.kt", (("val target = createObjectResult(source.schemaType(operation.world.schema), constructionDemand)", "                target\n"),)),
    "prepare": ("Prepare orchestration before passive descent", MAIN_SOURCE + "resolution/framework/SharedPassiveValueResolutionLogic.kt", (("val orchestration = createOrchestrationTask(occurrence, source, constructionDemand, creator)", "operation.dispatcher.dispatchOrchestration(orchestration)"),)),
    "closure": ("Close object and Query construction demand", MAIN_SOURCE + "resolution/OrchestrationTask.kt", (("val closedConstructionDemand =", "initialDemand = OrchestrationConstructionDemand(constructionDemand, Demand.EMPTY),"),)),
    "bindings": ("Declare variable bindings", MAIN_SOURCE + "resolution/OrchestrationTask.kt", (("private fun declareBindings()", "is VariableDefinition.FromField -> Unit"),)),
    "reserve": ("Reserve cells for demanded fields", MAIN_SOURCE + "resolution/FieldCheckerTask.kt", (("fun prepareAll(orchestrationTask: OrchestrationTask)", "if (checkerOccurrence == null) return PreparedFieldCheckerSlot(cell, null)"),)),
    "query-oer": ("Create the distinct auxiliary Query OER", MAIN_SOURCE + "resolution/OrchestrationTask.kt", (("val emptyQuerySource =", "val queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult)"),)),
    "field-task": ("Create the field-resolver task", MAIN_SOURCE + "resolution/FieldResolverTask.kt", (("task = FieldResolverTask(this)", "operation.resolutionObserver.onTaskPrepared?.invoke(task)"),)),
    "claim": ("Claim responsibility for a field value", MAIN_SOURCE + "resolution/FieldResolverTask.kt", (("val objectKey = sourceOccurrence.selection.key", "publicationCell.value.claim()"),)),
    "dispatch": ("Prepare and dispatch active field work", MAIN_SOURCE + "resolution/OrchestrationTask.kt", (("override fun prepareAndDispatchFieldWork()", "(fieldPublications + conditionedPassivePublications).forEach(operation.dispatcher::dispatchFieldResolver)"),)),
    "freeze": ("Freeze both OER field sets", MAIN_SOURCE + "resolution/CoroutineOrchestrationTaskBase.kt", (("            prepareAndDispatchFieldWork()\n", "queryOER.occurrence.target.freeze()"),)),
    "successor": ("Compute successor demand", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("val invocationDemand: SelectionForest =", "                )\n            }"),)),
    "inactive": ("Evaluate inclusion and stop when inactive", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("val activated = activatePublication()", "if (!activated) return"), ("val activated =\n", "return activated"))),
    "arguments": ("Resolve argument values", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("val groundedArguments =\n            selection.key.fetchGroundedArguments", "val resolverArguments = groundedArguments as Arguments.Resolved"),)),
    "inputs": ("Read the required object and Query inputs", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("val objectMaterializationSelections =", "val queryValue = materializeQueryFragment(fieldResolverOccurrence, queryMaterializationSelections)"),)),
    "await-inputs": ("Wait for required input values", MAIN_SOURCE + "resolution/framework/MaterializeResult.kt", (("val valuePromise = value", "return attempt"),)),
    "invoke": ("Invoke the field resolver with prepared inputs", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("return fieldResolverOccurrence.resolver.invoke(", "additionalContextForResolver = publication.oerOccurrence.additionalContextForResolver,"),)),
    "object-input": ("Materialize only the required object selections", MAIN_SOURCE + "resolution/MaterializeResolverInput.kt", (("private suspend fun ObjectEngineResult.materializeSelectedObject(", "            fields = selectedValues,\n        )"),)),
    "query-input": ("Materialize this owner's Query projection", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("private suspend fun materializeQueryFragment(", "resultPath = emptyList(),"),)),
    "passive": ("Resolve passive values and publish the result", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("val passiveValue: EngineResult? =", "publication.publicationCell.value.complete(passiveValue)"),)),
    "passive-fields": ("Complete available passive fields before dispatch", MAIN_SOURCE + "resolution/framework/SharedPassiveValueResolutionLogic.kt", (("val orchestration = createOrchestrationTask(occurrence, source, constructionDemand, creator)", "operation.dispatcher.dispatchOrchestration(orchestration)"),)),
    "publish": ("Complete the destination cell", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("publication.publicationCell.value.complete(passiveValue)", "publication.publicationCell.value.complete(passiveValue)"),)),
    "completion": ("Wait for structured resolution to finish", MAIN_SOURCE + "resolution/Resolver.kt", (("return withTimeout(timeoutMillis)", "                ).root\n            }"),)),
    "reference-tail": ("Follow references in the same publication", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("var authoritativeNodeIdentity: NodeReferenceIdentity? = null", "fieldValue =\n            fieldValue.withAuthoritativeNodeId("),)),
    "reference-invoke": ("Prepare and invoke a reference target", MAIN_SOURCE + "resolution/FieldResolutionLogic.kt", (("private suspend fun invokeRootFieldResolver(", "executionContext = fieldResolverTask,"),)),
    "reference-query": ("Resolve a reference target's independent Query input", MAIN_SOURCE + "resolution/FieldResolverTask.kt", (("private suspend fun FieldValueResolver.resolveQueryFragment(", "resultPath = emptyList(),"),)),
    "node-identity": ("Preserve the first Node reference's identity", MAIN_SOURCE + "resolution/framework/WithAuthoritativeNodeId.kt", (("internal fun ResolverOutputData?.withAuthoritativeNodeId(", "if (contextUnchanged && idUnchanged) return this"),)),
}
TOUR = "https://pages.git.musta.ch/alex-ubillus/viaduct-runtime2/"
PROJECTION = "viaduct.engine.runtime2.resolution.ResolverContractTest.omits unselected passive fields from selective resolver outputs"
OBJECT_FRAGMENT = "viaduct.engine.runtime2.resolution.ResolverContractTest.closes and orders transitive sibling resolver demand"
SECONDS = 3
STATUS_FADE = 0.8
PREP_MID = SECONDS / 2
PREP_PAUSE = 0.15
PASSIVE_AT = SECONDS / 6
CLAIM_AT = SECONDS * 5 / 6


def code(value):
    return f"<code>{escape(str(value))}</code>"


def code_pointer(key):
    label = escape("Show code: " + CODE_POINTERS[key][0], quote=True)
    return f' <button type="button" class="code-pointer" data-code-pointer="{key}" aria-haspopup="dialog" aria-label="{label}" title="{label}"><span class="code-pointer-icon" aria-hidden="true"></span></button>'


def code_sources(beats):
    references, files, templates = {}, {}, []
    for key in sorted(set(re.findall(r'data-code-pointer="([^"]+)"', "".join(beats)))):
        title, path, anchors = CODE_POINTERS[key]
        if path not in files:
            source = (RUNTIME2 / path).read_text(encoding="utf-8")
            file_id = "source-" + sha256(path.encode()).hexdigest()[:12]
            files[path] = (file_id, source)
            lines = "".join(
                f'<div class="source-line" data-line="{number}"><button type="button" class="source-line-number" tabindex="-1" aria-label="Select line {number}">{number}</button><code>{escape(line)}</code></div>'
                for number, line in enumerate(source.splitlines(), 1)
            )
            templates.append(f'<template id="{file_id}">{lines}</template>')
        file_id, source = files[path]
        ranges = []
        for start, end in anchors:
            if source.count(start) != 1:
                raise ValueError(f"Code pointer {key}: expected one start anchor in {path}: {start!r}")
            first = source.index(start)
            try:
                last = source.index(end, first) + len(end)
            except ValueError:
                raise ValueError(f"Code pointer {key}: missing end anchor in {path}: {end!r}") from None
            ranges.append([source.count("\n", 0, first) + 1, source.count("\n", 0, last) + 1])
        references[key] = {"title": title, "path": path, "file": file_id, "ranges": ranges}
    metadata = json.dumps(references).replace("<", "\\u003c")
    return f'''<script type="application/json" id="code-references">{metadata}</script>{"".join(templates)}
<dialog class="source-dialog" aria-labelledby="source-title">
<header><div><h2 id="source-title"></h2><p class="source-path"></p></div><button type="button" class="source-close" aria-label="Close source code">Close ×</button></header>
<div class="source-tools"><span class="source-selection" aria-live="polite"></span><button type="button" class="source-reset">Reset highlights</button></div>
<div class="source-scroll" tabindex="0" role="region" aria-label="Source code, scroll horizontally and vertically"><div class="source-code"></div></div>
<p class="source-help">Worktree source at animation generation time. Click a line number to highlight it; Shift-click to select a range.</p>
</dialog>'''


def pretty(value):
    return escape(json.dumps(value, indent=2, ensure_ascii=False))


def input_values(value):
    if isinstance(value, dict) and value.get("kind") == "object":
        return {key: input_values(item) for key, item in value["fields"].items()}
    if isinstance(value, list):
        return [input_values(item) for item in value]
    if isinstance(value, dict) and value.get("kind") in {"id", "enum"}:
        return value["value"]
    return value


def argument_text(value):
    if isinstance(value, dict):
        if value.get("kind") == "variable":
            return "$" + value["name"]
        return "{ " + ", ".join(f"{key}: {argument_text(item)}" for key, item in value.items()) + " }"
    if isinstance(value, list):
        return "[" + ", ".join(argument_text(item) for item in value) + "]"
    return json.dumps(value)


def selection_name(key, alias=None):
    name = key["field"].split(".")[-1]
    alias = alias or key.get("responseKey")
    if alias and alias != name:
        name = f"{alias}: {name}"
    if key["arguments"]:
        name += "(" + ", ".join(f"{k}: {argument_text(v)}" for k, v in key["arguments"].items()) + ")"
    return name


def selection_text(selections):
    parts = []
    for selection in selections:
        name = selection_name(selection["key"], selection.get("responseKey"))
        if selection["children"]:
            name += " { " + selection_text(selection["children"]) + " }"
        parts.append(name)
    return " ".join(parts)


def path_label(task):
    parts = (task.get("location") or {}).get("path", [])
    return ".".join(str(p.get("responseKey") or p["field"].split(".")[-1]) if isinstance(p, dict) else str(p) for p in parts) or "root"


def task_label(task, oers, compact=False, with_creator=False):
    prefix = f'#{task["id"]}'
    if with_creator:
        creator = f'#{task["creatorId"]}' if task["creatorId"] is not None else "root"
        prefix += f" ({creator})"
    prefix += " - "
    name = oers[task["location"]["oerId"]]["type"] if task["kind"] == "ORCHESTRATION" else path_label(task).rsplit(".", 1)[-1]
    if compact:
        name = short(name.rsplit(".", 1)[-1], max(6, 23 - len(prefix)))
    return prefix + name


def text(x, y, value, css="", **attrs):
    attributes = " ".join(f'{key.replace("_", "-")}="{escape(str(val), quote=True)}"' for key, val in attrs.items())
    return f'<text x="{x}" y="{y}" class="{css}" {attributes}>{escape(str(value))}</text>'


def short(value, length=26):
    return value if len(value) <= length else value[: length - 1] + "…"


def subject(event, tasks):
    facts = event["facts"]
    if event.get("referenceTaskId") is not None:
        return tasks[event["referenceTaskId"]]
    if "taskId" in facts:
        return tasks[facts["taskId"]]
    occurrence = facts.get("resolverOccurrenceId")
    return next((t for t in tasks.values() if occurrence is not None and t.get("resolverOccurrenceId") == occurrence), None)


def reference_events(events, tasks):
    hops, by_occurrence = {}, {}
    for event in events:
        if event["kind"] != "ROOT_FIELD_REFERENCE_INVOCATION":
            continue
        facts = event["facts"]
        publication = facts["publication"]
        task = next((t for t in tasks.values() if t["kind"] == "FIELD_RESOLVER"
                     and t["location"]["rootId"] == publication["rootId"]
                     and t["location"]["path"] == publication["path"]), None)
        if task is None:
            raise ValueError("Reference hop has no publication task")
        chain = hops.setdefault(task["id"], [])
        chain.append(facts)
        by_occurrence[facts["resolverOccurrenceId"]] = (task["id"], chain, len(chain))
    for event in events:
        match = by_occurrence.get(event["facts"].get("resolverOccurrenceId"))
        if match and event["kind"] in {"RESOLVER_INVOCATION", "ROOT_FIELD_REFERENCE_INVOCATION", "INDEPENDENT_QUERY_FRAGMENT_PREPARED"}:
            task_id, chain, hop = match
            yield {**event, "referenceTaskId": task_id, "referenceChain": chain, "referenceHop": hop}
        else:
            yield event


def reference_label(facts):
    reference = facts.get("reference") or {}
    identity = reference.get("nodeIdentity")
    if identity:
        return f'node({identity["type"]}, {json.dumps(identity["id"])})'
    path = reference.get("path") or [part["field"] for part in facts["invocation"]["path"]]
    name = ".".join(part.split(".")[-1] for part in path)
    arguments = reference.get("arguments", facts["key"]["arguments"])
    return name + "(" + ", ".join(f"{key}: {json.dumps(value)}" for key, value in arguments.items()) + ")"


def reference_chain(event, task):
    chain, hop = event["referenceChain"], event["referenceHop"]
    targets = []
    for i, facts in enumerate(chain[:hop], 1):
        state = "current" if i == hop else "done"
        targets.append(f'<li class="reference-{state}">{code(reference_label(facts))}</li>')
    return f'<section class="reference-chain"><b>Reference chain · {code(path_label(task))} · task #{task["id"]}</b><ol>{"".join(targets)}</ol><p>The destination cell stays claimed while this task follows the references.</p></section>'


def active_task(event, tasks):
    if event["kind"] == "BOOTSTRAP":
        return None
    task = subject(event, tasks)
    if task and event["kind"] == "TASK_PREPARED" and task["kind"] == "FIELD_RESOLVER":
        return tasks[task["creatorId"]]
    return task


def is_handoff(step, tasks):
    if len(step) != 2:
        return False
    previous, event = step
    creator, task = subject(previous, tasks), subject(event, tasks)
    return (
        previous["kind"] == "TASK_EXECUTION" and previous["facts"]["state"] == "FINISHED"
        and creator["kind"] == "ORCHESTRATION"
        and event["kind"] == "TASK_EXECUTION" and event["facts"]["state"] == "STARTED"
        and task["kind"] == "FIELD_RESOLVER" and task["creatorId"] == creator["id"]
    )


def animation_steps(events, tasks):
    root = next(t for t in tasks.values() if t["kind"] == "ORCHESTRATION" and t["creatorId"] is None)
    steps = [[{"kind": "BOOTSTRAP", "sequence": "bootstrap", "facts": {
        "taskId": root["id"], "oer": {"id": root["location"]["oerId"], "fields": []},
    }}]]
    preparation_end = next((i for i, event in enumerate(events)
                            if event["kind"] == "TASK_PREPARED" and subject(event, tasks) == root), None)
    if preparation_end is not None:
        steps.append(events[:preparation_end + 1])
        events = events[preparation_end + 1:]
    for event in events:
        if steps:
            previous = steps[-1][-1]
            task, previous_task = subject(event, tasks), subject(previous, tasks)
            prepared = (
                previous["kind"] == "QUERY_OER_PREPARED" and event["kind"] == "TASK_PREPARED"
                and task == previous_task and task["kind"] == "ORCHESTRATION"
            )
            run_entry = (
                previous["kind"] == "TASK_EXECUTION" and previous["facts"]["state"] == "STARTED"
                and previous_task["kind"] == "ORCHESTRATION"
            )
            field_prepared = (
                event["kind"] == "TASK_PREPARED" and task["kind"] == "FIELD_RESOLVER"
                and task["creatorId"] == previous_task["id"]
            ) if run_entry else False
            finished = (
                event["kind"] == "TASK_EXECUTION" and event["facts"]["state"] == "FINISHED"
                and task == previous_task
            ) if run_entry else False
            handoff = len(steps[-1]) == 1 and is_handoff([previous, event], tasks)
            if prepared or field_prepared or finished or handoff:
                steps[-1].append(event)
                continue
        steps.append([event])
    return steps


def phase(event):
    if event["kind"] == "TASK_EXECUTION":
        return "running" if event["facts"]["state"] == "STARTED" else "finished"
    return {"BOOTSTRAP": "", "QUERY_OER_PREPARED": "preparing", "TASK_PREPARED": "prepared", "RESOLVER_INVOCATION": "reference target" if event.get("referenceHop") else "resolver invoked", "ROOT_FIELD_REFERENCE_INVOCATION": "checking reference tail", "INDEPENDENT_QUERY_FRAGMENT_PREPARED": "awaiting Query input"}.get(event["kind"], "observed")


def preparing_orchestrator(step, tasks):
    event = step[-1]
    task = subject(event, tasks)
    if task and task["kind"] == "ORCHESTRATION" and event["kind"] in {"QUERY_OER_PREPARED", "TASK_PREPARED"}:
        return task
    return None


def orchestration_caller(step, tasks):
    event = step[0]
    task = subject(event, tasks)
    if event["kind"] == "TASK_EXECUTION" and event["facts"]["state"] == "STARTED" and task["kind"] == "ORCHESTRATION":
        creator = tasks.get(task["creatorId"])
        if creator and creator["kind"] == "FIELD_RESOLVER" and creator["location"]["rootId"] == task["location"]["rootId"]:
            return creator
    return None


def passive_resolution_spans(steps, tasks):
    spans = {}
    for i, step in enumerate(steps):
        for event in step:
            task = subject(event, tasks)
            if not task:
                continue
            creator = tasks.get(task["creatorId"])
            if task["kind"] == "ORCHESTRATION" and event["kind"] in {"QUERY_OER_PREPARED", "TASK_PREPARED"} and creator and creator["kind"] == "FIELD_RESOLVER":
                parent, child = creator["location"], task["location"]
                if parent["rootId"] == child["rootId"] and child["path"][:len(parent["path"])] == parent["path"]:
                    spans.setdefault(creator["id"], [i * SECONDS, len(steps) * SECONDS])
            if event["kind"] == "TASK_EXECUTION" and event["facts"]["state"] == "FINISHED" and task["id"] in spans:
                spans[task["id"]][1] = min(spans[task["id"]][1], i * SECONDS)
    return spans


def simple_passive_steps(steps, tasks):
    passive = passive_resolution_spans(steps, tasks)
    invoked, completed, result = set(), set(), {}
    for i, step in enumerate(steps):
        for event in step:
            task, facts = subject(event, tasks), event["facts"]
            if task and event["kind"] == "RESOLVER_INVOCATION":
                invoked.add(task["id"])
            if not task or task["kind"] != "FIELD_RESOLVER" or event["kind"] != "TASK_EXECUTION" or facts["state"] != "FINISHED":
                continue
            if task["id"] not in invoked or task["id"] in completed:
                continue
            completed.add(task["id"])
            if task["id"] in passive or facts.get("cause"):
                continue
            key = task["location"]["path"][-1]
            cell = next((c for c in facts["oer"]["fields"] if c["key"] == key), None)
            if cell and cell["state"] == "COMPLETED" and not (isinstance(cell.get("value"), dict) and cell["value"].get("kind") == "error"):
                result[i] = task
    return result


def task_track(steps, tasks, oers):
    handoffs = {i for i, step in enumerate(steps) if is_handoff(step, tasks)}
    entries = {i for i, step in enumerate(steps) if orchestration_caller(step, tasks)}
    passive = passive_resolution_spans(steps, tasks)
    simple = simple_passive_steps(steps, tasks)
    for i, task in simple.items():
        passive[task["id"]] = (i * SECONDS, i * SECONDS + SECONDS * 2 / 3)
    focus = [(0, 0)]
    for i, step in enumerate(steps):
        start, end = i * SECONDS, (i + 1) * SECONDS
        preparing = preparing_orchestrator(step, tasks)
        if preparing and preparing["creatorId"] is None:
            focus.extend([(start, preparing["id"]), (end - 0.01, preparing["id"])])
        elif preparing:
            creator = tasks.get(preparing["creatorId"])
            caller = creator["id"] if creator and creator["kind"] == "FIELD_RESOLVER" else 0
            focus.extend([
                (start, caller),
                (start + PREP_PAUSE, caller),
                (start + PREP_MID, preparing["id"]),
                (start + PREP_MID + PREP_PAUSE, preparing["id"]),
                (end - PREP_PAUSE, caller or preparing["id"]),
            ])
        else:
            actor = active_task(step[-1], tasks)
            actor_id = actor["id"] if actor else 0
            caller = orchestration_caller(step, tasks)
            if caller:
                focus.extend([(start, caller["id"]), (start + SECONDS / 3, caller["id"]), (start + SECONDS * 2 / 3, actor_id)])
            focus.append((end - 0.01, actor_id))
        focus.append((end, focus[-1][1]))
    focus_track = "|".join(f"{at:g}:{task_id}" for at, task_id in focus)
    orchestrators = [t for t in tasks.values() if t["kind"] == "ORCHESTRATION"]
    positions = {}
    column = 235
    for task in orchestrators:
        children = [t for t in tasks.values() if t["creatorId"] == task["id"] and t["kind"] == "FIELD_RESOLVER"]
        width = max(210, len(children) * 210)
        positions[task["id"]] = (column + (width - 210) / 2, 45)
        for i, child in enumerate(children):
            positions[child["id"]] = (column + i * 210, 145)
        column += width + 100
    if set(positions) != set(tasks):
        raise ValueError("This first viewer requires field tasks created by orchestration tasks")
    width = max(970, column)
    svg = [f'<svg width="{width}" height="235" viewBox="0 0 {width} 235" data-task-focus="{focus_track}" role="img" aria-label="Task track: orchestration above field resolvers; labels identify their creators">',
           text(0, 25, "TASK TRACK", "small muted title"),
           text(0, 76, "Orchestration", "small muted"), text(0, 176, "Field resolvers", "small muted"),
           f'<path class="rail" d="M150 72H{width}M150 172H{width}"/>']
    for task_id, task in tasks.items():
        appearances = [(i, e) for i, step in enumerate(steps) for e in step if subject(e, tasks) == task
                       and e["kind"] in {"BOOTSTRAP", "QUERY_OER_PREPARED", "TASK_PREPARED", "TASK_EXECUTION", "RESOLVER_INVOCATION", "ROOT_FIELD_REFERENCE_INVOCATION", "INDEPENDENT_QUERY_FRAGMENT_PREPARED"}]
        if not appearances:
            raise ValueError(f"Task {task_id} has no recorded observations")
        first_step = appearances[0][0]
        delayed = task["kind"] == "FIELD_RESOLVER" and first_step in entries
        first = first_step * SECONDS + (CLAIM_AT if delayed else 0)
        x, y = positions[task_id]
        creator = tasks.get(task["creatorId"])
        if creator and creator["kind"] == "ORCHESTRATION":
            px, py = positions[creator["id"]]
            wire = f'M{px + 95} {py + 55}L{x + 95} {y}'
            svg.append(f'<path data-in="{first}" data-dur="{0.25 if delayed else 0.6}" data-anim="draw" class="creator" d="{wire}"/>')
        statuses, instant_statuses = {}, set()
        for i, event in appearances:
            at = i * SECONDS + ((SECONDS - STATUS_FADE) / 2 if i in handoffs or i in entries else 0)
            if i in entries and task["kind"] == "FIELD_RESOLVER" and event["kind"] == "TASK_PREPARED":
                at = i * SECONDS + CLAIM_AT
            if task["kind"] == "ORCHESTRATION" and event["kind"] == "TASK_PREPARED":
                statuses[at] = "preparing"
                if task["creatorId"] is None:
                    instant_statuses.add(at)
                    at = (i + 1) * SECONDS - 0.01
                else:
                    at += PREP_MID - STATUS_FADE / 2
            if task["creatorId"] is None and event["kind"] in {"QUERY_OER_PREPARED", "TASK_PREPARED"}:
                instant_statuses.add(at)
            statuses[at] = phase(event)
        passive_state, passive_fill = "", ""
        if task_id in passive:
            start, end = passive[task_id]
            fade_in = SECONDS / 3 if simple.get(int(start // SECONDS)) == task else 0
            statuses[start] = "passive resolution"
            if fade_in:
                statuses[end] = "finished"
            else:
                instant_statuses.add(start)
            passive_state = f' data-add="{start + fade_in:g}:passive-resolution,{end:g}:-passive-resolution,{end:g}:task-finished"'
            passive_fill = f'<rect class="task-passive" x="{x + 1}" y="{y + 1}" width="188" height="53" rx="8" data-in="{start:g}" data-dur="{fade_in:g}" data-out="{end:g}" data-out-dur="{STATUS_FADE}"/>'
        status = "|".join(f"{at:g}:{state}" for at, state in sorted(statuses.items()))
        instant = f' data-text-instant="{",".join(f"{at:g}" for at in sorted(instant_statuses))}"' if instant_statuses else ""
        duration = PREP_PAUSE if task["kind"] == "ORCHESTRATION" else (0.25 if delayed else 0.6)
        if appearances[0][1]["kind"] == "BOOTSTRAP":
            duration = 0
        svg += [f'<g id="task-{task_id}" data-task-id="{task_id}" class="c4" data-in="{first}" data-dur="{duration}" data-anim="pop"{passive_state}>',
                f'<title>{escape(task_label(task, oers))}</title>',
                f'<rect class="task-card" x="{x}" y="{y}" width="190" height="55" rx="9"/>',
                passive_fill,
                f'<rect class="task-highlight" x="{x}" y="{y}" width="190" height="55" rx="9"/>',
                text(x + 12, y + 20, task_label(task, oers, compact=True, with_creator=True), "task-name"),
                f'<g data-text="{escape(status, quote=True)}" data-text-fade="{STATUS_FADE}"{instant}>{text(x + 12, y + 40, "prepared", "task-status")}</g>', '</g>']
    svg += [text(0, 226, "Lines: orchestration → field task.   Parentheses: creator.", "small muted"), '</svg>']
    return "\n".join(svg)


def oer_progress(steps, tasks):
    simple = simple_passive_steps(steps, tasks)
    states, seen, producers = {}, {}, {}
    created, cell_changes = {}, {}
    for i, step in enumerate(steps):
        preparing = preparing_orchestrator(step, tasks)
        caller = orchestration_caller(step, tasks)
        previous_cells = dict(cell_changes)
        focus_times = set()
        for event in step:
            facts = event["facts"]
            task = subject(event, tasks)
            if task:
                location = task["location"]
                label = path_label(task) if task["kind"] == "ORCHESTRATION" else "root"
                creator = tasks.get(task["creatorId"])
                if task["kind"] == "ORCHESTRATION" and creator and not location["path"] and location["rootId"] != creator["location"]["rootId"]:
                    label = f'Query input for task #{creator["id"]}'
                seen.setdefault(location["oerId"], label)
                if event["kind"] == "TASK_PREPARED" and task["kind"] == "FIELD_RESOLVER":
                    key = location["path"][-1]
                    if isinstance(key, dict) and "field" in key:
                        producers[location["oerId"], json.dumps(key, sort_keys=True)] = task["id"]
            if event["kind"] == "QUERY_OER_PREPARED" and facts["oer"]["fields"]:
                seen.setdefault(facts["query"]["oerId"], f'Query inputs for task #{task["id"]}')
            if "oer" in facts:
                states[facts["oer"]["id"]] = facts["oer"]
            for state in facts.get("oers", []):
                states[state["id"]] = state
        for oer_id in seen:
            created.setdefault(oer_id, i * SECONDS)
            for cell in states.get(oer_id, {}).get("fields", []):
                address = (oer_id, json.dumps(cell["key"], sort_keys=True))
                producer = producers.get(address)
                previous = cell_changes.get(address)
                if previous is None or previous[:2] != (cell, producer):
                    changed_at = i * SECONDS
                    if preparing and previous is None:
                        changed_at += PREP_MID
                    elif caller:
                        changed_at += CLAIM_AT if cell["state"] == "PENDING" and producer is not None else PASSIVE_AT
                    elif i in simple:
                        changed_at += SECONDS / 2
                    if changed_at > i * SECONDS:
                        focus_times.add(changed_at)
                    cell_changes[address] = (cell, producer, changed_at)
        yield states, seen, created, cell_changes, previous_cells, focus_times


def input_cells(oer_id, selections, value, states):
    if not isinstance(value, dict) or value.get("kind") != "object":
        return set()
    fields = {json.dumps(cell["key"], sort_keys=True): cell for cell in states.get(oer_id, {}).get("fields", [])}
    reads = set()
    for selection in selections:
        key = selection["key"]
        alias = selection.get("responseKey") or key.get("responseKey") or key["field"].split(".")[-1]
        address = (oer_id, json.dumps(key, sort_keys=True))
        cell = fields.get(address[1])
        if cell is None or alias not in value["fields"] or value["type"] not in selection["types"]:
            continue
        reads.add(address)
        child = cell.get("value")
        if isinstance(child, dict) and child.get("kind") == "oer":
            reads.update(input_cells(child["id"], selection["children"], value["fields"][alias], states))
    return reads


def oer_frames(steps, tasks, oers):
    frames = []
    row_height = 42
    query_inputs = {e["facts"]["resolverOccurrenceId"]: e["facts"]["oerId"] for step in steps for e in step
                    if e["kind"] in {"QUERY_FRAGMENT_PREPARED", "INDEPENDENT_QUERY_FRAGMENT_PREPARED"}}
    for i, (states, seen, created, cell_changes, previous_cells, focus_times) in enumerate(oer_progress(steps, tasks)):
        step = steps[i]
        event = step[-1]
        facts = event["facts"]
        task = subject(event, tasks)
        reads = set()
        if event["kind"] == "RESOLVER_INVOCATION" and task:
            reads.update(input_cells(task["location"]["oerId"], facts["inputSelections"], facts["input"], states))
            query_oer = query_inputs.get(facts["resolverOccurrenceId"])
            if query_oer is not None:
                reads.update(input_cells(query_oer, facts["queryInputSelections"], facts["queryInput"], states))
        parts = [text(12, 24, "OBJECT RESULT TREE", "small muted title")]
        y, anchors, links = 55, {}, []
        for oer_id, path in seen.items():
            fields = states.get(oer_id, {}).get("fields", [])
            x = 12 if path == "root" else 50
            card_height = 72 + max(1, len(fields)) * row_height
            anchors[oer_id] = (x, y)
            focus = ' data-focus' if created[oer_id] == i * SECONDS else ""
            parts += [f'<g class="c2" data-oer-id="{oer_id}" data-in="{created[oer_id]}" data-dur="{PREP_PAUSE}" data-anim="pop"><rect class="box c2"{focus} x="{x}" y="{y}" width="410" height="{card_height}" rx="10"/>',
                      text(x + 16, y + 25, oers[oer_id]["type"], "title"),
                      text(x + 392, y + 25, f"OER #{oer_id}", "node-id", text_anchor="end"),
                      text(x + 16, y + 46, path, "small muted mono")]
            for j, cell in enumerate(fields):
                cy = y + 60 + j * row_height
                address = (oer_id, json.dumps(cell["key"], sort_keys=True))
                producer = cell_changes[address][1]
                previous = previous_cells.get(address)
                changed = previous is None or previous[:2] != (cell, producer)
                changed_at = cell_changes[address][2]
                versions = [(cell, producer, changed_at, "", True)]
                if changed and previous is not None and changed_at > i * SECONDS:
                    versions = [(*previous, f"{i * SECONDS}-{changed_at}", False), (cell, producer, changed_at, f"{changed_at}-", True)]
                reading = "input-read" if address in reads else ""
                for value_cell, owner, at, window, current in versions:
                    label = value_cell["key"].get("responseKey") or value_cell["key"]["field"].split(".")[-1]
                    value = value_cell.get("value")
                    if value_cell["state"] == "PENDING":
                        rendered = f"claimed by #{owner}" if owner is not None else "reserved"
                    elif isinstance(value, dict) and value.get("kind") == "oer":
                        rendered = f'completed: → {oers[value["id"]]["type"]} #{value["id"]}'
                        links.append((oer_id, value["id"], cy + 15, at, window))
                    else:
                        rendered = "completed: " + json.dumps(input_values(value), ensure_ascii=False) if value_cell["state"] == "COMPLETED" else "failed"
                    css = "cell full" if value_cell["state"] == "COMPLETED" else "cell"
                    focus = ' data-focus' if current and (i * SECONDS <= at < (i + 1) * SECONDS or reading) else ""
                    visibility = f' data-on="{window}"' if window else ""
                    parts += [f'<g class="{reading}{" cell-state" if window else ""}"{visibility} data-in="{at}" data-dur="{0 if window else 0.25}" data-anim="pop"><rect class="{css}"{focus} x="{x + 12}" y="{cy}" width="386" height="32" rx="5"/>',
                              text(x + 22, cy + 21, short(label, 22), "mono"),
                              text(x + 386, cy + 21, short(rendered, 24), "mono muted", text_anchor="end"), '</g>']
            parts.append('</g>')
            y += card_height + 42
        for parent, child, cy, at, window in links:
            if child in anchors:
                px, _ = anchors[parent]
                cx, child_y = anchors[child]
                visibility = f' data-on="{window}"' if window else ""
                parts.append(f'<path class="wire c2{" cell-state" if window else ""}"{visibility} data-in="{at}" data-dur="0" d="M{px + 5} {cy}H{px - 5}V{child_y + 20}H{cx}"/>')
        parts.append(text(12, y + 4, "Reserved / claimed: dashed cell.   Completed: filled cell.", "small muted"))
        height = y + 30
        until = str((i + 1) * SECONDS) if i + 1 < len(steps) else ""
        sequences = ",".join(str(e["sequence"]) for e in step)
        focus_phase = f' data-focus-at="{",".join(str(at) for at in sorted(focus_times))}"' if focus_times else ""
        frames.append(f'<g class="snapshot"{focus_phase} data-viewbox="0 0 480 {height}" data-event="{event["sequence"]}" data-events="{sequences}" data-in="{i * SECONDS}" data-dur="0" data-on="{i * SECONDS}-{until}">{"".join(parts)}</g>')
    return f'<svg viewBox="0 0 480 {height}" role="img" aria-label="OER cells and their published values">{"".join(frames)}</svg>'


def demand_frames(recording, steps, tasks, oers):
    roots, locations, queries = {}, {}, {}
    updates = {i * SECONDS: [] for i in range(len(steps))}
    query_inputs = {e["facts"]["resolverOccurrenceId"]: e["facts"]["oerId"] for e in recording["events"]
                    if e["kind"] in {"QUERY_FRAGMENT_PREPARED", "INDEPENDENT_QUERY_FRAGMENT_PREPARED"}}

    def schedule(at, kind, *args):
        updates.setdefault(at, []).append((kind, args))

    for i, (_, _, _, cells, previous, _) in enumerate(oer_progress(steps, tasks)):
        start = i * SECONDS
        for event in steps[i]:
            task, facts = subject(event, tasks), event["facts"]
            if task and task["kind"] == "ORCHESTRATION":
                location = task["location"]
                if location["oerId"] not in locations:
                    locations[location["oerId"]] = location
                    schedule(start, "created", location, task["creatorId"])
                if event["kind"] == "QUERY_OER_PREPARED":
                    queries[task["id"]] = facts["query"]
                    if facts["oer"]["fields"]:
                        locations[facts["query"]["oerId"]] = facts["query"]
                        schedule(start, "created", facts["query"], task["id"])
                if event["kind"] == "TASK_PREPARED":
                    schedule(start + PREP_MID, "demand", location, facts["constructionDemand"])
                    if facts.get("queryConstructionDemand"):
                        query = queries[task["id"]]
                        locations[query["oerId"]] = query
                        schedule(start + PREP_MID, "demand", query, facts["queryConstructionDemand"])
        for address, change in cells.items():
            if change != previous.get(address) and address[0] in locations:
                schedule(change[2], "cell", locations[address[0]], change[0], change[1])

    def empty_node(key=None):
        return {"key": key, "children": {}, "creators": {}, "cells": {}, "conditions": {}}

    def child(node, key):
        return node["children"].setdefault(json.dumps(key, sort_keys=True), empty_node(key))

    def at_location(location):
        root = roots.setdefault(location["rootId"], empty_node())
        node = root
        for part in location["path"]:
            if "field" in part:
                node = child(node, part)
        return node

    def merge(node, selections, client=False):
        for selection in selections:
            target = child(node, selection["key"])
            if client:
                target["client"] = True
            condition = selection.get("condition", True)
            target["conditions"][json.dumps(condition, sort_keys=True)] = condition
            merge(target, selection["children"], client)

    def item_label(location):
        indices = "".join(f'[{part["index"]}]' for part in location["path"] if "index" in part)
        return f"item {indices}: " if indices else ""

    def completed_value(value):
        if isinstance(value, dict) and value.get("kind") == "oer":
            return oers[value["id"]]["type"]
        if isinstance(value, list):
            return "[" + ", ".join(completed_value(v) for v in value) + "]"
        return json.dumps(input_values(value), ensure_ascii=False)

    def input_addresses(location, selections, value):
        addresses = set()
        prefix = (str(location["rootId"]), *(json.dumps(p, sort_keys=True) for p in location["path"] if "field" in p))

        def objects(value):
            if isinstance(value, list):
                return [obj for item in value for obj in objects(item)]
            if isinstance(value, dict) and value.get("kind") == "list":
                return objects(value["values"])
            return [value] if isinstance(value, dict) and value.get("kind") == "object" else []

        def visit(selections, values, address):
            parents = [obj for value in values for obj in objects(value)]
            for selection in selections:
                key = selection["key"]
                alias = selection.get("responseKey") or key.get("responseKey") or key["field"].split(".")[-1]
                inputs = [p["fields"][alias] for p in parents
                          if alias in p["fields"] and p["type"] in selection["types"]]
                if not inputs:
                    continue
                target = (*address, json.dumps(key, sort_keys=True))
                addresses.add(target)
                visit(selection["children"], inputs, target)

        visit(selections, [value], prefix)
        return addresses

    def lines():
        result = []

        def walk(node, depth, address):
            notes = []
            for cell, owner, prefix in node["cells"].values():
                if cell["state"] == "PENDING":
                    state = f"claimed by #{owner}" if owner is not None else "reserved"
                else:
                    state = "completed: " + completed_value(cell.get("value")) if cell["state"] == "COMPLETED" else "failed"
                notes.append(prefix + state)
            for creator, prefix in node["creators"].values():
                notes.append(prefix + (f"created by #{creator}" if creator is not None else "created by root"))
            if node["conditions"] and "true" not in node["conditions"]:
                notes.append("condition: " + " or ".join(node["conditions"]))
            name = selection_name(node["key"]) if node["key"] else "query"
            title = f' title="{escape(node["key"]["field"], quote=True)}"' if node["key"] else ""
            css = "demand-name" + (" client-demand" if node.get("client") else "")
            body = "  " * depth + f'<span class="{css}"{title}>{escape(name)}</span>'
            if node["children"] or node["key"] is None:
                body += " {"
            if notes:
                body += '<span class="demand-note"> # ' + escape(", ".join(notes)) + '</span>'
            result.append((address, body))
            for key, value in node["children"].items():
                walk(value, depth + 1, address + (key,))
            if node["children"] or node["key"] is None:
                result.append(((*address, "}"), "  " * depth + "}"))

        for root_id, root in roots.items():
            if result:
                result.append(((str(root_id), "gap"), ""))
            walk(root, 0, (str(root_id),))
        return result

    first = next(t for t in tasks.values() if t["kind"] == "ORCHESTRATION")
    initial = at_location({**first["location"], "path": []})
    initial["client"] = True
    merge(initial, recording["initialDemand"], client=True)
    times = sorted(updates)
    frames, previous_lines = [], {}
    for i, at in enumerate(times):
        for kind, args in updates[at]:
            location = args[0]
            node = at_location(location)
            if kind == "created":
                node["creators"][location["oerId"]] = (args[1], item_label(location))
            elif kind == "demand":
                merge(node, args[1])
            elif kind == "cell":
                child(node, args[1]["key"])["cells"][location["oerId"]] = (args[1], args[2], item_label(location))
        current = lines()
        reads = set()
        for event in steps[min(int(at // SECONDS), len(steps) - 1)]:
            if event["kind"] != "RESOLVER_INVOCATION":
                continue
            task, facts = subject(event, tasks), event["facts"]
            object_location = locations.get(task["location"]["oerId"]) if task else None
            query_location = locations.get(query_inputs.get(facts["resolverOccurrenceId"]))
            for location, selections, value in [(object_location, facts["inputSelections"], facts["input"]),
                                                (query_location, facts["queryInputSelections"], facts["queryInput"])]:
                if location and selections:
                    reads.update(input_addresses(location, selections, value))
        rendered = []
        for address, body in current:
            changed = body != previous_lines.get(address) and address[-1] not in {"}", "gap"}
            reading = address in reads
            css = "demand-line" + (" demand-changed" if changed else "") + (" input-read" if reading else "")
            focus = " data-focus" if changed or reading else ""
            rendered.append(f'<span class="{css}"{focus}>{body}</span>')
        until = str(times[i + 1]) if i + 1 < len(times) else ""
        frames.append(f'<pre class="demand-snapshot" data-on="{at}-{until}" data-in="{at}" data-dur="0"><code>{"".join(rendered)}</code></pre>')
        previous_lines = dict(current)
    return '<p class="demand-hint">Demand grows as orchestrators prepare. Comments show OER and cell state; highlighted lines changed.</p>' + "".join(frames)


def materialize(oer_id, states, visited=()):
    if oer_id in visited:
        return f"→ OER #{oer_id}"

    def value(v):
        if isinstance(v, dict) and v.get("kind") == "oer":
            return materialize(v["id"], states, (*visited, oer_id))
        if isinstance(v, list):
            return [value(item) for item in v]
        return input_values(v)

    return {(c["key"].get("responseKey") or c["key"]["field"].split(".")[-1]): value(c.get("value"))
            for c in states[oer_id]["fields"] if c["state"] == "COMPLETED"}


def caption(step, tasks, oers):
    event = step[-1]
    kind, facts = event["kind"], event["facts"]
    task = subject(event, tasks)
    if kind == "BOOTSTRAP":
        return "Bootstrap resolution", f'<span class="actor">{escape(task_label(task, oers))}</span><p>Resolution begins with a root orchestration task and an empty Query OER{code_pointer("root")}.</p>'
    caller = orchestration_caller(step, tasks)
    inputs = ""
    if kind == "QUERY_OER_PREPARED" or (kind == "TASK_PREPARED" and task["kind"] == "ORCHESTRATION"):
        title = "Prepare object orchestration"
        body = f'When a resolver returns an object, the field-resolver task allocates an OER for it{code_pointer("object")} and creates and prepares an orchestrator task for it{code_pointer("prepare")}. The orchestrator will close demand{code_pointer("closure")} and bind variable declarations{code_pointer("bindings")}.'
        if not task["location"]["path"] and task["creatorId"] is None:
            body = f'Prepare the operation\'s root OER and its orchestration task{code_pointer("root")}. The orchestrator closes demand{code_pointer("closure")} and declares variable bindings{code_pointer("bindings")}.'
        elif not task["location"]["path"]:
            body = f'The reference target needs its own Query input. Its publication task creates an independently rooted Query OER and prepares an orchestrator{code_pointer("reference-query")}. The orchestrator closes this input’s construction demand{code_pointer("closure")} and declares its bindings{code_pointer("bindings")}. This is input preparation, before the reference target is invoked.'
        body += f' Reserved OER cells hold space for demanded values{code_pointer("reserve")}. Field-resolver tasks will later claim responsibility for the cells they produce.'
        query = next((e["facts"] for e in step if e["kind"] == "QUERY_OER_PREPARED"), None)
        if query and query["oer"]["fields"]:
            fields = ", ".join(code(cell["key"]["field"]) for cell in query["oer"]["fields"])
            body += f' Its Query OER{code_pointer("query-oer")} is included in the tree because it has demanded fields: {fields}.'
    elif kind == "TASK_PREPARED":
        title = "Prepare the field task"
        body = f'Orchestrator task {code("#" + str(task["creatorId"]))} creates field-resolver task {code("#" + str(task["id"]))}{code_pointer("field-task")} and assigns it responsibility for completing the {code(path_label(task))} cell. The task claims the cell’s value promise{code_pointer("claim")}. The cell remains claimed until its field task completes it{code_pointer("publish")}.'
        if caller:
            body = f'Field-resolver task {code("#" + str(caller["id"]))} first completes the object’s passive fields{code_pointer("passive-fields")}. Execution then transfers to the orchestrator. ' + body
    elif kind == "TASK_EXECUTION":
        started = facts["state"] == "STARTED"
        if task["kind"] == "ORCHESTRATION":
            title = "Run object orchestration" if started else "Dispatch field work"
            body = f'The run phase prepares and dispatches active field work{code_pointer("dispatch")}, then freezes the object and Query OER field sets{code_pointer("freeze")}.'
            if not started:
                body = f'The orchestrator has dispatched its prepared field tasks{code_pointer("dispatch")} and frozen the object and Query OER field sets{code_pointer("freeze")}. The field tasks can now run and publish their results into the cells they own{code_pointer("publish")}.'
                if not any(t["kind"] == "FIELD_RESOLVER" and t["creatorId"] == task["id"] for t in tasks.values()):
                    title = "Seal the field sets"
                    body = f'The orchestrator freezes the object and Query OER field sets{code_pointer("freeze")}. No field-resolver tasks are needed for this object.'
        else:
            title = "Start the field task" if started else "Field task finished"
            if started:
                body = f'The task computes successor demand{code_pointer("successor")}, then evaluates the field’s inclusion condition. If the field is excluded, it marks the cell inactive and stops{code_pointer("inactive")}. Otherwise, it resolves argument values{code_pointer("arguments")}, and reads required inputs{code_pointer("inputs")}. All of a resolver’s inputs must be ready before the resolver is called; the task suspends waiting for those inputs if they are not ready{code_pointer("await-inputs")}.'
                if facts.get("sourceReference"):
                    body = f'The source already supplies a root-field reference. This task computes successor demand and evaluates inclusion{code_pointer("successor")}{code_pointer("inactive")}, then follows the reference instead of invoking the containing field’s registered resolver{code_pointer("reference-tail")}. Each target uses the reference’s grounded arguments and its own Query input{code_pointer("reference-invoke")}.'
            else:
                body = f'The task has finished passive descent{code_pointer("passive")} and publication{code_pointer("publish")}. Its parent\'s cell now holds the observed value.'
        if facts.get("cause"):
            body += f' The recorded termination has a cause: {code(facts["cause"])}.'
    elif kind == "RESOLVER_INVOCATION":
        reference = event.get("referenceHop")
        title = "Follow a reference · hop " + str(reference) if reference else "Invoke the resolver"
        demand = selection_text(facts["suppliedDemand"])
        identity = event["referenceChain"][reference - 1].get("reference", {}).get("nodeIdentity") if reference else None
        arguments = f'for Node identity {code(identity["type"] + ": " + identity["id"])}' if identity else f'with arguments {code(json.dumps(facts["arguments"]))}{"" if reference else code_pointer("arguments")}'
        body = f'Invoke {code(facts["field"])}{code_pointer("reference-invoke" if reference else "invoke")} {arguments} and successor demand {code(demand or "empty")}{code_pointer("successor")}.'
        if reference:
            body += f' The same field task follows {code(reference_label(event["referenceChain"][reference - 1]))}; this is a new resolver invocation, with a fresh invocation identity, within the existing publication{code_pointer("reference-tail")}. The target path identifies the resolver; namespace prefixes are not executed to reach it.'
        if not facts["inputSelections"] and not facts["queryInputSelections"]:
            body += " No object or Query input fields are required."
        for input_label, selection_key, value_key in [("Object fragment", "inputSelections", "input"), ("Query fragment", "queryInputSelections", "queryInput")]:
            if facts[selection_key]:
                pointer = "object-input" if selection_key == "inputSelections" else ("reference-query" if reference else "query-input")
                inputs += f'<section class="input-snapshot"><p><b>{input_label}</b> {code("{ " + selection_text(facts[selection_key]) + " }")}{code_pointer(pointer)}</p><pre>{pretty(input_values(facts[value_key]))}</pre></section>'
        if facts["inputSelections"] or facts["queryInputSelections"]:
            inputs += '<p>The highlighted OER cells supply this invocation’s required inputs.</p>'
    elif kind == "ROOT_FIELD_REFERENCE_INVOCATION":
        hop = event["referenceHop"]
        title = "Check the reference tail"
        body = f'The helper for {code(reference_label(facts))} has returned{code_pointer("reference-tail")}. The task checks its result: another reference continues the loop; an ordinary value proceeds to passive resolution and publication.'
        if hop < len(event["referenceChain"]):
            body += ' This execution continues with another reference hop in the same task.'
        else:
            body += ' This is the last reference hop for this publication.'
            identity = next((f.get("reference", {}).get("nodeIdentity") for f in event["referenceChain"] if f.get("reference", {}).get("nodeIdentity")), None)
            if identity:
                body += f' Before passive descent, the first Node identity, {code(identity["type"] + ": " + identity["id"])}, remains authoritative for demanded output{code_pointer("node-identity")}.'
    elif kind == "INDEPENDENT_QUERY_FRAGMENT_PREPARED":
        title = "Prepare the reference target’s Query input"
        body = f'This reference invocation has an independently rooted Query input{code_pointer("reference-query")}. Its cells must be ready before the target resolver can be invoked. It is separate from the publication’s result OER and from other reference hops’ Query inputs.'
    elif kind == "RESOLUTION_COMPLETED":
        title = "Resolution complete"
        body = f'The final recorded OER tree contains the constructed values{code_pointer("completion")}. This is resolution output, before GraphQL response completion.'
    else:
        title = kind.replace("_", " ").capitalize()
        body = "This callback records additional resolution metadata; expand the facts below to inspect it."
    actor = active_task(event, tasks)
    actor_label = task_label(actor, oers) if actor else "Resolution"
    if caller:
        actor_label = task_label(caller, oers) + " → " + actor_label
    content = f'<span class="actor">{escape(actor_label)}</span><p>{body}</p>{inputs}'
    if event.get("referenceHop"):
        content += reference_chain(event, task)
    if kind == "RESOLUTION_COMPLETED":
        content += f'<pre>{pretty(materialize(facts["rootId"], {s["id"]: s for s in facts["oers"]}))}</pre>'
    event_label = f'Observation #{event["sequence"]} · {kind}' if len(step) == 1 else "Observations " + ", ".join(f'#{e["sequence"]}' for e in step)
    content += f'<p class="event">{event_label}</p><details><summary>Recorded facts</summary><pre>{pretty(facts if len(step) == 1 else step)}</pre></details>'
    return title, content


def page(title, body, assets):
    def asset(name):
        version = sha256((SOURCE / "assets" / name).read_bytes()).hexdigest()[:12]
        return f"{assets}/{name}?v={version}"

    return f'''<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{escape(title)} · Runtime2</title>
<script src="{asset('theme.js')}"></script><link rel="stylesheet" href="{asset('style.css')}">
<link rel="stylesheet" href="{asset('recording.css')}"></head><body><main>{body}</main>
<script src="{asset('motion.js')}"></script></body></html>'''


def render(recording):
    if recording["formatVersion"] != 1:
        raise ValueError("Unsupported recording format version")
    if not recording["completed"] or recording["failure"]:
        raise ValueError("This first viewer requires a successfully completed resolution")
    tasks = {t["id"]: t for t in recording["tasks"]}
    oers = {o["id"]: o for o in recording["oers"]}
    events = list(reference_events(recording["events"], tasks))
    if not events or any(t["kind"] not in {"ORCHESTRATION", "FIELD_RESOLVER"} for t in tasks.values()) or any(e["kind"].startswith("CHECKER") for e in events):
        raise ValueError("This first viewer supports nonempty, checker-free orchestration and field-resolver recordings")
    steps = animation_steps(events, tasks)
    test = recording["test"]
    identity = f'{test["className"]}.{test["methodName"]}'
    title = "Output projection" if identity == PROJECTION else test["methodName"].capitalize()
    source = recording.get("source")
    source_context = (
        f'<div class="example example-source"><pre><small>Schema</small><code>{escape(source["schema"])}</code></pre>'
        f'<pre><small>Query</small><code>{escape(source["query"])}</code></pre></div>'
        if source else '<p class="hint">Regenerate this animation to include its schema and query.</p>'
    )
    context = ""
    if identity == PROJECTION:
        context = '<p>The resolver supplies both fields. Only <code>requested</code> belongs in the constructed User OER.</p>'
    elif identity == OBJECT_FRAGMENT:
        title = "Object fragments: a dependency chain"
        context = '<p>The client asks only for <code>greeting</code>. Its object fragment requires <code>display</code>, whose object fragment requires <code>first</code> and <code>last</code>. Watch demand expand, then <code>display = 5</code> become available before <code>greeting = 6</code>. The internal OER retains these dependencies; GraphQL completion selects the client’s response fields.</p>'
    beats = []
    for i, step in enumerate(steps):
        heading, body = caption(step, tasks, oers)
        beats.append(f'<li data-at="{i * SECONDS}" data-title="{escape(heading, quote=True)}">{body}</li>')
    has_demand = isinstance(recording.get("initialDemand"), list) and all(
        "constructionDemand" in e["facts"] for e in events
        if e["kind"] == "TASK_PREPARED" and subject(e, tasks)["kind"] == "ORCHESTRATION"
    )
    demand = demand_frames(recording, steps, tasks, oers) if has_demand else ""
    unavailable = '' if has_demand else ' disabled title="Regenerate this recording to capture construction demand"'
    body = f'''<div class="topline"><span class="kicker">Runtime2 · recorded execution</span><a href="../index.html">All animations</a></div>
<h1>{escape(title)}</h1><p class="lede">One production execution. Follow the tasks as they construct the result tree.</p>
{source_context}{context}<div class="legend"><span class="c4">Executing</span><span class="passive-legend">Executing passive field resolution</span></div>
<figure class="scene" data-duration="{len(steps) * SECONDS}"><div class="stage">
<div class="task-track">{task_track(steps, tasks, oers)}</div>
<div class="resolution-view"><div class="view-tabs" role="tablist" aria-label="Resolution view">
<button id="oer-tab" role="tab" aria-selected="true" aria-controls="oer-view">OER</button>
<button id="demand-tab" role="tab" aria-selected="false" aria-controls="demand-view" tabindex="-1"{unavailable}>Construction demand</button></div>
<div id="oer-view" class="oer-tree" role="tabpanel" aria-labelledby="oer-tab" tabindex="0">{oer_frames(steps, tasks, oers)}</div>
<div id="demand-view" class="construction-demand" role="tabpanel" aria-labelledby="demand-tab" tabindex="0" hidden>{demand}</div></div>
</div><ol class="beats">{"".join(beats)}</ol></figure>
{code_sources(beats)}
<p class="hint">Use Next / Prev, the timeline, or the arrow keys. Auto plays the full sequence. Times are illustrative; steps group related callbacks in their recorded order. Highlighted tasks are on stage, not a concurrency schedule.</p>
<noscript><p>Enable JavaScript to step through the recording. The explanations above remain readable.</p></noscript>
<details><summary>Recording and sources</summary><p class="meta">{escape(identity)}</p><p><a href="recording.json">Observation log (JSON)</a> · <a href="https://slate.airbnb.tools/Y3YxDNTJT4/Runtime2+resolver+debugger+walkthrough">Debugger walkthrough</a></p></details>
<footer>Scene conventions and player adapted from <a href="{TOUR}">Alex’s Runtime2 tour</a>: vanilla JavaScript, inline SVG, declarative beats, and the same visual language. No checkers are shown.</footer>'''
    return page(title, body, "../assets")


def gallery(output):
    links = []
    for path in sorted(output.glob("*/index.html"), key=lambda p: p.stat().st_mtime, reverse=True):
        test = json.loads((path.parent / "recording.json").read_text())["test"]
        stamp = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        links.append(f'<li><a href="{path.parent.name}/index.html">{escape(test["methodName"])}</a><small>{escape(test["className"])} · {stamp}</small></li>')
    body = f'<div class="kicker">Runtime2 · recorded executions</div><h1>Resolution animations</h1><p class="lede">Most recently generated first. Each test replaces its previous animation.</p><ul class="recordings">{"".join(links)}</ul>'
    (output / "index.html").write_text(page("Resolution animations", body, "assets").replace('<meta name="viewport"', '<meta http-equiv="refresh" content="5"><meta name="viewport"'))


def build_animation(recordings, test, output):
    class_name, method = test.rsplit(".", 1)
    prefix = re.sub(r"[^A-Za-z0-9._-]", "_", method)[:80]
    digest = sha256(method.encode()).hexdigest()[:16]
    source = recordings / class_name / f"{prefix}-{digest}.json"
    if not source.is_file():
        raise ValueError(f"No recording for {test}. Choose an eligible single-resolution @Test, or rerun animate with --rerun-tasks if its artifact was removed.")
    recording = json.loads(source.read_text())
    if recording["test"] != {"className": class_name, "methodName": method}:
        raise ValueError("Recording identity does not match the selected test")
    html = render(recording)
    slug = prefix[:60] + "-" + sha256(test.encode()).hexdigest()[:12]
    destination = output / slug
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(SOURCE / "assets", output / "assets", dirs_exist_ok=True)
    shutil.copy2(source, destination / "recording.json")
    (destination / "index.html").write_text(html)
    return destination / "index.html"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recordings", type=Path, required=True)
    parser.add_argument("--test", action="append", required=True, help="Exact fully qualified class.method, without wildcards; repeat for multiple tests")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        for test in args.test:
            print(f"Animation: {build_animation(args.recordings, test, args.output)}")
        gallery(args.output)
        print("Serve with ./gradlew :core:engine:runtime2:animationViewer")
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"Cannot animate recording: {error}\n")


if __name__ == "__main__":
    main()
