# cloud-itonami-isic-2393: Manufacture of other porcelain and ceramic products

Open Business Blueprint for **ISIC Rev.5 2393**: manufacture of other porcelain and ceramic products — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **porcelain/ceramic-products plant operations**: production-batch data logging (product-type/weight/glaze-defect-rate/chip-resistance test results), forming-line/kiln-line-equipment maintenance scheduling, safety-concern flagging, and outbound porcelain/ceramic-product shipment coordination.

This repository designs a forkable OSS business for porcelain/ceramic-
products plant operations: run by a qualified operator so a tableware/
decorative-ceramics/technical-ceramics plant keeps its own operating
records instead of renting a closed SaaS.

## Scope: porcelain/ceramic tableware, decorative & technical ceramics, not refractories or clay building materials

ISIC 2393 covers the **porcelain/ceramic-products plant** that prepares
raw materials (kaolin, feldspar, quartz, ball clay), forms them
(jiggering/jolleying, RAM pressing, slip casting), glazes, then fires
in a kiln (bisque firing followed by glost firing) and inspects the
finished ware — producing porcelain tableware, bone-china tableware,
stoneware/earthenware tableware, decorative/ornamental ceramics, art
pottery, technical ceramics, and ceramic insulators: tableware,
decorative ceramics and technical/industrial ceramics for household,
decorative and industrial use. This is distinct from
`cloud-itonami-isic-2391` (Manufacture of refractory products), which
covers high-temperature-service furnace/kiln lining materials (fire
brick, kiln lining) rather than tableware/decorative/technical
ceramics, and from `cloud-itonami-isic-2392` (Manufacture of clay
building materials), which covers construction brick/tile rather than
porcelain/ceramic ware. This actor's own hazard profile is centered on
the kiln-firing line and glaze chemistry: kiln-fire/thermal-hazard
(radiant heat and burn risk at the bisque/glost firing zones),
silica-dust hazard (crystalline quartz/feldspar respirable dust
exposure at body preparation and forming), glaze-material-safety
hazard (lead/cadmium leaching risk from non-compliant glazes —
lead-free glaze compliance is a well-documented regulatory concern for
ceramic tableware), and forming-line-equipment pinch-point/crush
hazard (jiggering/jolleying machine, RAM press).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-type/weight/glaze-defect-rate/chip-resistance data logging (administrative, not an operational decision)
- `:schedule-maintenance` — forming-line/kiln-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a kiln-safety/thermal-hazard or glaze-material-safety (lead-free compliance) concern (always escalates)
- `:coordinate-shipment` — outbound porcelain/ceramic-product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(kiln firing line, thermal/burn hazard, glaze-material lead/cadmium
safety, silica-dust exposure, forming-line pinch-point hazard):

- Does NOT control the forming line or kiln line equipment directly
- Does NOT make plant-safety or glaze-material-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the forming line or kiln line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`porcelainmfg.operation/build`, a langgraph-clj StateGraph):
1. **`porcelainmfg.advisor`** (sealed intelligence node, `PorcelainAdvisor`): proposes decisions only, never commits
2. **`porcelainmfg.governor`** (independent, `Porcelain/Ceramic Plant Operations Governor`): validates against domain rules, re-derived from `porcelainmfg.registry`'s pure functions and `porcelainmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct forming-line/kiln-line-equipment control)
     - Directly actuating the forming line or kiln line (`:actuate-forming-kiln-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:glaze-defect-rate-percent` value on a production-batch patch
     - No physically implausible `:chip-resistance-newtons` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`porcelainmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`porcelainmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
