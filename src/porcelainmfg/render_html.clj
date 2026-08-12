(ns porcelainmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  operator-console sample and no generator at all. This namespace drives
  the REAL actor stack -- `porcelainmfg.operation/build` compiles the
  langgraph-clj StateGraph (advise -> govern -> decide -> commit | hold |
  request-approval) and every row on the generated page is read back out
  of the resulting `porcelainmfg.store` MemStore, its append-only ledger,
  and the `:audit` channel of the real graph runs.

  HAND-WRITTEN HTML IS NOT A SUBSTITUTE. Every id, number, disposition,
  hold reason and approver on the page is actor output. The only prose
  that is authored here is section copy and the `action-gate-rows`
  table, which documents this actor's FIXED closed op contract
  (`porcelainmfg.governor/allowed-ops`, `porcelainmfg.phase/phases`) --
  it describes code, not a run, and is labelled as such on the page.

  Determinism: the store carries no clock, no ledger fact carries a
  timestamp, `all-batches`/`all-equipment` sort by `:id`, and the
  ledger/history/safety-concern logs are append-ordered vectors -- so the
  document is byte-identical across reruns from the same seed. Nothing
  here reads the wall clock, `rand`, or the environment. If a clock is
  ever added to the store, pass an epoch-ms constant in from `-main`
  rather than calling `System/currentTimeMillis` here.

  Build-time invariant: `-main` REFUSES to write the file when the
  resulting ledger holds zero `:governor-hold` facts. The value of this
  page is that it shows a governor that actually says no; a scenario that
  has quietly stopped producing HARD holds is a broken demo, and it fails
  the build instead of publishing a page that only ever shows green.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [porcelainmfg.governor :as governor]
            [porcelainmfg.operation :as op]
            [porcelainmfg.store :as store]))

(def ^:private coordinator
  "The operator identity every run in this scenario is executed as --
  phase 3 (`supervised-auto`), the actor's `porcelainmfg.phase/default-phase`."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

;; ----------------------------- scenario -----------------------------

(def ^:private scenario
  "One entry per graph run. `:approve?` only marks that a human approver
  is STANDING BY -- the approval is submitted solely when the actor
  actually parks the run at `:request-approval`, so a scenario that
  stopped escalating cannot be dressed up as an approved commit.

  Requests are taken from this repo's own `porcelainmfg.sim` demo driver
  (`clojure -M:dev:run`), whose ids match `porcelainmfg.store`'s seeded
  `batch-001`..`batch-003` / `kiln-001` / `form-002` directory -- verified
  by running it BEFORE writing this file. Four clean paths (one phase-3
  auto-commit plus three human-approved commits) and ten HARD holds, one
  per governor rule, so the console shows every disposition this actor
  can reach."
  [{:thread "t01" :approve? false
    :note "clean production-batch patch -- the one op phase 3 may auto-commit"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :porcelain-tableware :last-assessed "2026-07-14"}}}

   {:thread "t02" :approve? true
    :note "tunnel kiln is verified + registered, no actuation -- never auto at any phase"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kiln-001" :maintenance-type :kiln-inspection
                      :scheduled-date "2026-08-01" :actuate-forming-kiln-line? false}}}

   {:thread "t03" :approve? true
    :note "a safety concern is always high-stakes -- two independent layers refuse to auto-commit it"
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "kiln-001" :severity :moderate
                      :description "glost窯出口付近の輻射熱上昇、鉛不使用釉薬コンプライアンス確認要"}}}

   {:thread "t04" :approve? true
    :note "batch-001 is verified + registered and has shipping headroom"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :weight-kg 5000.0
                      :destination "buyer-showroom-north"}}}

   {:thread "t05" :approve? true
    :note "a caller whose own request :effect is not :propose is mis-wired or compromised"
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :porcelain-tableware}}}

   {:thread "t06" :approve? true
    :note "an op outside the closed allowlist -- and its :noop proposal effect is outside the effect allowlist too"
    :request {:op :actuate-forming-line :effect :propose :subject "batch-001"}}

   {:thread "t07" :approve? true
    :note "form-002 has never been inspected or commissioned"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "form-002" :maintenance-type :die-inspection
                      :scheduled-date "2026-08-01" :actuate-forming-kiln-line? false}}}

   {:thread "t08" :approve? true
    :note "batch-003 has not been QC-verified and is not on the production ledger"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-003" :weight-kg 1000.0
                      :destination "buyer-showroom-south"}}}

   {:thread "t09" :approve? true
    :note "weight recomputed from the batch's own fields, never from the proposal's claim"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-002" :weight-kg 1000.0
                      :destination "buyer-showroom-east"}}}

   {:thread "t10" :approve? true
    :note "directly actuating the forming/kiln line is permanently outside this actor's authority"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "kiln-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01" :actuate-forming-kiln-line? true}}}

   {:thread "t11" :approve? true
    :note "mnt-1 again -- a dedicated :scheduled? fact, never a :status value"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "kiln-001" :maintenance-type :kiln-inspection
                      :scheduled-date "2026-08-01" :actuate-forming-kiln-line? false}}}

   {:thread "t12" :approve? true
    :note "a product type outside the closed known set"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :unobtainium-ware}}}

   {:thread "t13" :approve? true
    :note "a glaze-defect rate above 100% of the batch is not a physically possible defect count"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:glaze-defect-rate-percent 250.0}}}

   {:thread "t14" :approve? true
    :note "an edge chip-resistance reading no porcelain/ceramic ware reaches"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:chip-resistance-newtons 9999.0}}}])

(defn- exec!
  "One real graph run. Submits the approval ONLY when the actor really
  parked at `:request-approval` (`:disposition :escalate`) -- so an
  approved commit on the page always corresponds to an escalation the
  governor + phase gate actually produced."
  [actor {:keys [thread request approve? note]}]
  (let [r0 (g/run* actor {:request request :context coordinator}
                   {:thread-id thread})
        escalated? (= :escalate (get-in r0 [:state :disposition]))
        r1 (when (and approve? escalated?)
             (g/run* actor {:approval {:status :approved :by (:actor-id coordinator)}}
                     {:thread-id thread :resume? true}))
        final (or r1 r0)]
    {:thread      thread
     :note        note
     :op          (:op request)
     :subject     (:subject request)
     :verdict     (get-in r0 [:state :verdict])
     :disposition (get-in final [:state :disposition])
     :audit       (vec (distinct (concat (get-in r0 [:state :audit] [])
                                         (get-in r1 [:state :audit] []))))}))

(defn run-demo!
  "Seeds a fresh MemStore, compiles a real PorcelainOperationActor over
  it, and drives every `scenario` entry through the real graph. Returns
  `{:db <store> :runs [...]}` -- the store carries the committed SSoT +
  append-only ledger, the runs carry the per-thread governor verdict,
  phase-gate reason and audit trail. Nothing on the rendered page is
  computed anywhere but here."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv (partial exec! actor) scenario)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-str [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- code [v] (str "<code>" (esc (kw-str v)) "</code>"))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- num-cell [v]
  (str "<span class=\"num\">" (esc v) "</span>"))

(defn- row [cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n")
           (str (row ["<span class=\"muted\">no rows</span>"]) "\n"))
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- derived cells (all from actor output) -----------------------------

(defn- audit-entry [audit t]
  (first (filter #(= t (:t %)) audit)))

(defn- last-ledger-fact [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- governor-cell
  "The governor's own verdict map, rendered. `:hard?`/`:escalate?`/
  `:high-stakes?`/`:confidence` are read straight off
  `porcelainmfg.governor/check`'s return value."
  [{:keys [hard? escalate? high-stakes? confidence violations]}]
  (cond
    hard?
    (str "<span class=\"critical\">HARD &times; " (count violations) "</span> "
         (str/join ", " (map #(code (:rule %)) violations)))

    escalate?
    (str "<span class=\"warn\">clean, escalates</span> "
         (str/join " " (cond-> []
                         high-stakes? (conj "<span class=\"muted\">high-stakes</span>")
                         (< (double (or confidence 0.0)) governor/confidence-floor)
                         (conj (str "<span class=\"muted\">confidence &lt; "
                                    (esc governor/confidence-floor) "</span>")))))

    :else "<span class=\"ok\">clean</span>"))

(defn- phase-cell
  "What `porcelainmfg.phase/gate` did, taken from the audit fact the
  `:decide` node actually wrote (`:phase-reason` on a hold,
  `:reason`/`:phase` on an approval request)."
  [audit]
  (let [hold (audit-entry audit :governor-hold)
        ask  (audit-entry audit :approval-requested)]
    (cond
      (:phase-reason hold) (str "<span class=\"critical\">" (code (:phase-reason hold)) "</span>")
      hold                 "<span class=\"muted\">compliance hold stands</span>"
      ask                  (str "<span class=\"warn\">" (code (:reason ask)) "</span>"
                                " <span class=\"muted\">phase " (esc (:phase ask)) "</span>")
      :else                "<span class=\"ok\">auto-eligible</span>")))

(defn- human-cell
  "Whether a human ever saw this run -- derived from the audit trail, not
  asserted. A HARD hold produces no `:approval-requested` fact at all,
  which is precisely what 'never reaches a human' means here."
  [audit]
  (let [granted (audit-entry audit :approval-granted)
        ask     (audit-entry audit :approval-requested)
        hold    (audit-entry audit :governor-hold)]
    (cond
      granted (str "<span class=\"ok\">approved by " (esc (:by granted)) "</span>")
      ask     "<span class=\"warn\">awaiting approval</span>"
      hold    "<span class=\"critical\">never reached a human</span>"
      :else   "<span class=\"muted\">not required</span>")))

(defn- disposition-cell [d]
  (case d
    :commit   "<span class=\"ok\">committed</span>"
    :hold     "<span class=\"critical\">held</span>"
    :escalate "<span class=\"warn\">parked for approval</span>"
    (str "<span class=\"muted\">" (esc (kw-str d)) "</span>")))

(defn- ledger-status-cell [ledger subject]
  (let [f (last-ledger-fact ledger subject)]
    (cond
      (nil? f)                "<span class=\"muted\">no activity</span>"
      (= :committed (:t f))   "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> "
           (str/join ", " (map code (:basis f))))
      :else                   (str "<span class=\"muted\">" (esc (kw-str (:t f))) "</span>"))))

;; ----------------------------- rows -----------------------------

(defn- batch-row [ledger {:keys [id product-type material weight-kg shipped-weight-kg
                                 glaze-defect-rate-percent chip-resistance-newtons
                                 verified? registered? last-assessed]}]
  (row [(code id) (code product-type) (esc material)
        (num-cell weight-kg) (num-cell shipped-weight-kg)
        (num-cell glaze-defect-rate-percent) (num-cell chip-resistance-newtons)
        (yes-no verified?) (yes-no registered?) (esc last-assessed)
        (ledger-status-cell ledger id)]))

(defn- equipment-row [ledger {:keys [id kind verified? registered?
                                     last-maintenance-date last-scheduled-maintenance-date]}]
  (row [(code id) (code kind) (yes-no verified?) (yes-no registered?)
        (if last-maintenance-date (esc last-maintenance-date) "<span class=\"muted\">never</span>")
        (if last-scheduled-maintenance-date
          (esc last-scheduled-maintenance-date)
          "<span class=\"muted\">none scheduled</span>")
        (ledger-status-cell ledger id)]))

(defn- run-row [{:keys [thread op subject verdict disposition audit note]}]
  (row [(code thread) (code op) (code subject)
        (governor-cell verdict) (phase-cell audit) (human-cell audit)
        (disposition-cell disposition)
        (str "<span class=\"muted\">" (esc note) "</span>")]))

(defn- ledger-row [{:keys [t op actor subject basis violations confidence summary]}]
  (row [(code t) (code op) (esc actor) (code subject)
        (num-cell confidence)
        (if (seq violations)
          (str/join "<br>" (map #(str (code (:rule %)) " " (esc (:detail %))) violations))
          (str "<span class=\"muted\">" (esc (str/join ", " (map kw-str basis))) "</span>"))
        (esc (or summary ""))]))

(defn- maintenance-draft-row [r]
  (row [(code (get r "record_id")) (esc (get r "kind"))
        (code (get r "maintenance_id")) (code (get r "equipment_id"))
        (yes-no (get r "immutable"))]))

(defn- shipment-draft-row [r]
  (row [(code (get r "record_id")) (esc (get r "kind"))
        (code (get r "shipment_id")) (yes-no (get r "immutable"))]))

(def ^:private approver-note
  "Observed defect, rendered rather than papered over. `porcelainmfg.operation`'s
  `:request-approval` node builds the approved record as
  `(assoc (commit-record ..) :payload (assoc (:value proposal) :approved-by (:by approval)))`
  -- it attaches the approver to `:payload`, but
  `porcelainmfg.store/commit-record!` persists `:value`. So the approver
  who unblocked a commit is never written to the SSoT record, even though
  it IS in the append-only audit trail. The page says so instead of
  printing a name the store does not hold."
  (str "<strong>Known gap:</strong> the approver is <em>not</em> persisted on the committed "
       "record. <code>porcelainmfg.operation</code>'s <code>:request-approval</code> node "
       "attaches <code>:approved-by</code> to the record's <code>:payload</code>, but "
       "<code>porcelainmfg.store/commit-record!</code> persists <code>:value</code> — so the "
       "attribution is dropped before it reaches the SSoT. It survives only in the audit "
       "trail; see the <em>human</em> column of the Governed run timeline above."))

(defn- approver-cell [approved-by]
  (if approved-by
    (esc approved-by)
    "<span class=\"muted\">not on record — see run timeline</span>"))

(defn- concern-row [{:keys [id equipment-id severity description approved-by]}]
  (row [(code id) (code equipment-id) (code severity) (esc description)
        (approver-cell approved-by)]))

(defn- shipment-row [{:keys [id batch-id weight-kg destination shipment-number approved-by]}]
  (row [(code id) (code shipment-number) (code batch-id) (num-cell weight-kg)
        (esc destination)
        (approver-cell approved-by)]))

(defn- maintenance-row [{:keys [id equipment-id maintenance-type scheduled-date
                                maintenance-number scheduled? approved-by]}]
  (row [(code id) (code maintenance-number) (code equipment-id) (code maintenance-type)
        (esc scheduled-date) (yes-no scheduled?)
        (approver-cell approved-by)]))

(def ^:private action-gate-rows
  ;; Static description of this actor's own FIXED closed contract --
  ;; `porcelainmfg.governor/allowed-ops` + `porcelainmfg.phase/phases`.
  ;; It documents code, not a run, so it is legitimately authored here
  ;; rather than derived; the page labels it as such.
  [(row [(code :log-production-batch)
         "<span class=\"ok\">phase-3 auto-commit when governor-clean</span>"
         "product-type / glaze-defect-rate / chip-resistance each validated against a closed plausible range"])
   (row [(code :schedule-maintenance)
         "<span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span>"
         "equipment verified? AND registered? re-derived independently; double-schedule refused; direct forming/kiln-line actuation permanently blocked"])
   (row [(code :flag-safety-concern)
         "<span class=\"warn\">ALWAYS human approval &middot; high-stakes</span>"
         "never gated on the referenced equipment being verified — a concern may be raised about anything"])
   (row [(code :coordinate-shipment)
         "<span class=\"warn\">phase-3: human approval (not auto-eligible)</span>"
         "shipping headroom recomputed from the batch's own weight + shipped-to-date, never from the proposal's claim; un-computable headroom is not headroom"])])

;; ----------------------------- document -----------------------------

(defn render
  "Renders the operator console from the `{:db .. :runs ..}` map
  `run-demo!` returned. Reads only the store and the real run output."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2393 &middot; porcelain &amp; ceramic products plant operations</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of other porcelain and ceramic products (ISIC 2393) — Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\"><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">proposal-only — this actor never actuates a forming or kiln line</span></p>\n"
     "<main>\n"

     (section
      "This page"
      (str "Generated at build time by <code>porcelainmfg.render-html</code> "
           "(<code>clojure -M:dev:render-html</code>) by compiling the real "
           "<code>porcelainmfg.operation</code> langgraph actor over a freshly seeded "
           "<code>porcelainmfg.store</code> and running "
           (count runs) " coordination requests through it. Every id, number, verdict, "
           "hold reason and approver below is read back out of that run — the ledger "
           "closed with <span class=\"ok\">" (count commits) " commits</span> and "
           "<span class=\"critical\">" (count holds) " HARD holds</span>. "
           "The build refuses to write this file if the hold count is ever zero.")
      "")

     (section
      "Production batches"
      (str "Kiln-fired lots in the SSoT after the run. <code>verified?</code> means the batch's "
           "own product-type / glaze-defect-rate / chip-resistance claims were actually "
           "QC-inspected; <code>registered?</code> means it is on file in the production "
           "ledger. Both must hold before any shipment may be coordinated against it.")
      (table ["batch" "product type" "material" "weight kg" "shipped kg"
              "glaze defect %" "chip resist. N" "verified?" "registered?"
              "last assessed" "last ledger fact"]
             (map (partial batch-row ledger) (store/all-batches db))))

     (section
      "Forming / kiln line equipment"
      (str "Equipment units in the SSoT after the run. Maintenance may only ever be "
           "<em>scheduled</em> against a unit that is independently verified <em>and</em> "
           "registered — and the schedule is a draft, never an actuation.")
      (table ["unit" "kind" "verified?" "registered?" "last maintenance"
              "last scheduled" "last ledger fact"]
             (map (partial equipment-row ledger) (store/all-equipment db))))

     (section
      "Action gate (Porcelain/Ceramic Plant Operations Governor)"
      (str "The actor's fixed closed contract — <code>porcelainmfg.governor/allowed-ops</code> "
           "and <code>porcelainmfg.phase/phases</code>. This table documents code, not this "
           "run; the run itself is the next section. HARD holds cannot be overridden by any "
           "phase or any human.")
      (table ["op" "gate" "independent ground-truth checks"] action-gate-rows))

     (section
      "Governed run timeline"
      (str "One row per real graph run: the governor's own verdict, what "
           "<code>porcelainmfg.phase/gate</code> then did with it, whether a human was ever "
           "asked, and where the run landed. A HARD hold emits no approval request at all — "
           "that is what <em>never reached a human</em> means here.")
      (table ["thread" "op" "subject" "governor verdict" "phase gate" "human"
              "outcome" "scenario"]
             (map run-row runs)))

     (section
      "Scheduled maintenance windows"
      (str "Committed maintenance records. Each is a DRAFT window a plant coordinator keeps "
           "— the equipment is never touched by this actor. " approver-note)
      (table ["maintenance" "number" "equipment" "type" "scheduled date" "scheduled?" "approved by"]
             (map maintenance-row (store/all-maintenance db))))

     (section
      "Coordinated shipments"
      (str "Committed outbound shipment records. No freight carrier is ever dispatched by "
           "this actor. " approver-note)
      (table ["shipment" "number" "batch" "weight kg" "destination" "approved by"]
             (map shipment-row (keep #(store/shipment db (:id %))
                                     (map (fn [r] {:id (get r "shipment_id")})
                                          (store/shipment-history db))))))

     (section
      "Safety concerns"
      (str "The append-only safety-concern log. A safety concern is always high-stakes and "
           "always requires human eyes, regardless of confidence. " approver-note)
      (table ["concern" "equipment" "severity" "description" "approved by"]
             (map concern-row (store/safety-concerns db))))

     (section
      "Draft registry records"
      "The unsigned draft records <code>porcelainmfg.registry</code> constructed for each committed schedule/shipment. Signature is the human supervisor's act, never this actor's."
      (str (table ["record" "kind" "maintenance" "equipment" "immutable"]
                  (map maintenance-draft-row (store/maintenance-history db)))
           (table ["record" "kind" "shipment" "immutable"]
                  (map shipment-draft-row (store/shipment-history db)))))

     (section
      "Audit ledger (this run)"
      "The append-only decision-fact log — every commit and every hold this scenario produced, in order."
      (table ["fact" "op" "actor" "subject" "confidence" "basis / violation detail" "summary"]
             (map ledger-row ledger)))

     "</main>\n"
     "<footer><p>Generated by <code>porcelainmfg.render-html</code> from a real "
     "<code>porcelainmfg.operation</code> actor run. No hand-written figures; no timestamps; "
     "byte-identical across reruns from the same seed.</p></footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)]
    ;; Build-time invariant, not a convention: this console exists to show
    ;; a governor that actually refuses. A scenario that stopped producing
    ;; HARD holds is a broken demo -- fail the build rather than publish a
    ;; page that only ever shows green.
    (when (zero? (count holds))
      (throw (ex-info
              (str "REFUSING to write " out
                   ": the ledger contains ZERO :governor-hold facts. This console's whole "
                   "claim is that the Porcelain/Ceramic Plant Operations Governor can say no "
                   "and that a HARD hold never reaches a human. Publishing an all-green page "
                   "would assert the opposite. Fix the scenario in porcelainmfg.render-html/scenario "
                   "(or the governor) so at least one HARD hold is produced.")
              {:out out :ledger-facts (count ledger) :holds 0
               :commits (count commits) :runs (count runs)})))
    (io/make-parents out)
    (spit out (render result))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts: "
                  (count commits) " committed, " (count holds) " HARD holds; "
                  (count runs) " graph runs, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))
