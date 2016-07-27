(ns solsort.fmtools.api-client
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]])
  (:require
   [solsort.fmtools.definitions :refer
    [trail-types full-sync-types
     line-types part-types field-types
     ReportTemplateTable ReportTemplateFields ReportTemplateParts
     ReportTables ReportTable Areas FieldGuid ReportFields Objects
     AreaGuid
     LineType PartType Name ReportGuid ReportName FieldType DisplayOrder PartGuid]]
   [solsort.fmtools.util :refer [third to-map delta empty-choice <chan-seq <localforage fourth-first timestamp->isostring str->timestamp]]
   [solsort.fmtools.db :refer [db db! db-sync! obj obj!]]
   [solsort.fmtools.disk-sync :as disk]
   [clojure.set :as set]
   [solsort.util
    :refer
    [<p <ajax <seq<! js-seq normalize-css load-style! put!close!
     log page-ready render dom->clj next-tick]]
   [re-frame.core :as re-frame
    :refer [register-sub subscribe register-handler
            dispatch dispatch-sync]]
   [cljs.core.async :as async :refer [>! <! chan put! take! timeout close! pipe]]))

;; TODO more clear separation of object-loads, and restructure/write to db
(defn add-child! [parent child]
  (obj!
   {:id parent
    :children (distinct (conj (get (obj parent) :children []) child))}))

(defn <api [endpoint]
  (<ajax (str "https://"
              "fmtools.solsort.com/api/v1/"
                                        ;"app.fmtools.dk/api/v1/"
                                        ;(js/location.hash.slice 1)
                                        ;"@fmproxy.solsort.com/api/v1/"
              endpoint)
         :credentials true))

(defn <update-state []
  (go
    (let [prev-sync (or @(db :state :prev-sync) "2000-01-01")
          trail (->
                 (<! (<api (str "AuditTrail?trailsAfter=" prev-sync)))
                 (get "AuditTrails"))
          trail (into (or @(db :state :trail) #{})
                      (map #(assoc % :type (trail-types (get % "AuditType"))) trail))
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
      (db-sync! :state
                {:prev-sync last-update
                 :trail trail}))))

(defn updated-types []
  (into #{} (map :type @(db :state :trail))))

(defn <load-template [template-id]
  (go
    (let [template (<! (<api (str "ReportTemplate?templateGuid="
                                  template-id)))
          template (ReportTemplateTable template)
          template (obj! (into template
                               {:id (get template "TemplateGuid")
                                :type :template}))
          fields (-> template
                     (ReportTemplateFields)
                     (->>
                      (map #(assoc % "FieldType" (field-types (FieldType %))))
                      (map #(into % {:id (get % "FieldGuid")
                                     :type :field}))
                      (map obj!)
                      (sort-by DisplayOrder) ; TODO optimize
                      (group-by PartGuid)))
          parts (-> template (ReportTemplateParts))
          parts (map #(assoc % "LineType" (or (line-types (LineType %))
                                              (log "invalid-LintType" %))) parts)
          parts (map #(assoc % "PartType" (part-types (PartType %))) parts)
          parts (map #(obj! (into % {:id (get % "PartGuid")
                                     :type :part}))
                     parts)
          parts (map
                 (fn [part]
                   (assoc part :fields
                          (sort-by DisplayOrder
                                   (get fields (PartGuid part)))))
                 (sort-by DisplayOrder parts))]
      (obj! {:id (:id template) :children (map :id parts)})
      (doall (map (fn [[id children]]
                    (obj! {:id id :children (map #(get % "FieldGuid") children)}))
                  fields))
      (log 'loaded-template
           (get template "Name")
           ))))
(defn <load-templates []
  (go
    (let [templates (get (<! (<api "ReportTemplate")) "ReportTemplateTables")]
          (<! (<chan-seq (for [template templates]
                           (<load-template (get template "TemplateGuid")))))
          (log 'loaded-templates
               (obj! {:id :templates
                  :type :root
                      :children (map #(get % "TemplateGuid") templates)})))))

(defn handle-area [area objects]
  (let [objects (for [object objects]
                  (let [object (assoc object "AreaName" (Name area))
                        parent (get object "ParentId")
                        object (into object
                                     {:parent
                                      (if (zero? parent)
                                        (get object "AreaGuid")
                                        parent)
                                      :id (get object "ObjectId")
                                      :type :object})]
                    ;; NB: this is a tad slow - optimisation of [:area-object] would yield benefit
                    ;(dispatch-sync [:area-object object])
                    object))]
  (doall (map obj! objects))
  ;;TODO performance (db-sync! :obj (into @(db :obj) (map (fn [o] [(:id o) o]) objects)))

  (doall
   (for [[parent-id children] (group-by :parent objects)]
     (obj! {:id parent-id
            :children (into (or (:children (obj parent-id)) [])
                            (map :id children))})))
  (log 'load-area (Name area))
  (obj! area)
  (add-child! :areas (:id area))))

(defn <load-area [area]
  (go
    (let [objects (Objects
                   (<! (<api (str "Object?areaGuid=" (AreaGuid area)))))
          area (into area
                     {:id (get area "AreaGuid")
                      :parent :areas
                      :type :area
                      :children (map #(get % "ObjectId") objects)})]
      (handle-area area objects)
)))
(defn <load-objects []
  (go (let [areas (<! (<api "Area"))]
        (log 'areas areas (Areas areas))
        (<! (<chan-seq (for [area (Areas areas)]
                         (<load-area area))))
        (log 'objects-loaded))))

(defn handle-report [report report-id data role table]
  (let [t0 (js/Date.now)
        fields
        (for [entry (get table "ReportParts")]
          (into entry
                {:id (get entry "PartGuid")
                 :type :part-entry}))
        parts
        (for [entry (get table "ReportFields")]
          (into entry
                {:id (get entry "FieldGuid")
                 :type :field-entry}))
        files
        (for [entry (get table "ReportFiles")]
                (into entry
                            {:id (str (get entry "LinkedToGuid")
                                      "-"
                                      (get entry "FileId"))
                             :type :file-entry}))
        objs (concat fields parts files)
        ]
    (db-sync! :obj (into @(db :obj) (map (fn [o] [(:id o) o]) objs)))
    (log 'report (ReportName report) (- (js/Date.now) t0))))

(defn <load-report [report]
  (go
    (let [report-id (get report "ReportGuid")
          data (<! (<api (str "Report?reportGuid=" (ReportGuid report))))
          role (<! (<api (str "Report/Role?reportGuid=" (ReportGuid report))))
          table (get data "ReportTable")]
      (handle-report report report-id data role table))))

(defn <load-reports []
  (go
    (let [reports (<! (<api "Report"))]
      (<! (<chan-seq
           (for [report (ReportTables reports)]
             (let [report (into report {:id (get report "ReportGuid")
                                        :type :report})]
               (obj! report)
               (add-child! :reports (:id report))
               (<load-report report)))))
      (log 'loaded-reports))))
(defn <load-controls []
  (go
    (let [controls (get (<! (<api "ReportTemplate/Control")) "ReportControls")]
      (doall (map (fn [ctl]
                    (obj! (into ctl
                                {:id (get ctl "ControlGuid")
                                 :type :control}))
                    (add-child! :controls (get ctl "ControlGuid")))
                  controls)))))

(obj! {:id :root :type :root
       :children [:areas :templates :reports :controls]})
(obj! {:id :areas :type :root})
(obj! {:id :controls :type :root})
(obj! {:id :reports :type :root})

(defn <do-fetch "unconditionally fetch all templates/areas/..."
  []
  (go (dispatch-sync [:db :loading true])
      (<! (<chan-seq [(<load-objects)
                      (<load-reports)
                      (<load-controls)
                      (<load-templates)
                      #_(go (let [user (<! (<api "User"))] (dispatch [:user user])))]))
      (db-sync! :state :trail
                (filter #(nil? (full-sync-types (:type %))) @(db :state :trail)))
      (<! (disk/<save-form))
      (dispatch-sync [:db :loading false])))

(defn <fetch [] "conditionally update db"
  (go
    (<! (<update-state))
    (when-not (empty? (set/intersection full-sync-types (updated-types)))
      (<! (<do-fetch)))))
