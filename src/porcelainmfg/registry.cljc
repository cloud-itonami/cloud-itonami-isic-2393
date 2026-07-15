(ns porcelainmfg.registry
  "Pure-function domain logic for the porcelain/ceramic-products
  (tableware, decorative ceramics, technical/industrial ceramics)
  plant-operations coordination actor -- equipment/batch verification,
  shipment-weight recompute, product-type validation,
  glaze-defect-rate plausibility validation, chip-resistance
  plausibility validation, and draft maintenance-schedule/shipment-
  coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/porcelainmfg`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `porcelainmfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `refractorymfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2391`, the closest architectural sibling): never
  trust a proposal's own self-reported weight/status when the inputs
  needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating forming-line or
  kiln-line equipment, or dispatching a real freight carrier (this
  actor NEVER does either -- see README `What this actor does NOT
  do`).

  SCOPE NOTE: ISIC 2393 (this actor) covers OTHER PORCELAIN AND CERAMIC
  PRODUCTS -- porcelain/ceramic tableware, decorative ceramics, and
  technical/industrial ceramics: raw-material (kaolin, feldspar,
  quartz, ball clay) body preparation -> forming (jiggering/jolleying,
  RAM pressing, slip casting) -> glazing -> kiln-firing (bisque + glost
  firing) -> inspection production lines that produce porcelain
  tableware, bone-china tableware, stoneware/earthenware tableware,
  decorative/ornamental ceramics, art pottery, technical ceramics and
  ceramic insulators. This is distinct from `cloud-itonami-isic-2391`
  (Manufacture of refractory products), which covers high-temperature-
  service furnace/kiln lining materials rather than tableware/
  decorative/technical ceramics, and from `cloud-itonami-isic-2392`
  (Manufacture of clay building materials), which covers construction
  brick/tile rather than porcelain/ceramic ware. This actor's own
  hazard profile is centered on the kiln-firing line and glaze
  chemistry: kiln-fire/thermal-hazard (radiant heat and burn risk at
  the bisque/glost firing zones), silica-dust hazard (crystalline
  quartz/feldspar respirable dust exposure at body preparation and
  forming), glaze-material-safety hazard (lead/cadmium leaching risk
  from non-compliant glazes -- lead-free glaze compliance is a
  well-documented regulatory concern for ceramic tableware), and
  forming-line-equipment pinch-point/crush hazard (jiggering/jolleying
  machine, RAM press).")

;; ----------------------------- constants -----------------------------

(def valid-product-types
  "The closed set of product-type values a production batch (a
  kiln-fired lot) record may declare -- the standard porcelain/ceramic
  product families this actor's plant may produce. Anything else is a
  fabricated/unrecognized product type -- the governor HARD-holds
  rather than let an invented product type pass through."
  #{:porcelain-tableware :bone-china-tableware :stoneware-tableware
    :earthenware-tableware :decorative-ceramic :art-pottery
    :technical-ceramic :ceramic-insulator})

(def glaze-defect-rate-min-percent
  "Physical floor for a batch's own glaze-defect-rate reading (percent
  of pieces in the batch exhibiting a visible glaze defect --
  crazing/pinholing/crawling/blistering -- zero defects is the best
  possible outcome, never negative)."
  0.0)

(def glaze-defect-rate-max-percent
  "Physical ceiling for a batch's own glaze-defect-rate reading -- a
  rate above 100% of the batch is not a physically possible defect
  count. A reading above this is implausible inspection/QC data, not a
  real batch."
  100.0)

(def chip-resistance-min-newtons
  "Physical floor for a batch's own edge chip-resistance reading in
  newtons (an edge-chip test per e.g. ASTM C1327-style methodology --
  zero newtons survived is the worst possible outcome, never
  negative)."
  0.0)

(def chip-resistance-max-newtons
  "Physical ceiling for a batch's own edge chip-resistance reading in
  newtons -- no known porcelain/ceramic tableware or decorative/
  technical ceramic exceeds this edge-chip resistance. A reading above
  this is implausible sensor/QC data, not a real batch."
  500.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-type/weight/glaze-defect-rate/chip-resistance
  claims have actually been QC-inspected, not merely logged from an
  unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (> (+ (double so-far) (double new-weight-kg)) (double capacity)))))

(defn product-type-valid?
  "Is `product-type` one of the closed, known porcelain/ceramic product
  values? nil/blank is treated as invalid (a production-batch patch
  must declare a real product type, not omit it silently)."
  [product-type]
  (contains? valid-product-types product-type))

(defn glaze-defect-rate-valid?
  "Is `percent` a physically plausible batch glaze-defect-rate reading
  (percent of pieces in the batch with a visible glaze defect)?
  Rejects nil, non-numbers, negative values, and values beyond
  `glaze-defect-rate-max-percent` -- a fabricated or gauge-error
  reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) glaze-defect-rate-min-percent)
       (<= (double percent) glaze-defect-rate-max-percent)))

(defn chip-resistance-valid?
  "Is `newtons` a physically plausible batch edge chip-resistance
  reading, in newtons? Rejects nil, non-numbers, negative values, and
  values beyond `chip-resistance-max-newtons` -- a fabricated or
  sensor-error reading, never let through as a real batch fact."
  [newtons]
  (and (number? newtons)
       (>= (double newtons) chip-resistance-min-newtons)
       (<= (double newtons) chip-resistance-max-newtons)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  forming-line/kiln-line-equipment maintenance window against a
  verified, registered piece of equipment. Pure function -- does not
  actuate the forming-line or kiln-line equipment or execute any
  maintenance; it builds the RECORD a plant coordinator would keep.
  `porcelainmfg.governor` independently re-verifies the equipment's
  own verified/registered ground truth, and permanently blocks any
  attempt to directly actuate the forming-line/kiln-line equipment
  (see README `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound porcelain/ceramic product shipment against a verified,
  registered production batch. Pure function -- does not dispatch any
  real freight carrier; it builds the RECORD a plant coordinator would
  keep. `porcelainmfg.governor` independently re-verifies the
  shipment's own claimed weight against `shipment-weight-exceeded?`,
  before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
