#!/usr/bin/env python3
"""Validate the tour site. It never writes files.

Usage: python3 -I tools/validate.py

The site lives in core/engine/runtime2/impldocs/website, and its source links are relative to the
repository root five levels up. Every <a class="src"> and <figure class="code"> is checked against
the code in this checkout: data-lines in bounds, data-expect inside the cited range, data-anchor
matching a Markdown heading, and each code excerpt matching its cited lines verbatim.

Site checks always run: internal links, anchors and glossary ids resolve, no page hard-codes a
repository link, scenes and concept graphs are
well-formed, figure.code data-hl lies within data-lines, and every chapter and deep-dive page is
in the app.js nav exactly once.
"""
import html
import html.parser
import pathlib
import re
import sys
import urllib.parse

REPO = pathlib.Path(__file__).resolve().parent.parent
OSS_ROOT = REPO.parents[4]
BLOB_RE = re.compile(r"github\.com/[^/\s\"']+/viaduct\b", re.I)
EXTERNAL_RE = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//)", re.I)
RANGE_RE = re.compile(r"\s*(\d+)(?:-(\d+))?\s*")
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
        "track", "wbr"}

errors = []
stats = {"figures": 0, "links": 0, "expects": 0, "pages": 0, "internal": 0, "gl": 0, "scenes": 0,
         "graphs": 0}


def err(msg):
    errors.append(msg)


# ---------- parsing ----------

class El:
    __slots__ = ("tag", "attrs", "parent", "children", "start", "start_end", "end", "line", "text")

    def __init__(self, tag, attrs, parent, start, start_end, line):
        self.tag, self.attrs, self.parent = tag, attrs, parent
        self.children, self.start, self.start_end, self.end, self.line = [], start, start_end, None, line
        self.text = []

    def has(self, c):
        return c in self.attrs.get("class", "").split()

    def ancestors(self):
        p = self.parent
        while p is not None:
            yield p
            p = p.parent

    def descendants(self):
        for c in self.children:
            yield c
            yield from c.descendants()

    def content(self):
        return "".join(self.text)


class Doc(html.parser.HTMLParser):
    """Element tree with source offsets and line numbers."""

    def __init__(self, src):
        super().__init__(convert_charrefs=True)
        self.src = src
        self.offsets = [0] + [m.end() for m in re.finditer("\n", src)]
        self.root = El("#root", {}, None, 0, 0, 0)
        self.stack = [self.root]
        self.els = []
        self.feed(src)
        self.close()
        self.ids = {e.attrs["id"] for e in self.els if e.attrs.get("id")}

    def _pos(self):
        line, col = self.getpos()
        return self.offsets[line - 1] + col, line

    def _open(self, tag, attrs, push):
        start, line = self._pos()
        raw = self.get_starttag_text()
        if tag in ("li", "p") and self.stack[-1].tag == tag:
            self.stack.pop().end = start
        el = El(tag, {k: (v or "") for k, v in attrs}, self.stack[-1], start, start + len(raw), line)
        self.stack[-1].children.append(el)
        self.els.append(el)
        if push:
            self.stack.append(el)
        else:
            el.end = el.start_end

    def handle_starttag(self, tag, attrs):
        self._open(tag, attrs, tag not in VOID)

    def handle_startendtag(self, tag, attrs):
        self._open(tag, attrs, False)

    def handle_endtag(self, tag):
        start, _ = self._pos()
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                for e in self.stack[i:]:
                    e.end = start
                del self.stack[i:]
                return

    def handle_data(self, d):
        for e in self.stack:
            e.text.append(d)


def add_generated_ids(doc):
    # Mirrors pageSections() in assets/app.js, which ids every h2 in <main> that lacks one.
    for h in doc.els:
        if h.tag != "h2" or h.attrs.get("id"):
            continue
        anc = list(h.ancestors())
        if not any(a.tag == "main" for a in anc):
            continue
        if any(a.tag == "figure" or a.has("drill") or a.has("dd-start") or (a.tag == "article" and a.has("ex"))
               for a in anc):
            continue
        p = h.parent
        if p.tag == "section" and p.attrs.get("id") and p.children and p.children[0] is h:
            continue
        doc.ids.add(re.sub(r"^-|-$", "", re.sub(r"[^a-z0-9]+", "-", h.content().lower())))


# ---------- source rendering ----------

class Sources:
    def __init__(self, oss_root):
        self.oss = oss_root
        self.cache = {}

    def lines(self, src, where):
        if src not in self.cache:
            self.cache[src] = self._load(src, where)
        return self.cache[src]

    def _load(self, src, where):
        if ".." in pathlib.PurePosixPath(src).parts:
            err(f"{where}: data-src {src} must not contain ..")
            return None
        if src.startswith("/"):
            err(f"{where}: data-src {src} must be relative to the public repository root")
            return None
        f = self.oss / src
        if not f.is_file():
            err(f"{where}: missing file {src}")
            return None
        return f.read_text(encoding="utf-8").splitlines()


def parse_range(r, n, where, src):
    m = RANGE_RE.fullmatch(r)
    if not m:
        err(f"{where}: bad range {r!r}")
        return None
    a, b = int(m.group(1)), int(m.group(2) or m.group(1))
    if a < 1 or b < a or b > n:
        err(f"{where}: range {a}-{b} out of bounds for {src} ({n} lines)")
        return None
    return a, b


def check_expect(attrs, text, where):
    for exp in [e for e in attrs.get("data-expect", "").split("||") if e]:
        stats["expects"] += 1
        if exp not in text:
            err(f"{where}: data-expect {exp!r} not found in cited range")


def check_anchor(anchor, lines, a, where):
    heads = [(i + 1, re.match(r"#{1,6}\s+(.*?)\s*#*\s*$", l)) for i, l in enumerate(lines)]
    heads = [(n, re.sub(r"[^\w\- ]", "", m.group(1).lower()).replace(" ", "-")) for n, m in heads if m]
    if a is not None:
        if (a, anchor) not in heads:
            err(f"{where}: data-anchor {anchor!r} is not the heading on line {a}")
    elif anchor not in {s for _, s in heads}:
        err(f"{where}: data-anchor {anchor!r} matches no heading")


def check_fig(fig, sources, page):
    attrs = fig.attrs
    src, rng = attrs.get("data-src"), attrs.get("data-lines")
    where = f"{page}:{fig.line}:{src}:{rng}"
    if not src or not rng:
        err(f"{page}:{fig.line}: figure.code missing data-src/data-lines")
        return
    lines = sources.lines(src, where)
    if lines is None:
        return
    r = parse_range(rng, len(lines), where, src)
    if r is None:
        return
    a, b = r
    chunk = "\n".join(lines[a - 1:b])
    check_expect(attrs, chunk, where)
    stats["figures"] += 1
    pre = next((e for e in fig.descendants() if e.tag == "pre" and e.has("src")), None)
    code = next((e for e in pre.descendants() if e.tag == "code"), None) if pre else None
    if code is None:
        err(f"{where}: figure.code has no <pre class=\"src\"><code>")
    elif code.content() != chunk:
        err(f"{where}: code excerpt differs from lines {a}-{b} of the source; paste them verbatim")
    elif pre.attrs.get("data-start") != str(a):
        err(f"{where}: pre data-start must be {a}")


def check_a(el, sources, page):
    attrs = el.attrs
    src, rng, anchor = attrs.get("data-src"), attrs.get("data-lines"), attrs.get("data-anchor")
    where = f"{page}:{el.line}:link:{src}:{rng}"
    if not src:
        err(f"{page}:{el.line}: a.src missing data-src")
        return
    lines = sources.lines(src, where)
    if lines is None:
        return
    a = None
    if rng:
        r = parse_range(rng, len(lines), where, src)
        if r is None:
            return
        a, b = r
        check_expect(attrs, "\n".join(lines[a - 1:b]), where)
    else:
        check_expect(attrs, "\n".join(lines), where)
    if anchor:
        check_anchor(anchor, lines, a, where)
    stats["links"] += 1


# ---------- site checks ----------

def num(v, where, what):
    try:
        return float(v)
    except ValueError:
        err(f"{where}: {what} {v!r} is not a number")
        return None


def page_doc(target, docs):
    if target not in docs:
        docs[target] = Doc(target.read_text(encoding="utf-8"))
        add_generated_ids(docs[target])
    return docs[target]


def check_links(page, doc, docs):
    rel = page.relative_to(REPO)
    for el in doc.els:
        for k in ("href", "src"):
            if k not in el.attrs:
                continue
            v = el.attrs[k].strip()
            where = f"{rel}:{el.line}"
            if k == "href" and el.tag == "a" and (el.has("src") or (
                    el.parent is not None and el.parent.tag == "figcaption"
                    and el.parent.parent is not None and el.parent.parent.has("code"))):
                err(f"{where}: source link has an href; remove it, app.js builds it from data-src")
                continue
            if BLOB_RE.search(v):
                err(f"{where}: hard-coded repository link; use a.src with data-src so app.js builds it")
            if not v or EXTERNAL_RE.match(v) or "${" in v:
                continue
            stats["internal"] += 1
            u = urllib.parse.urlsplit(v)
            target = (page.parent / urllib.parse.unquote(u.path)).resolve() if u.path else page.resolve()
            if REPO not in target.parents:
                err(f"{where}: {k}={v!r} leaves the site")
            elif not target.is_file():
                err(f"{where}: {k}={v!r} points at a missing file")
            elif u.fragment and target.suffix == ".html":
                if urllib.parse.unquote(u.fragment) not in page_doc(target, docs).ids:
                    err(f"{where}: {k}={v!r} has no #{u.fragment} in {target.relative_to(REPO)}")


def glossary_ids(gdoc):
    # Mirrors the glossary popup map in assets/app.js: tr[id] rows plus any [id] inside them.
    ids = set()
    for tr in gdoc.els:
        if tr.tag == "tr" and tr.attrs.get("id"):
            ids.add(tr.attrs["id"])
            ids.update(d.attrs["id"] for d in tr.descendants() if d.attrs.get("id"))
    return ids


def check_gl(page, doc, gl_ids):
    rel = page.relative_to(REPO)
    for el in doc.els:
        if el.tag != "a" or not el.has("gl"):
            continue
        stats["gl"] += 1
        href = el.attrs.get("href", "")
        path, _, frag = href.partition("#")
        if not (path.endswith("glossary.html") or (page.name == "glossary.html" and path == "")):
            err(f"{rel}:{el.line}: a.gl href {href!r} does not point at glossary.html")
        elif frag not in gl_ids:
            err(f"{rel}:{el.line}: a.gl #{frag} is not a glossary row or alias id")


def scene_times(e, w):
    # Time syntax as parsed by prepare() in assets/motion.js.
    for k in ("data-in", "data-out"):
        if k in e.attrs:
            yield k, num(e.attrs[k], w, k)
    for win in filter(None, (x.strip() for x in e.attrs.get("data-on", "").split(","))):
        for x in win.split("-"):
            if x.strip():
                yield "data-on", num(x, w, "data-on")
    for k, sep in (("data-text", "|"), ("data-add", ",")):
        if k not in e.attrs:
            continue
        for part in filter(None, e.attrs[k].split(sep)):
            t, colon, _ = part.partition(":")
            if colon:
                yield k, num(t, w, k)
            else:
                err(f"{w}: {k} part {part!r} has no time:")


def check_scenes(page, doc):
    rel = page.relative_to(REPO)
    for fig in doc.els:
        if fig.tag != "figure" or not fig.has("scene"):
            continue
        stats["scenes"] += 1
        where = f"{rel}:{fig.line}"
        dur = fig.attrs.get("data-duration")
        D = num(dur, where, "data-duration") if dur is not None else None
        ats = []
        for ol in fig.children:
            if ol.tag != "ol" or not ol.has("beats"):
                continue
            for li in ol.children:
                if li.tag != "li":
                    continue
                v = num(li.attrs.get("data-at", "0"), f"{rel}:{li.line}", "data-at")
                if v is None:
                    continue
                ats.append(v)
                if D is not None and v > D:
                    err(f"{rel}:{li.line}: beat data-at {v:g} > data-duration {D:g}")
        if ats != sorted(ats):
            err(f"{where}: scene beats not sorted by data-at: {', '.join(f'{a:g}' for a in ats)}")
        if len(set(ats)) != len(ats):
            err(f"{where}: scene has duplicate beat data-at: {', '.join(f'{a:g}' for a in ats)}")
        if D is None:
            continue
        items = {}
        for svg in fig.descendants():
            if svg.tag == "svg" and any(a.has("stage") for a in svg.ancestors()):
                items.update((id(e), e) for e in svg.descendants())
        for e in items.values():
            w = f"{rel}:{e.line}"
            for k, t in scene_times(e, w):
                if t is not None and t > D + 1e-9:
                    err(f"{w}: {k} time {t:g} > data-duration {D:g}")


def check_graphs(page, doc):
    # Mirrors assets/graph.js: nodes are direct .cg-node children keyed by data-id.
    rel = page.relative_to(REPO)
    for fig in doc.els:
        if not fig.has("cgraph"):
            continue
        stats["graphs"] += 1
        ids = []
        for n in fig.children:
            if n.has("cg-node"):
                if not n.attrs.get("data-id"):
                    err(f"{rel}:{n.line}: cg-node without data-id")
                ids.append(n.attrs.get("data-id"))
        if len(set(ids)) != len(ids):
            err(f"{rel}:{fig.line}: duplicate cg-node data-id")
        known = set(ids) - {None, ""}
        for c in fig.children:
            if c.has("cg-edge"):
                for k in ("data-from", "data-to"):
                    if c.attrs.get(k) not in known:
                        err(f"{rel}:{c.line}: cg-edge {k}={c.attrs.get(k)!r} is not a cg-node data-id")
            if c.tag == "ol" and c.has("cg-tour"):
                for li in c.children:
                    if li.tag == "li" and li.attrs.get("data-focus") not in known:
                        err(f"{rel}:{li.line}: cg-tour data-focus={li.attrs.get('data-focus')!r} is not a cg-node data-id")


def check_hl(page, doc):
    rel = page.relative_to(REPO)
    for fig in doc.els:
        if fig.tag != "figure" or not fig.has("code") or "data-hl" not in fig.attrs:
            continue
        m = RANGE_RE.fullmatch(fig.attrs.get("data-lines", ""))
        if not m:
            continue
        a, b = int(m.group(1)), int(m.group(2) or m.group(1))
        for p in fig.attrs["data-hl"].split(","):
            hm = RANGE_RE.fullmatch(p)
            if not hm:
                err(f"{rel}:{fig.line}: bad data-hl part {p!r}")
            elif not (a <= int(hm.group(1)) <= int(hm.group(2) or hm.group(1)) <= b):
                err(f"{rel}:{fig.line}: data-hl {p.strip()} outside data-lines {a}-{b}")


def js_array(src, name):
    """Body of `const NAME = [ ... ]`, matched by bracket depth with strings skipped."""
    m = re.search(r"\bconst\s+" + name + r"\s*=\s*\[", src)
    if not m:
        return None
    depth, quote, i = 0, None, m.end() - 1
    while i < len(src):
        c = src[i]
        if quote:
            if c == "\\":
                i += 1
            elif c == quote:
                quote = None
        elif c in "\"'`":
            quote = c
        elif c in "[{(":
            depth += 1
        elif c in "]})":
            depth -= 1
            if depth == 0:
                return src[m.end():i]
        i += 1
    return None


def top_level_objects(body):
    items, depth, start = [], 0, 0
    for i, c in enumerate(body):
        if c == "{":
            if depth == 0:
                start = i
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                items.append(body[start:i + 1])
    return items


FILE_RE = re.compile(r"""\bfile\s*:\s*(["'])([^"']+)\1""")


def nav_files():
    # Mirrors DEEP_PAGES in assets/app.js: a topic with pages contributes its pages, not itself.
    js = (REPO / "assets" / "app.js").read_text(encoding="utf-8")
    layers, deep = js_array(js, "LAYERS"), js_array(js, "DEEP")
    if layers is None or deep is None:
        err("assets/app.js: could not find the LAYERS and DEEP nav arrays")
        return None
    files = [m.group(2) for m in FILE_RE.finditer(layers)]
    for item in top_level_objects(deep):
        pm = re.search(r"\bpages\s*:\s*\[", item)
        found = [m.group(2) for m in FILE_RE.finditer(item[pm.end():] if pm else item)]
        files += found if pm else found[:1]
    if not files:
        err("assets/app.js: nav arrays list no files")
        return None
    return files


def check_nav(pages):
    files = nav_files()
    if files is None:
        return
    for f in sorted(set(files)):
        if not (REPO / f).is_file():
            err(f"assets/app.js: nav lists missing page {f}")
    for p in pages:
        rel = p.relative_to(REPO).as_posix()
        if rel.split("/")[0] in ("chapters", "deep-dives") and files.count(rel) != 1:
            err(f"assets/app.js: {rel} appears {files.count(rel)} times in the nav (want exactly 1)")


# ---------- main ----------

def site_pages():
    top = [REPO / n for n in ("index.html", "glossary.html") if (REPO / n).exists()]
    return top + sorted(p for d in ("chapters", "deep-dives") for p in (REPO / d).rglob("*.html")
                        if not p.name.startswith("_"))


def main(argv):
    if argv:
        sys.exit(__doc__)
    if not (OSS_ROOT / "core" / "engine" / "runtime2").is_dir():
        sys.exit(f"{OSS_ROOT} is not the repository root; the site must stay in core/engine/runtime2/impldocs/website")
    sources = Sources(OSS_ROOT)

    pages = site_pages()
    docs = {}
    for p in pages:
        rel = p.relative_to(REPO)
        doc = Doc(p.read_text(encoding="utf-8"))
        for el in doc.els:
            if el.tag == "figure" and el.has("code"):
                check_fig(el, sources, rel)
            elif el.tag == "a" and el.has("src"):
                check_a(el, sources, rel)
        add_generated_ids(doc)
        docs[p.resolve()] = doc
        stats["pages"] += 1

    gloss = docs.get((REPO / "glossary.html").resolve())
    if gloss is None:
        err("glossary.html missing")
    gl_ids = glossary_ids(gloss) if gloss else set()
    for p in pages:
        doc = docs[p.resolve()]
        check_links(p, doc, docs)
        check_gl(p, doc, gl_ids)
        check_scenes(p, doc)
        check_graphs(p, doc)
        check_hl(p, doc)
    check_nav(pages)

    print(f"figures={stats['figures']} links={stats['links']} expects={stats['expects']}")
    print(f"pages={stats['pages']} internal-refs={stats['internal']} glossary-links={stats['gl']} "
          f"scenes={stats['scenes']} graphs={stats['graphs']}")
    if errors:
        print(f"\n{len(errors)} ERROR(S):")
        for e in errors:
            print("  " + e)
        sys.exit(1)
    print("OK")


if __name__ == "__main__":
    main(sys.argv[1:])
