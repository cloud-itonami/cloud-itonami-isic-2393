(ns porcelainmfg.render-html
  "Build-time HTML renderer for the operator console.

  Drives the REAL PorcelainOperationActor (`porcelainmfg.operation/build`
  -> a compiled langgraph-clj StateGraph) over the REAL seeded store
  (`porcelainmfg.store/sample-data!`), through the REAL Porcelain/Ceramic
  Plant Operations Governor (`porcelainmfg.governor/check`) and the REAL
  rollout phase gate (`porcelainmfg.phase/gate`), and renders whatever
  those produced. Nothing on the page is invented here:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`, `store/safety-concerns`,
      `store/maintenance-history`, `store/shipment-history`),
    - every HARD-hold rule name and every violation detail string is the
      governor's own `:violations` entry off the ledger fact -- never a
      literal in this namespace,
    - the phase gate table is derived from `porcelainmfg.phase/phases`,
      and the governor configuration / ground-truth bound tables from the
      public vars of `porcelainmfg.governor` and `porcelainmfg.registry`.

  The ONLY hand-written content is each scenario's `:exercises` sentence
  (what that request is meant to demonstrate) and the section prose --
  labelled where it appears. Every rule name, detail, number and status
  next to it comes from the run.

  Subject provenance (the demo may not invent subjects): every batch and
  equipment id driven below is seeded by `store/sample-data!`
  (`batch-001` `batch-002` `batch-003`, `kiln-001` `form-002`) --
  verified against the seed before this file was written. Every `mnt-*` /
  `ship-*` / `concern-*` subject is the draft record that its own op
  registers via `porcelainmfg.registry`.

  Deterministic: no clock, no randomness, no network. Re-running writes a
  byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [porcelainmfg.governor :as governor]
            [porcelainmfg.operation :as op]
            [porcelainmfg.phase :as phase]
            [porcelainmfg.registry :as registry]
            [porcelainmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`).

  Between them these exercise ALL ELEVEN of the governor's HARD checks
  plus its SOFT confidence/high-stakes gate. `:exercises` is the one
  hand-written field in this map -- prose describing intent; the verdict,
  rule names and details shown next to it on the page are the governor's
  own output."
  [{:tid "t01"
    :exercises "Routine production-batch logging against the seeded verified+registered porcelain-tableware batch. Governor-clean, and :log-production-batch is the ONE op in phase 3's :auto set -> auto-commit with no human in the loop."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :porcelain-tableware
                      :glaze-defect-rate-percent 2.1
                      :chip-resistance-newtons 185.0
                      :last-assessed "2026-07-14"}}}

   {:tid "t02"
    :exercises "Maintenance window against the verified+registered tunnel kiln. :schedule-maintenance is deliberately absent from EVERY phase's :auto set, so it escalates even though the governor is clean; the human plant supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kiln-001"
                      :maintenance-type :kiln-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-forming-kiln-line? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Kiln thermal-hazard / lead-free glaze compliance concern. Always :coordination/safety-concern stake, so the governor escalates regardless of confidence; the human approves."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "kiln-001" :severity :moderate
                      :description "glost窯出口付近の輻射熱上昇、鉛不使用釉薬コンプライアンス確認要"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Outbound shipment against a verified+registered batch with weight headroom (20000 kg logged, 4000 kg already shipped). Escalates; the human shipping approver approves and the batch's own shipped-weight advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :weight-kg 5000.0
                      :destination "buyer-showroom-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "A governor-CLEAN shipment that the human VETOES. Distinct from a HARD hold: compliance cleared it, a person declined it. Lands on the ledger as :approval-rejected, basis :approver-rejected."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-001" :weight-kg 1000.0
                      :destination "buyer-showroom-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Maintenance against the seeded jiggering machine, which is neither inspected nor on file. The governor re-derives verified?/registered? from the equipment's own record, never from the advisor's rationale. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "form-002"
                      :maintenance-type :die-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-forming-kiln-line? false}}}

   {:tid "t07"
    :exercises "Shipment against the seeded UNVERIFIED/unregistered technical-ceramic batch. Same ground-truth invariant, batch side. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-003" :weight-kg 1000.0
                      :destination "buyer-showroom-south"}}}

   {:tid "t08"
    :exercises "Shipment whose claimed weight would push the bone-china batch past its own logged production weight. The governor independently recomputes shipped-to-date + claim against the batch's own fields. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-002" :weight-kg 1000.0
                      :destination "buyer-showroom-east"}}}

   {:tid "t09"
    :exercises "Shipment stating NO weight at all. Un-checkable headroom is not headroom -- the governor refuses rather than let a proposal with no stated amount fall through the numeric guard. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-5"
              :value {:batch-id "batch-001"
                      :destination "buyer-showroom-north"}}}

   {:tid "t10"
    :exercises "A maintenance proposal that tries to ACTUATE the forming/kiln line rather than draft a window. Permanent scope boundary -- an approval is offered below and never reaches a human, because the hold happens first. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "kiln-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-forming-kiln-line? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t11"
    :exercises "The SAME maintenance window as t02, scheduled a second time. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kiln-001"
                      :maintenance-type :kiln-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-forming-kiln-line? false}}}

   {:tid "t12"
    :exercises "A batch patch declaring a product type outside the closed porcelain/ceramic family set. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:product-type :unobtainium-ware}}}

   {:tid "t13"
    :exercises "A batch patch claiming a glaze-defect rate above 100% of the batch -- not a physically possible defect count. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:glaze-defect-rate-percent 250.0}}}

   {:tid "t14"
    :exercises "A batch patch claiming an edge chip-resistance no porcelain/ceramic ware reaches. Implausible QC/sensor data, rejected rather than logged as a real batch fact. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:chip-resistance-newtons 9999.0}}}

   {:tid "t15"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked BEFORE anything else, so a compromised caller can never reach a commit path. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :porcelain-tableware}}}

   {:tid "t16"
    :exercises "An op outside the closed four-op allowlist. Both the op allowlist and the proposal-effect allowlist reject it -- two independent checks agree. HARD hold."
    :request {:op :actuate-forming-line :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario.
  Returns {:db store :runs [..]}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"muted\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "Every `:governor-hold` fact on the append-only ledger -- the HARD
  holds this run actually produced."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger after "
               "driving " (count runs) " requests through " (code "porcelainmfg.operation/build")
               ". Nothing here is a usage, revenue or performance claim.")
          (str
           (table ["Measure" "Count"]
                  [(tr "requests driven through the actor" (str "<span class=\"num\">" (count runs) "</span>"))
                   (tr "ledger facts written" (str "<span class=\"num\">" (count led) "</span>"))
                   (tr "commits" (str "<span class=\"ok num\">" (n :committed) "</span>"))
                   (tr "governor HARD holds" (str "<span class=\"critical num\">" (n :governor-hold) "</span>"))
                   (tr "human rejections" (str "<span class=\"critical num\">" (n :approval-rejected) "</span>"))
                   (tr "human approvals granted"
                       (str "<span class=\"num\">"
                            (count (filter #(= :approved (:human %)) runs)) "</span>"))])
           "<p class=\"muted\">Note: <code>:approval-granted</code> is emitted to the graph's in-memory "
           "<code>:audit</code> channel only — <code>porcelainmfg.operation</code> never appends it to the "
           "store ledger, so it is not a fact this page counts. An approved request is visible as the "
           "<code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (held before any interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one " (code "langgraph.graph/run*") " over the compiled actor. The governor "
             "column is the verdict map the governor itself returned; the human column is the decision "
             "handed back to the graph while it was paused at " (code ":request-approval") ".")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is one violation inside a " (code ":governor-hold") " fact on the append-only "
               "ledger. The rule name and the detail text are the governor's own " (code ":violations")
               " entries — this page holds no rule text of its own. A HARD hold is never offered to a "
               "human: no phase and no approval can override it.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (str "<span class=\"num\">" (fmt (:confidence h)) "</span>")
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r))
                         (str "<span class=\"num\">" (fmt (:confidence r)) "</span>"))))))))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "porcelainmfg.phase/phases") ". A governor HOLD always stays a HOLD; "
               "an op that may write but is not auto-eligible escalates to a human even when the governor "
               "is clean.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"critical\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "porcelainmfg.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "porcelainmfg.registry") " uses to re-derive the truth itself, rather "
             "than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid product types" (kw-codes registry/valid-product-types))
                (tr "glaze defect rate (%)"
                    (str (code registry/glaze-defect-rate-min-percent) " … "
                         (code registry/glaze-defect-rate-max-percent)))
                (tr "edge chip resistance (N)"
                    (str (code registry/chip-resistance-min-newtons) " … "
                         (code registry/chip-resistance-max-newtons)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining-kg [b]
  (let [w (:weight-kg b) s (:shipped-weight-kg b 0.0)]
    (if (and (number? w) (number? s)) (- (double w) (double s)) nil)))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches (kiln-fired lots)"
          (str "Read back from " (code "porcelainmfg.store/all-batches") " AFTER the run — so "
               (code ":shipped-weight-kg") " already includes whatever this run's approved shipments "
               "committed. All three batches are seeded by " (code "store/sample-data!") "; this demo "
               "invents no batch of its own. <em>Remaining</em> uses the same "
               (code "0.0") " default " (code "porcelainmfg.registry") " itself applies when it "
               "recomputes headroom.")
          (table ["Batch" "Product type" "Material" "Weight (kg)" "Shipped (kg)" "Remaining (kg)"
                  "Glaze defect (%)" "Chip resistance (N)" "verified?" "registered?" "ready?"
                  "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:product-type b)) (fmt (:material b))
                       (str "<span class=\"num\">" (fmt (:weight-kg b)) "</span>")
                       (str "<span class=\"num\">" (fmt (:shipped-weight-kg b)) "</span>")
                       (str "<span class=\"num\">" (fmt (remaining-kg b)) "</span>")
                       (str "<span class=\"num\">" (fmt (:glaze-defect-rate-percent b)) "</span>")
                       (str "<span class=\"num\">" (fmt (:chip-resistance-newtons b)) "</span>")
                       (flag (:verified? b)) (flag (:registered? b))
                       (if (registry/batch-ready? b)
                         "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Forming-line / kiln-line equipment"
        (str "Read back from " (code "porcelainmfg.store/all-equipment") ". An equipment id is never a "
             "request " (code ":subject") " in this domain (the maintenance draft id is), so no "
             "ledger-status column is shown for equipment — "
             (code ":last-scheduled-maintenance-date") " is the field the commit path actually writes "
             "onto an equipment record.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (if (registry/equipment-ready? e)
                       "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (str "<span class=\"num\">"
                          (esc (count (filter #(= (:id e) (:equipment-id %))
                                              (store/all-maintenance db))))
                          "</span>"))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "porcelainmfg.store/all-maintenance") ". The maintenance "
               "number is minted by " (code "porcelainmfg.registry/register-maintenance") " at commit "
               "time. Nothing here actuates any forming-line or kiln-line equipment.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date" "actuate-forming-kiln-line?"
                    "scheduled?" "Maintenance number"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m)) (flag (:actuate-forming-kiln-line? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [hist (store/shipment-history db)
        ships (keep #(store/shipment db (get % "shipment_id")) hist)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "porcelainmfg.store/shipment-history")
               " back to each stored shipment record. This is a draft a coordinator keeps — it "
               "dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Weight (kg)" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s))
                         (str "<span class=\"num\">" (fmt (:weight-kg s)) "</span>")
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log (" (code "porcelainmfg.store/safety-concerns")
               "). A concern may be raised against any equipment, verified or not — kiln thermal "
               "hazard, silica dust, glaze lead/cadmium compliance and forming-line pinch points are "
               "never blocked on an administrative technicality.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as " (code "porcelainmfg.store/ledger")
             " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (str "<span class=\"num\">" (esc (inc i)) "</span>")
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-2393 (porcelainmfg)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<span class=\"badge\">ISIC 2393</span>"
       "<span class=\"badge\">porcelainmfg</span>"
       "<span class=\"badge\">read-only sample</span>"
       "</header>\n"
       "<h1>Porcelain &amp; ceramic products plant operations — operator console</h1>"
       "<p class=\"subtitle\">governor "
       (code "porcelain-ceramic-plant-operations-governor") " · actor "
       (esc (:actor-id coordinator)) " · role " (code (:actor-role coordinator))
       " · phase " (esc (:phase coordinator))
       "</p>\n<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>porcelainmfg.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>porcelainmfg.operation</code> actor graph over the real "
       "<code>porcelainmfg.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "This actor proposes and coordinates only: it never controls forming-line or kiln-line "
       "equipment, and it makes no plant-safety or glaze-material-safety decision. "
       "No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is not
    ;; evidence of a governor. Refuse to write one.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :requests-driven (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
