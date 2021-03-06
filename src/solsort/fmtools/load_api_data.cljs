(ns solsort.fmtools.load-api-data
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
   [solsort.fmtools.definitions :refer
    [line-types part-types field-types
     ReportTemplateTable ReportTemplateFields ReportTemplateParts
     ReportTables ReportTable Areas FieldGuid ReportFields Objects
     AreaGuid
     LineType PartType Name ReportGuid ReportName FieldType DisplayOrder PartGuid]]
   [solsort.toolbox.appdb :refer [db db!]]
   [solsort.fmtools.db :refer [api-db server-host]]
   [solsort.util :refer [<ajax log js-obj-push <chan-seq]]
   [cljs.core.async :as async :refer [<!]]))

(defn <api [endpoint & {:keys [data method]
                        :or {data nil method "GET"}}]
  (go
    #_(js/console.log "<api" endpoint)
    (<! (<ajax (str "https://" (server-host)
                    "/api/v1/"
                                        ;"app.fmtools.dk/api/v1/"
                                        ;(js/location.hash.slice 1)
                                        ;"@fmproxy.solsort.com/api/v1/"
                    endpoint)
               :method method
               :data data
               :credentials true))))

(defn obj [id] (get @api-db id {:id id}))
(defn obj! [o]
  (let [id (:id o)
        prev (get @api-db (:id o) {})
        o (into prev o)]
    (if id
      (swap! api-db assoc id o)
      (log 'obj! 'missing-id o))
    o))
(defn add-child! [parent child]
  (obj!
   {:id parent
    :children (distinct (conj (get (obj parent) :children []) child))}))

(defn group-by-part-guid [fields]
  (let [m #js {}]
    (doall (for [o fields] (js-obj-push m (PartGuid o) o)))
    (js->clj m)))
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
                                     :type :field}))))
          _
          (reset! api-db (into @api-db (map (fn [o] [(:id o) o]) fields)))
          fields (->> fields
                      (group-by-part-guid)
                      (map (fn [[k v]] [k (sort-by DisplayOrder v)]))
                      (into {}))
          parts (-> template (ReportTemplateParts))
          parts (map #(assoc % "LineType" (or (line-types (LineType %))
                                              (log "invalid-LintType" %))) parts)
          parts (map #(assoc % "PartType" (part-types (PartType %))) parts)
          parts (map #(obj! (into % {:id (get % "PartGuid")
                                     :type :part}))
                                        ; TODO replace this with (db! ...)
                     parts)
          parts (map
                 (fn [part]
                   (assoc part :fields
                          (get fields (PartGuid part))))
                 (sort-by DisplayOrder parts))]
      (obj! {:id (:id template) :children (distinct (map :id parts))})
      (doall (map (fn [[id children]]
                                        ; TODO replace this with (db! ...)
                    (obj! {:id id :children (distinct (map #(get % "FieldGuid") children))}))
                  fields))
      #_(log 'loaded-template
             (get template "Name")))))
(defn <load-templates []
  (go
    (let [templates (get (<! (<api "ReportTemplate")) "ReportTemplateTables")]
      (<! (<chan-seq (for [template templates]
                       (<load-template (get template "TemplateGuid")))))
      (obj! {:id :templates
             :type :root
             :children (distinct (map #(get % "TemplateGuid") templates))})
      #_(log 'loaded-templates))))

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
                    object))]
    (doall (map obj! objects))

    (doall
     (for [[parent-id children] (group-by :parent objects)]
       (obj! {:id parent-id
              :children (distinct (into (or (:children (obj parent-id)) [])
                                        (map :id children)))})))
    #_(log 'load-area (Name area))
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
                      :children (distinct (map #(get % "ObjectId") objects))})]
      (handle-area area objects))))
(defn <load-objects []
  (go (let [areas (<! (<api "Area"))]
        #_(log 'areas areas (Areas areas))
        (<! (<chan-seq (for [area (Areas areas)]
                         (<load-area area))))
        #_(log 'objects-loaded))))

(defn handle-report [report report-id data role table]
  (let [t0 (js/Date.now)
        parts
        (for [entry (get table "ReportParts")]
          (into entry
                {:id (get entry "PartGuid")
                 :type :part-entry}))
        fields
        (for [entry (get table "ReportFields")]
          (into entry
                {:id (get entry "FieldGuid")
                 :type :field-entry}))
        files
        (group-by #(get % "LinkedToGuid") (get table "ReportFiles"))
        objs (concat fields parts)]
    (obj! (into (obj :images) files))
    (reset! api-db (into @api-db (map (fn [o] [(:id o) o]) objs)))
    #_(log 'report (ReportName report) (- (js/Date.now) t0))))
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
      #_(log 'loaded-reports))))

(defn <load-controls []
  (go
    (let [controls (get (<! (<api "ReportTemplate/Control")) "ReportControls")]
      (doall (map (fn [ctl]
                    (obj! (into ctl
                                {:id (get ctl "ControlGuid")
                                 :type :control}))
                    (add-child! :controls (get ctl "ControlGuid")))
                  controls)))))

(defn init-root! []
  (obj! {:id :root :type :root
         :children [:areas :templates :reports :controls]})
  (obj! {:id :areas :type :root})
  (obj! {:id :controls :type :root})
  (obj! {:id :reports :type :root}))

(defn <load-api-db!
  "Fetch data from api, and save it in api-db"
  []
  (go
    (init-root!)
    (<! (<chan-seq
         [(<load-templates)
          (<load-objects)
          (<load-reports)
          (<load-controls)]))))
