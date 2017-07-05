(ns internal.transaction
  "Internal functions that implement the transactional behavior."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [internal.util :as util]
            [internal.graph :as ig]
            [internal.graph.types :as gt]
            [internal.node :as in]
            [internal.system :as is]
            [schema.core :as s]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Configuration parameters
;; ---------------------------------------------------------------------------
(def maximum-retrigger-count 100)
(def maximum-graph-coloring-recursion 1000)

;; ---------------------------------------------------------------------------
;; Internal state
;; ---------------------------------------------------------------------------
(def ^:dynamic *tx-debug* nil)
(def ^:dynamic *in-transaction?* nil)

(def ^:private ^java.util.concurrent.atomic.AtomicInteger next-txid (java.util.concurrent.atomic.AtomicInteger. 1))
(defn- new-txid [] (.getAndIncrement next-txid))

(defmacro txerrstr [ctx & rest]
  `(str (:txid ~ctx) ": " ~@(interpose " " rest)))

;; ---------------------------------------------------------------------------
;; Building transactions
;; ---------------------------------------------------------------------------
(defn new-node
  "*transaction step* - add a node to a graph"
  [node]
  [{:type     :create-node
    :node     node}])

(defn become
  [node-id to-node]
  [{:type     :become
    :node-id  node-id
    :to-node  to-node}])

(defn delete-node
  [node-id]
  [{:type     :delete-node
    :node-id  node-id}])

(defn new-override
  [override-id root-id traverse-fn]
  [{:type :new-override
    :override-id override-id
    :root-id root-id
    :traverse-fn traverse-fn}])

(defn override-node
  [original-node-id override-node-id]
  [{:type        :override-node
    :original-node-id original-node-id
    :override-node-id override-node-id}])

(defn transfer-overrides [from-node-id to-node-id id-fn]
  [{:type         :transfer-overrides
    :from-node-id from-node-id
    :to-node-id   to-node-id
    :id-fn        id-fn}])

(defn update-property
  "*transaction step* - Expects a node, a property label, and a
  function f (with optional args) to be performed on the current value
  of the property."
  [node-id pr f args]
  [{:type     :update-property
    :node-id  node-id
    :property pr
    :fn       f
    :args     args}])

(defn clear-property
  [node-id pr]
  [{:type     :clear-property
    :node-id  node-id
    :property pr}])

(defn update-graph-value
  [gid f args]
  [{:type :update-graph-value
    :gid  gid
    :fn   f
    :args args}])

(defn connect
  "*transaction step* - Creates a transaction step connecting a source node and label (`from-resource from-label`) and a target node and label
(`to-resource to-label`). It returns a value suitable for consumption by [[perform]]."
  [from-node-id from-label to-node-id to-label]
  [{:type         :connect
    :source-id    from-node-id
    :source-label from-label
    :target-id    to-node-id
    :target-label to-label}])

(defn disconnect
  "*transaction step* - The reverse of [[connect]]. Creates a
  transaction step disconnecting a source node and label
  (`from-resource from-label`) from a target node and label
  (`to-resource to-label`). It returns a value suitable for consumption
  by [[perform]]."
  [from-node-id from-label to-node-id to-label]
  [{:type         :disconnect
    :source-id    from-node-id
    :source-label from-label
    :target-id    to-node-id
    :target-label to-label}])

(defn disconnect-sources
  [basis target-node target-label]
  (for [[src-node src-label] (gt/sources basis target-node target-label)]
    (disconnect src-node src-label target-node target-label)))

(defn label
  [label]
  [{:type  :label
    :label label}])

(defn sequence-label
  [seq-label]
  [{:type  :sequence-label
    :label seq-label}])

(defn invalidate
  [node-id]
  [{:type :invalidate
    :node-id node-id}])

;; ---------------------------------------------------------------------------
;; Executing transactions
;; ---------------------------------------------------------------------------
(defn- pairs [m] (for [[k vs] m v vs] [k v]))

(defn- mark-input-activated
  [ctx node-id input-label]
  (if-let [nodes-affected (get-in ctx [:nodes-affected node-id] #{})]
    (let [basis (:basis ctx)
          dirty-deps (-> (gt/node-by-id-at basis node-id) (gt/node-type basis) in/input-dependencies (get input-label))]
      (update ctx :nodes-affected assoc node-id (reduce conj nodes-affected dirty-deps)))
    ctx))

(defn- mark-output-activated
  [ctx node-id output-label]
  (if-let [nodes-affected (get-in ctx [:nodes-affected node-id] #{})]
    (update ctx :nodes-affected assoc node-id (conj nodes-affected output-label))
    ctx))

(defn- mark-outputs-activated
  [ctx node-id output-labels]
  (if-let [nodes-affected (get-in ctx [:nodes-affected node-id] #{})]
    (update ctx :nodes-affected assoc node-id (reduce conj nodes-affected output-labels))
    ctx))

(defn- mark-all-outputs-activated
  [ctx node-id]
  (let [basis (:basis ctx)
        all-labels (-> (gt/node-by-id-at basis node-id)
                       (gt/node-type basis)
                       in/output-labels)]
    (update ctx :nodes-affected assoc node-id (set all-labels))))

(defn- next-node-id [ctx gid]
  (is/next-node-id* (:node-id-generators ctx) gid))

(defmulti perform
  "A multimethod used for defining methods that perform the individual
  actions within a transaction. This is for internal use, not intended
  to be extended by applications.

  Perform takes a transaction context (ctx) and a map (m) containing a
  value for keyword `:type`, and other keys and values appropriate to
  the transformation it represents. Callers should regard the map and
  context as opaque.

  Calls to perform are only executed by [[transact]]. The data
  required for `perform` calls are constructed in action functions,
  such as [[connect]] and [[update-property]]."
  (fn [ctx m] (:type m)))

(def ^:private ctx-disconnect)

(defn- ctx-disconnect-arc [ctx arc]
  (let [[src-id src-label] (gt/head arc)
        [tgt-id tgt-label] (gt/tail arc)]
    (ctx-disconnect ctx src-id src-label tgt-id tgt-label)))

(defn- disconnect-inputs [ctx tgt-id tgt-label]
  (reduce ctx-disconnect-arc ctx (ig/explicit-arcs-by-target (:basis ctx) tgt-id tgt-label)))

(defn- disconnect-all-inputs [ctx tgt-id]
  (reduce ctx-disconnect-arc ctx (ig/explicit-arcs-by-target (:basis ctx) tgt-id)))

(defn- disconnect-outputs [ctx src-id src-label]
  (reduce ctx-disconnect-arc ctx (ig/explicit-arcs-by-source (:basis ctx) src-id src-label)))

(defn- disconnect-stale [ctx node-id old-node new-node labels-fn disconnect-fn]
  (let [basis (:basis ctx)
        stale-labels (set/difference
                      (-> old-node (gt/node-type basis) labels-fn)
                      (-> new-node (gt/node-type basis) labels-fn))]
    (loop [ctx ctx
           labels stale-labels]
      (if-let [label (first labels)]
        (recur (disconnect-fn ctx node-id label) (rest labels))
        ctx))))

(defn- disconnect-stale-inputs [ctx node-id old-node new-node]
  (disconnect-stale ctx node-id old-node new-node in/input-labels disconnect-inputs))

(defn- disconnect-stale-outputs [ctx node-id old-node new-node]
  (disconnect-stale ctx node-id old-node new-node in/output-labels disconnect-outputs))

(def ^:private replace-node (comp first gt/replace-node))

(defmethod perform :become
  [ctx {:keys [node-id to-node]}]
  (if-let [old-node (gt/node-by-id-at (:basis ctx) node-id)] ; nil if node was deleted in this transaction
    (let [new-node (merge to-node (dissoc old-node :node-type))]
      (-> ctx
        (disconnect-stale-inputs node-id old-node new-node)
        (disconnect-stale-outputs node-id old-node new-node)
        (update :basis replace-node node-id new-node)
        (mark-all-outputs-activated node-id)))
    ctx))

(defn- delete-single
  [ctx node-id]
  (if-let [node (gt/node-by-id-at (:basis ctx) node-id)] ; nil if node was deleted in this transaction
    (-> ctx
      (disconnect-all-inputs node-id)
      (mark-all-outputs-activated node-id)
      (update :basis gt/delete-node node-id)
      (assoc-in [:nodes-deleted node-id] node)
      (update :nodes-added (partial filterv #(not= node-id %))))
    ctx))

(def ^:private reduce-conj (partial reduce conj))

(defn- cascade-delete-sources
  [basis node-id]
  (if-let [n (gt/node-by-id-at basis node-id)]
    (let [override-id (gt/override-id n)]
      (loop [inputs (some-> n
                      (gt/node-type basis)
                      in/cascade-deletes)
             result (vec (ig/get-overrides basis node-id))]
        (if-let [input (first inputs)]
          (let [explicit (map first (ig/explicit-sources basis node-id input))
                implicit (filter (fn [node-id] (when-let [n (gt/node-by-id-at basis node-id)]
                                                 (= override-id (gt/override-id n))))
                                 (map first (gt/sources basis node-id input)))]
            (recur (rest inputs) (-> result (reduce-conj explicit) (reduce-conj implicit))))
          result)))
    []))

(defn- ctx-delete-node [ctx node-id]
  (when *tx-debug*
    (println (txerrstr ctx "deleting " node-id)))
  (let [to-delete (ig/pre-traverse (:basis ctx) [node-id] cascade-delete-sources)]
    (when (and *tx-debug* (not (empty? to-delete)))
      (println (txerrstr ctx "cascading delete of " (pr-str to-delete))))
    (reduce delete-single ctx to-delete)))

(defmethod perform :delete-node
  [ctx {:keys [node-id]}]
  (ctx-delete-node ctx node-id))

(defmethod perform :new-override
  [ctx {:keys [override-id root-id traverse-fn]}]
  (-> ctx
    (update :basis gt/add-override override-id (ig/make-override root-id traverse-fn))))

(defn- flag-all-successors-changed [ctx nodes]
  (let [succ (get ctx :successors-changed)]
    (assoc ctx :successors-changed (reduce (fn [succ nid]
                                             (assoc succ nid nil))
                                           succ nodes))))

(defn- flag-successors-changed [ctx changes]
  (let [succ (get ctx :successors-changed)]
    (assoc ctx :successors-changed (reduce (fn [succ [nid label]]
                                             (if-let [node-succ (get succ nid #{})]
                                               (assoc succ nid (conj node-succ label))
                                               succ))
                                           succ changes))))

(defn- ctx-override-node [ctx original-id override-id]
  (assert (= (gt/node-id->graph-id original-id) (gt/node-id->graph-id override-id))
            "Override nodes must belong to the same graph as the original")
  (let [basis (:basis ctx)
        original (gt/node-by-id-at basis original-id)
        all-originals (take-while some? (iterate (fn [nid] (some->> (gt/node-by-id-at basis nid)
                                                             gt/original)) original-id))]
    (-> ctx
      (update :basis gt/override-node original-id override-id)
      (flag-all-successors-changed all-originals)
      (flag-successors-changed (mapcat #(gt/sources basis %) all-originals)))))

(defmethod perform :override-node
  [ctx {:keys [original-node-id override-node-id]}]
  (ctx-override-node ctx original-node-id override-node-id))

(declare apply-tx ctx-add-node)

(defn- node-id->override-id [basis node-id]
  (->> node-id
    (gt/node-by-id-at basis)
    gt/override-id))

(defn- ctx-make-override-nodes [ctx override-id node-ids]
  (reduce (fn [ctx node-id]
            (let [basis (:basis ctx)]
              (if (some #(= override-id (node-id->override-id basis %)) (ig/get-overrides basis node-id))
                ctx
                (let [gid (gt/node-id->graph-id node-id)
                     new-sub-id (next-node-id ctx gid)
                     new-sub-node (in/make-override-node override-id new-sub-id node-id {})]
                 (-> ctx
                   (ctx-add-node new-sub-node)
                   (ctx-override-node node-id new-sub-id))))))
          ctx node-ids))

(defn- populate-overrides [ctx node-id]
  (let [basis (:basis ctx)
        override-nodes (ig/get-overrides basis node-id)]
    (reduce (fn [ctx override-node-id]
              (let [oid (node-id->override-id basis override-node-id)
                    o (ig/override-by-id basis oid)
                    traverse-fn (:traverse-fn o)
                    node-ids (subvec (ig/pre-traverse basis [node-id] traverse-fn) 1)]
                (reduce populate-overrides
                        (ctx-make-override-nodes ctx oid node-ids)
                        override-nodes)))
      ctx override-nodes)))

(defmethod perform :transfer-overrides
  [ctx {:keys [from-node-id to-node-id id-fn]}]
  (let [basis (:basis ctx)
        override-nodes (ig/get-overrides basis from-node-id)
        retained (set override-nodes)
        override-ids (into {} (map #(let [n (gt/node-by-id-at basis %)] [% (gt/override-id n)]) override-nodes))
        overrides (into {} (map (fn [[_ oid]] [oid (ig/override-by-id basis oid)]) override-ids))
        nid->or (comp overrides override-ids)
        overrides-to-fix (filter (fn [[oid o]] (= (:root-id o) from-node-id)) overrides)
        nodes-to-delete (filter (complement retained) (mapcat #(let [o (nid->or %)
                                                                    traverse-fn (:traverse-fn o)
                                                                    nodes (ig/pre-traverse basis [%] traverse-fn)]
                                                                nodes)
                                                             override-nodes))]
    (-> ctx
      (update :basis (fn [basis] (gt/override-node-clear basis from-node-id)))
      (update :basis (fn [basis]
                       (reduce (fn [basis [oid o]] (gt/replace-override basis oid (assoc o :root-id to-node-id)))
                               basis overrides-to-fix)))
      ((partial reduce ctx-delete-node) nodes-to-delete)
      ((partial reduce (fn [ctx node-id]
                         (let [basis (:basis ctx)
                               n (gt/node-by-id-at basis node-id)
                               [new-basis new-node] (gt/replace-node basis node-id (gt/set-original n to-node-id))]
                           (-> ctx
                             (assoc :basis new-basis)
                             (mark-all-outputs-activated node-id)
                             (ctx-override-node to-node-id node-id)))))
        override-nodes)
      (populate-overrides to-node-id))))

(defn- property-default-setter
  [basis node-id node property _ new-value]
  (first (gt/replace-node basis node-id (gt/set-property node basis property new-value))))

(defn- invoke-setter
  [ctx node-id node property old-value new-value override-node? dynamic?]
  (let [basis (:basis ctx)
        node-type (gt/node-type node basis)
        value-type (some-> (in/property-type node-type property) deref in/schema s/maybe)]
   (if-let [validation-error (and in/*check-schemas* value-type (s/check value-type new-value))]
     (do
       (in/warn-output-schema node-id node-type property new-value value-type validation-error)
       (throw (ex-info "SCHEMA-VALIDATION"
                   {:node-id          node-id
                    :type             node-type
                    :property         property
                    :expected         value-type
                    :actual           new-value
                    :validation-error validation-error})))
     (let [setter-fn (in/property-setter node-type property)]
       (-> ctx
         (update :basis property-default-setter node-id node property old-value new-value)
         (cond->
           (not= old-value new-value)
           (cond->
             (not override-node?) (mark-output-activated node-id property)
             override-node? (mark-outputs-activated node-id (cond-> (if dynamic? [property :_properties] [property])
                                                              (not (gt/property-overridden? node property)) (conj :_overridden-properties)))
             (not (nil? setter-fn))
             (update :deferred-setters conj [setter-fn node-id property old-value new-value]))))))))

(defn apply-defaults [ctx node]
  (let [node-id (gt/node-id node)
        override-node? (some? (gt/original node))]
    (loop [ctx ctx
           props (keys (in/public-properties (gt/node-type node (:basis ctx))))]
      (if-let [prop (first props)]
        (let [ctx (if-let [v (get node prop)]
                    (invoke-setter ctx node-id node prop nil v override-node? false)
                    ctx)]
          (recur ctx (rest props)))
        ctx))))

(defn- ctx-add-node [ctx node]
  (let [[basis-after full-node] (gt/add-node (:basis ctx) node)
        node-id                 (gt/node-id full-node)]
    (-> ctx
      (assoc :basis basis-after)
      (apply-defaults node)
      (update :nodes-added conj node-id)
      (assoc-in [:successors-changed node-id] nil)
      (mark-all-outputs-activated node-id))))

(defmethod perform :create-node [ctx {:keys [node]}]
  (when (nil? (gt/node-id node)) (println "NIL NODE ID: " node))
  (ctx-add-node ctx node))

(defmethod perform :update-property [ctx {:keys [node-id property fn args] :as tx-step}]
  (let [basis (:basis ctx)]
    (when (nil? node-id) (println "NIL NODE ID: update-property " tx-step))
    (if-let [node (gt/node-by-id-at basis node-id)] ; nil if node was deleted in this transaction
      (let [;; Fetch the node value by either evaluating (value ...) for the property or looking in the node map
            ;; The context is intentionally bare, i.e. only :basis, for this reason
            ;; Normally value production within a tx will set :in-transaction? on the context
            old-value (in/node-property-value node property {:basis basis})
            new-value (apply fn old-value args)
            override-node? (some? (gt/original node))
            dynamic? (not (contains? (some-> (gt/node-type node basis) in/all-properties) property))]
        (invoke-setter ctx node-id node property old-value new-value override-node? dynamic?))
      ctx)))

(defn- ctx-set-property-to-nil [ctx node-id node property]
  (let [basis (:basis ctx)
        old-value (gt/get-property node basis property)
        node-type (gt/node-type node basis)]
    (if-let [setter-fn (in/property-setter node-type property)]
      (apply-tx ctx (setter-fn basis node-id old-value nil))
      ctx)))

(defmethod perform :clear-property [ctx {:keys [node-id property]}]
  (let [basis (:basis ctx)]
    (if-let [node (gt/node-by-id-at basis node-id)] ; nil if node was deleted in this transaction
      (let [dynamic? (not (contains? (some-> (gt/node-type node basis) in/all-properties) property))]
        (-> ctx
          (mark-outputs-activated node-id (cond-> (if dynamic? [property :_properties] [property])
                                            (and (gt/original node) (gt/property-overridden? node property)) (conj :_overridden-properties)))
          (update :basis replace-node node-id (gt/clear-property node basis property))
          (ctx-set-property-to-nil node-id node property)))
      ctx)))

(defn- ctx-disconnect-single [ctx target target-id target-label]
  (if (= :one (in/input-cardinality (gt/node-type target (:basis ctx)) target-label))
    (disconnect-inputs ctx target-id target-label)
    ctx))

(def ctx-connect)

(defn- ctx-add-overrides [ctx source-id source source-label target target-label]
  (let [basis (:basis ctx)
        target-id (gt/node-id target)]
    (if ((in/cascade-deletes (gt/node-type target basis)) target-label)
      (populate-overrides ctx target-id)
      ctx)))

(defn assert-type-compatible
  [basis src-id src-node src-label tgt-id tgt-node tgt-label]
  (let [output-nodetype (gt/node-type src-node basis)
        output-valtype  (in/output-type output-nodetype src-label)
        input-nodetype  (gt/node-type tgt-node basis)
        input-valtype   (in/input-type input-nodetype tgt-label)]
    (assert output-valtype
            (format "Attempting to connect %s (a %s) %s to %s (a %s) %s, but %s does not have an output or property named %s"
                    src-id (in/type-name output-nodetype) src-label
                    tgt-id (in/type-name input-nodetype) tgt-label
                    (in/type-name output-nodetype) src-label))
    (assert input-valtype
            (format "Attempting to connect %s (a %s) %s to %s (a %s) %s, but %s does not have an input named %s"
                    src-id (in/type-name output-nodetype) src-label
                    tgt-id (in/type-name input-nodetype) tgt-label
                    (in/type-name input-nodetype) tgt-label))
    (assert (in/type-compatible? output-valtype input-valtype)
            (format "Attempting to connect %s (a %s) %s to %s (a %s) %s, but %s and %s are not have compatible types."
                    src-id (in/type-name output-nodetype) src-label
                    tgt-id (in/type-name input-nodetype) tgt-label
                    (:k output-valtype) (:k input-valtype)))))

(defn- ctx-connect [ctx source-id source-label target-id target-label]
  (if-let [source (gt/node-by-id-at (:basis ctx) source-id)] ; nil if source node was deleted in this transaction
    (if-let [target (gt/node-by-id-at (:basis ctx) target-id)] ; nil if target node was deleted in this transaction
      (do
        (assert-type-compatible (:basis ctx) source-id source source-label target-id target target-label)
        (-> ctx
                                        ; If the input has :one cardinality, disconnect existing connections first
            (ctx-disconnect-single target target-id target-label)
            (mark-input-activated target-id target-label)
            (update :basis gt/connect source-id source-label target-id target-label)
            (flag-successors-changed [[source-id source-label]])
            (ctx-add-overrides source-id source source-label target target-label)))
      ctx)
    ctx))

(defmethod perform :connect
  [ctx {:keys [source-id source-label target-id target-label] :as tx-data}]
  (ctx-connect ctx source-id source-label target-id target-label))

(defn- ctx-remove-overrides [ctx source-id source-label target-id target-label]
  (let [basis (:basis ctx)
        target (gt/node-by-id-at basis target-id)]
    (if (contains? (in/cascade-deletes (gt/node-type target basis)) target-label)
      (let [src-or-nodes (map (partial gt/node-by-id-at basis) (ig/get-overrides basis source-id))]
        (loop [tgt-overrides (ig/get-overrides basis target-id)
               ctx ctx]
          (if-let [or (first tgt-overrides)]
            (let [basis (:basis ctx)
                  or-node (gt/node-by-id-at basis or)
                  override-id (gt/override-id or-node)
                  tgt-ors (filter #(= override-id (gt/override-id %)) src-or-nodes)
                  traverse-fn (ig/override-traverse-fn basis override-id)
                  to-delete (ig/pre-traverse basis (mapv gt/node-id tgt-ors) traverse-fn)]
              (recur (rest tgt-overrides)
                     (reduce ctx-delete-node ctx to-delete)))
            ctx)))
      ctx)))

(defn- ctx-disconnect [ctx source-id source-label target-id target-label]
  (-> ctx
      (mark-input-activated target-id target-label)
      (update :basis gt/disconnect source-id source-label target-id target-label)
      (flag-successors-changed [[source-id source-label]])
      (ctx-remove-overrides source-id source-label target-id target-label)))

(defmethod perform :disconnect
  [ctx {:keys [source-id source-label target-id target-label]}]
  (ctx-disconnect ctx source-id source-label target-id target-label))

(defmethod perform :update-graph-value
  [ctx {:keys [gid fn args]}]
  (-> ctx
      (update-in [:basis :graphs gid :graph-values] #(apply fn % args))
      (update :graphs-modified conj gid)))

(defmethod perform :label
  [ctx {:keys [label]}]
  (assoc ctx :label label))

(defmethod perform :sequence-label
  [ctx {:keys [label]}]
  (assoc ctx :sequence-label label))

(defmethod perform :invalidate
  [ctx {:keys [node-id] :as tx-data}]
  (if (gt/node-by-id-at (:basis ctx) node-id)
    (mark-all-outputs-activated ctx node-id)
    ctx))

(defn- apply-tx
  [ctx actions]
  (loop [ctx     ctx
         actions actions]
    (if-let [action (first actions)]
      (if (sequential? action)
        (recur (apply-tx ctx action) (next actions))
        (recur
          (update
            (try (perform ctx action)
              (catch Exception e
                (when *tx-debug*
                  (println (txerrstr ctx "Transaction failed on " action)))
                (throw e)))
            :completed conj action) (next actions)))
      ctx)))

(defn- mark-nodes-modified
  [{:keys [nodes-affected] :as ctx}]
  (assoc ctx :nodes-modified (set (keys nodes-affected))))

(defn map-vals-bargs
  [m f]
  (util/map-vals f m))

(defn- apply-tx-label
  [{:keys [basis label sequence-label] :as ctx}]
  (cond-> (update-in ctx [:basis :graphs] map-vals-bargs #(assoc % :tx-sequence-label sequence-label))

    label
    (update-in [:basis :graphs] map-vals-bargs #(assoc % :tx-label label))))

(def tx-report-keys [:basis :graphs-modified :nodes-added :nodes-modified :nodes-deleted :outputs-modified :label :sequence-label])

(defn- finalize-update
  "Makes the transacted graph the new value of the world-state graph."
  [{:keys [nodes-modified graphs-modified] :as ctx}]
  (-> (select-keys ctx tx-report-keys)
      (assoc :status (if (empty? (:completed ctx)) :empty :ok)
             :graphs-modified (into graphs-modified (map gt/node-id->graph-id nodes-modified)))))

(defn new-transaction-context
  [basis node-id-generators]
  {:basis               basis
   :nodes-affected      {}
   :nodes-added         []
   :nodes-modified      #{}
   :nodes-deleted       {}
   :outputs-modified    #{}
   :graphs-modified     #{}
   :successors-changed  {}
   :node-id-generators node-id-generators
   :completed           []
   :txid                (new-txid)
   :deferred-setters    []})

(defn update-successors
  [{:keys [successors-changed] :as ctx}]
  (update ctx :basis ig/update-successors successors-changed))

(defn- trace-dependencies
  [ctx]
  ;; at this point, :outputs-modified contains [node-id output] pairs.
  ;; afterwards, it will have the transitive closure of all [node-id output] pairs
  ;; reachable from the original collection.
  (let [outputs-modified (->> (:nodes-affected ctx)
                           (gt/dependencies (:basis ctx))
                           (into [] (mapcat (fn [[nid ls]] (mapv #(vector nid %) ls)))))]
    (assoc ctx :outputs-modified outputs-modified)))

(defn apply-setters [ctx]
  (let [setters (:deferred-setters ctx)
        ctx (assoc ctx :deferred-setters [])]
    (when (and *tx-debug* (not (empty? setters)))
      (println (txerrstr ctx "deferred setters" setters " with meta" (pr-str (map meta setters)))))
    (if (empty? setters)
      ctx
      (-> (reduce (fn [ctx [f node-id property old-value new-value :as deferred]]
                    (try
                      (if (gt/node-by-id-at (:basis ctx) node-id)
                        (apply-tx ctx (f (:basis ctx) node-id old-value new-value))
                        ctx)
                      (catch clojure.lang.ArityException ae
                        (println "ArityException while inside " f " on node " node-id " with " old-value new-value (meta deferred)
                                 (:node-type (gt/node-by-id-at (:basis ctx) node-id)))
                        (throw ae))
                      (catch Exception e
                        (let [node-type (:name @(:node-type (gt/node-by-id-at (:basis ctx) node-id)))]
                          (throw (Exception. (format "Setter of node %s (%s) %s could not be called" node-id node-type property) e))))))
                  ctx setters)
        recur))))

(defn transact*
  [ctx actions]
  (when *tx-debug*
    (println (txerrstr ctx "actions" (seq actions))))
  (with-bindings {#'*in-transaction?* true}
    (-> ctx
      (apply-tx actions)
      apply-setters
      mark-nodes-modified
      update-successors
      trace-dependencies
      apply-tx-label
      finalize-update)))

(defn in-transaction? []
  *in-transaction?*)
