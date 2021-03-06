(ns solsort.fmtools.api-client
  (:require-macros
   [com.rpl.specter.macros :refer [select transform]]
   [cljs.core.async.macros :refer [go go-loop alt!]])
  (:require
   [solsort.fmtools.definitions :refer [trail-types full-sync-types field-sync-fields part-sync-fields sync-fields saving-message]]
   [solsort.fmtools.db :refer [db db! api-db server-host]]
   [solsort.fmtools.load-api-data :refer [<load-api-db! init-root! <api]]
   [solsort.fmtools.data-index :refer [update-entry-index!]]
   [solsort.fmtools.disk-sync :as disk]
   [com.rpl.specter :as s]
   [clojure.set :as set]
   [solsort.util :refer [str->timestamp timestamp->isostring log <ajax <chan-seq]]
   [cljs.core.async :as async :refer [>! timeout]]))

(defonce needs-sync (atom {}))
(defn api-to-db! []
  (db! [:obj]
       (into
        (into
         (db [:obj])
         @api-db)
        (filter (fn [[k v]] (:local v)) (db [:obj])))))

(defn <update-state []
  (go
    (let [prev-sync (db [:obj :state :prev-sync] "2000-01-01")
          api-trail (->
                     (<! (<api (str "AuditTrail?trailsAfter=" prev-sync)))
                     (get "AuditTrails"))
          ;_ (log 'trail trail)
          trail (into (db [:obj :state :trail] #{})
                      (map #(assoc % :type (trail-types (get % "AuditType"))) api-trail))
          last-update (->> trail
                           (map #(get % "CreatedAt"))
                           (reduce max))
          last-update (max last-update prev-sync)
          last-update (if last-update
                        ; TODO: as trailsAfter include event at timestamp, instead of after timestamp, we increment last-update timestamp here. This should probably be fixed in the api, and then the workaround here should be removed
                        (.slice
                         (timestamp->isostring (inc (str->timestamp last-update)))
                         0 -1)
                        prev-sync)]
      (when-not (empty? api-trail)
        (db! [:obj :state]
             {:id :state
              :local true
              :prev-sync last-update
              :trail trail})))))
(defn updated-types []
  (into #{} (map :type (db [:obj :state :trail]))))

(init-root!)
(update-entry-index!)

(defn <do-fetch "unconditionally fetch all templates/areas/..."
  []
  (if (db [:loading])
    (go nil)
    (go (db! [:loading] "Loading from API")
        (<! (<load-api-db!))
        (api-to-db!)
        (db! [:obj :state :trail]
             (filter #(nil? (full-sync-types (:type %))) (db [:obj :state :trail])))
        (update-entry-index!)
        (db! [:loading] saving-message))))
(defn update-part-trail! [trail]
  (let [id (get trail "PrimaryGuid")
        o (db [:obj id])
        updated (into o {"Performed" (get trail "FieldBoolean")
                         "Remarks" (get trail "FieldString")
                         "Amount" (get trail "FieldInteger")})]
    (when (= o updated)
      (let [updated (dissoc updated :local)]
        (swap! api-db assoc id updated)
        (db [:obj id] updated)))))
(defn update-field-trail! [trail]
  (let [id (get trail "PrimaryGuid")
        o (db [:obj id])
        obj-key (:type trail)
        trail-key (str "Field" (clojure.string/replace obj-key #"Value[12]" ""))
        updated (assoc o obj-key (get trail trail-key))
        new (dissoc updated :local)]
    (swap! api-db assoc id new)
    (when (or
           (not (:local o))
           (= o updated))
      (swap! needs-sync dissoc id)
      (db! [:obj id] new))))
(defn update-image-trail! [o]
  (<do-fetch) ; TODO should be an incremental update, when API is updated...
  ;(log 'update-image-trail o)
)
(defn <fetch [] "conditionally update db"
  (go
    (<! (<update-state))
    (when-not (empty? (set/intersection full-sync-types (updated-types)))
      (<! (<do-fetch)))
    (when-not (empty? (db [:obj :state :trail]))
      (doall
       (for [o (db [:obj :state :trail])]
         (if (string? (:type o))
           (update-field-trail! o)
           (case (:type o)
             :part-changed (update-part-trail! o)
             :part-image (update-image-trail! o)
             (log (:type o) "not handled" o)))))
      (db! [:obj :state :trail] #{}))))

(defn sync-obj! [o]
;  (log 'sync-obj o) ; expensive logging...
  (when (and (:local o)
             (:type o))
    (swap! needs-sync assoc (:id o) o)))

(defn <sync-field! [o]
  (go
    (let [payload (clj->js (into {} (filter #(field-sync-fields (first %)) (seq o))))]
      (<! (<ajax
           (str
            "https://" (server-host)
            "/api/v1/Report/Field")
           :method "PUT" :data payload)))))
(defn <sync-part! [o]
  (go
    (let [api-obj (get @api-db (:id o))]
     ;(log 'sync-part o)
      (when-not (= (:control o)
                   (:control api-obj))
       ;(log 'update-control)
)
      (when-not
       (= (select-keys o part-sync-fields)
          (select-keys api-obj part-sync-fields))
        (let [payload (clj->js (into {} (filter #(part-sync-fields (first %)) (seq o))))]
     ;    (log 'upload-part)
          (<! (<ajax (str
                      "https://" (server-host)
                      "/api/v1/Report/Part")
                     :method "PUT" :data payload)))))))
(defn <sync-images! [o]
  (go
    (let [o (dissoc o :image-change)
          needs-remove (select [(s/filterer #(coll? (second %))) s/ALL s/LAST
                                (s/filterer #(:image-change %)) s/ALL]
                               o)
          o (transform
             [(s/filterer #(coll? (second %))) s/ALL s/LAST]
             (fn [imgs] (remove #(and (:image-change %) (:deleted %)) imgs))
             o)
          needs-update (select
                        [(s/filterer #(coll? (second %))) s/ALL s/LAST
                         (s/filterer #(:image-change %)) s/ALL]
                        o)]
      ;(log 'sync-images o needs-remove needs-update)
      (<!
       (<chan-seq
        (concat
         (for [img needs-remove]
           (go
             (when (get img "FileId")
               (<! (<ajax
                    (str
                     "https://" (server-host)
                     "/api/v1/Report/File?fileId="
                     (get img "FileId"))
                    :method "DELETE")))))
         (for [img needs-update]
           (go
             (<! (<ajax
                  (str
                   "https://" (server-host)
                   "/api/v1/Report/File")
                  :method "PUT"
                  :data
                  {"LinkedToGuid" (get img "LinkedToGuid")
                   "FileName" (get img "FileName")
                   "FileExtension" (get img "FileExtension")
                   "Base64Image" (clojure.string/replace
                                  (:data img)
                                  #"^[^,]*,"
                                  "")})))))))
      (db! [:obj :images] o)
      (swap! api-db assoc :images o))))

(defn <sync-to-server! []
  (go
    (let [objs (vals @needs-sync)]
      (when-not (empty? objs)
        (<!
         (<chan-seq
          (doall (for [o objs]
                   (do
                     (case (identity ; sometimes replace with log while debugging
                            (or
                             (= (select-keys o sync-fields)
                                (select-keys (get @api-db (:id o)) sync-fields))
                             (:type o)))
                       :field-entry (<sync-field! o)
                       :part-entry (<sync-part! o)
                       :images (<sync-images! o)
                       true (go (swap! needs-sync dissoc (:id o)))
                       (go (log 'no-sync-type o))))))))
       ;(reset! needs-sync {}) ; TODO: remove this line, when update through audittrail works
))))
(defn <sync! []
  (go
    (when js/navigator.onLine
      (go
        (<! (<sync-to-server!))
        (<! (<fetch))))
    (<! (timeout 3000))))
(defn init []
  (defonce -sync-loop
    (go-loop []
      (<! (<sync!))
      (recur))))
