"""Build the Decision Layer deck as a Neo4j-branded PPTX.

Text slides come from deck.md and are rendered onto the official Neo4j
template. The 16:9 diagrams in ../images are rasterised with rsvg-convert and
inserted full-bleed at their outline positions.

Usage:
    python3 -m venv .venv && .venv/bin/pip install python-pptx pyyaml
    .venv/bin/python build.py

Requires the Neo4j PPTX toolkit; point NEO4J_PPTX at it if it is not in
~/Downloads/neo4j-pptx.
"""
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
IMAGES = HERE.parent / "images"
BUILD = HERE / ".build"
OUT = HERE.parent / "the-decision-layer.pptx"

TOOLKIT = Path(os.environ.get("NEO4J_PPTX", Path.home() / "Downloads" / "neo4j-pptx"))
if not (TOOLKIT / "scripts" / "render_deck.py").exists():
    raise SystemExit(f"Neo4j PPTX toolkit not found at {TOOLKIT}. Set NEO4J_PPTX.")
sys.path.insert(0, str(TOOLKIT / "scripts"))

from pptx import Presentation
from parse_markdown import parse_markdown
from render_deck import render_deck
from slide_ops import move_slide_to, set_notes

# Diagrams are rendered at 2x the 1600x900 artboard so they stay crisp on a
# 4K projector without bloating the file.
RASTER_WIDTH, RASTER_HEIGHT = 3200, 1800

# (final 1-based slide number, SVG stem, speaker notes)
IMAGE_SLIDES = [
    (3, "smarter-agents-smarter-context", """\
The capability is a commodity: every competitor calls the same frontier model.

The context is missing: the model knows the world. It does not know your
business. How a process runs, which policy governs a step, and who can approve
an exception was never written down for an agent to read.

The context is the advantage: what you capture about how the business decides
is the only part nobody else can buy.

Commodity intelligence is the same for everyone. The business context an
enterprise builds around it is the advantage."""),

    (5, "decision-graph-anatomy", """\
The picture makes four claims: what was decided, what authorized it, what
modified it, and what it relied on."""),

    (6, "neo4j-agent-memory-diagram", """\
Long-term memory: the graph holds enterprise knowledge as nodes. Companies,
policies, underwriters, and thresholds all live there.

Short-term memory: Spring AI stores the conversation itself through
Neo4jChatMemoryRepository, in the same database.

Reasoning memory: Decision nodes hold what was decided and what authorized it.
This is the memory that becomes precedent.

One Neo4j instance runs all three. Only reasoning memory changes the next
outcome."""),

    (7, "decision-layer-memory-loop", """\
Every agent shares one advisor and one graph: resolve the context that governs
the request, record what was decided, reuse it as precedent."""),

    (9, "advisor-interception-point", """\
Interception: a CallAdvisor wraps the model call.

Two sides: the advisor sees the request going in and the response coming out.

Composition: advisors run as a chain, and the order decides what each one
sees."""),

    (10, "advisor-order-lifecycle", """\
Both decision advisors sit outside the tool loop, so context is read once and
the decision is written once per turn."""),

    (13, "memory-compounds", """\
The trace is written only after the model decides, and it outlives the
conversation, so the next request starts from what prior work already proved."""),

    (15, "cypher-company-anchored-query", """\
The search starts at the company and follows its applications to past
decisions. Three tests then decide which of those denials still count.

Ownership: the traversal starts at the company node and reaches its decisions
through SUBMITTED and ABOUT.

Age: the denial falls inside the window the policy sets.

Standing: no exception has set the denial aside.

The company anchors the search, and every test is a relationship, so one query
settles all three."""),

    (16, "graph-walk-authorization", """\
Start at one denial and follow its relationships. Each hop adds a piece of the
explanation: Company, Application, Prior Decision, Policy that governed it,
Exception that modified it, Later decisions it influenced.

APPLIED_POLICY names the rule that authorized the decision, and carries the
numbers that were measured.

EXCEPTION_TO, followed backward, finds the exception that set it aside.

ESCALATED_FROM, followed backward, finds every later decision it drove.

One traversal returns the authority, the modification, and the lineage
together."""),

    (17, "filter-then-rank", """\
Traversal decides which past decisions can govern this case. Similarity ranks
what survives.

Query, traverse identity, policy, time, standing, and lineage, collect the
decisions that can govern this case, rank that eligible set by semantic
similarity, then ground the model with the decision path.

The graph filters before anything reaches the model, so the prompt carries only
the decisions this case needs."""),

    (18, "second-run-sees-first", """\
Run one: the agent decides and writes a decision trace.

Run two: the agent reads that trace as standing precedent.

Everything else is fixed: company, amount, policies, and underwriter stay the
same.

The graph changed between the runs, so the context changed with it."""),

    (19, "lighter-agents-shared-layer", """\
Agents in the same system share data. They do not share reasoning. One graph is
the only channel they need, because what travels through it is the reasoning
behind a decision rather than the data it was about."""),

    (20, "capture-improve-autonomous", """\
Capture: every decision writes down its outcome and its authorization.
Approvals, overrides, and exceptions land in the same record.

Improve: each request starts from that record, so the next decision has
stronger context than the last one.

Autonomous: the agent acts alone on cases the record already shows how to
settle.

Autonomy becomes possible when the agent learns to decide the way your business
does. This talk builds the capture step. An autonomy gate reads from it."""),
]


def rasterise(stem):
    """Render one diagram, reusing the PNG when it is newer than its source."""
    svg = IMAGES / f"{stem}.svg"
    if not svg.exists():
        raise SystemExit(f"Missing diagram: {svg}")
    png = BUILD / f"{stem}.png"
    if png.exists() and png.stat().st_mtime >= svg.stat().st_mtime:
        return png
    BUILD.mkdir(exist_ok=True)
    subprocess.run(
        ["rsvg-convert", "-w", str(RASTER_WIDTH), "-h", str(RASTER_HEIGHT),
         str(svg), "-o", str(png)],
        check=True,
    )
    return png


def blank_layout(prs):
    """Pick the emptiest BLANK_* layout to base full-bleed image slides on."""
    blanks = [l for l in prs.slide_layouts if l.name.startswith("BLANK")]
    pool = blanks or list(prs.slide_layouts)
    return min(pool, key=lambda l: len(l.placeholders))


def add_full_bleed(prs, image_path, notes):
    slide = prs.slides.add_slide(blank_layout(prs))
    # Strip inherited placeholders so nothing sits under or over the artwork.
    for shape in list(slide.shapes):
        shape._element.getparent().remove(shape._element)
    slide.shapes.add_picture(str(image_path), 0, 0, prs.slide_width, prs.slide_height)
    if notes:
        set_notes(slide, notes)
    return slide


def main():
    pngs = {stem: rasterise(stem) for _, stem, _ in IMAGE_SLIDES}
    print(f"Rasterised {len(pngs)} diagrams into {BUILD}")

    _frontmatter, plan = parse_markdown((HERE / "deck.md").read_text(encoding="utf-8"))
    report = render_deck(
        slide_plan=plan,
        template_path=TOOLKIT / "assets" / "neo4j_template.pptx",
        catalog_path=TOOLKIT / "references" / "slide_layouts.json",
        output_path=OUT,
        markdown_dir=HERE,
    )
    print(report.summary())

    prs = Presentation(str(OUT))
    # Ascending target order, so each insertion lands after the previous ones.
    for pos, stem, notes in sorted(IMAGE_SLIDES):
        move_slide_to(prs, add_full_bleed(prs, pngs[stem], notes), pos - 1)
    prs.save(str(OUT))

    print(f"\nFinal deck: {len(Presentation(str(OUT)).slides._sldIdLst)} slides -> {OUT}")


if __name__ == "__main__":
    main()
