;; [![Build Status](https://travis-ci.org/solsort/fmtools.svg?branch=master)](https://travis-ci.org/solsort/fmtools) <img src=https://fmtools.solsort.com/icon.png align=right width=100 height=100>
;; # FM-Tools
;;
;; ## Formål
;;
;; Formålet er at lave en simpel app hvor det er let at udfylde rapporter fra FM-tools.
;;
;; Krav til app'en:
;;
;; - muligt at udfylde rapporterne, ud fra rapportskabelon bestående af linjer med felter
;; - understøtte dynamiske rapportskabeloner, hvor afsnit(linjer) af rapporten bliver gentaget for hver enhed på de forskellige niveauer. (eksempelvie projekt/tavle/anlæg/komponent)
;; - muligt at navigere mellem enheder på forskellige niveauer, og finde rapport for pågældende ehned
;; - forskellige former for felter, ie.: overskrifter/labels, tekstformulare, checkbokse, tal, dato, etc.
;; - muligt at vedhæfte/se billeder for hver linje i formularen
;; - formater: håndholdt mobil, samt tablet
;; - skal kunne funger/udfyldes offline, udfyldte formularer synkroniseres næste gang at der er internetforbindelse
;; - skal fungere på nyere Android og iOS, - enten som webapp, eller som hybrid app hvis ikke al nødvendig funktionalitet er tilgængelig via webbrowseren.
;;
;; ## Roadmap
;;
;; TODO next sprints
;; - disk data sync
;;   - sync/restore db
;;   - refactor/cleanup
;;   - debug performance
;;
;; Current sprint:
;; v0.0.7
;;
;; - reactive db lookup by path, ie.: (db :foo :bar) returns reaction on :bar of reaction of :foo
;; - √setup nrepl to work with cider
;; - restructure file
;;
;; ### Changelog
;; #### v0.0.6
;;
;; - progress better data sync to disk
;;   - write data structure to disk
;;   - GC/remove old nodes from disk
;;   - only write changes, fix delta function
;;   - escape string written, such that encoding for node
;;     references does not collide with disk.
;;   - load data structure from disk
;;   - make sure that diff is optimised (ie. do not traverse all data)
;; - start saving filled out data into app-db
;; - BUGFIX: text entry - read from db
;;
;; #### v0.0.5
;;
;; - do not select template directly, choose from open reports instead
;; - experiments towards faster/better synchronisation from app-db to disk
;;
;; #### v0.0.4
;;
;; - initial traverse/store report data into database, (needs mangling)
;; - traverse area/object tree structure / object-graph
;; - find current selected area, and render list of nodes based on this
;;
;; #### v0.0.3
;;
;; - try convert camera-image into dataurl for display
;; - area/object-tree - choose/show current object/area
;; - changelog/roadmap
;; - cors testing/debugging
;;
;; #### v0.0.2
;;
;; - offline version with cache manifest
;; - document data structure
;; - refactoring
;; - issue-tracking in documentation/file
;;
;; #### v0.0.1
;;
;; - checkbox component that writes to application database
;; - initial version of camera button (data not fetched yet)
;; - simple buggy rendition of templates, test that table-format also works on mobile (mostly)
;; - generic select widget
;; - choose current template (should be report later)
;; - responsive ui
;; - basic communication with api - load data
;; - Proxy api on demo-deploy-server
;;
;; ### Backlog
;;
;; v0.1.0
;;
;; - general
;;   - better data model / data mapping
;;     - function for mapping api-data to internal data
;;     - make implentation match documentation
;;       - templates should be list instead of object
;;       - `:lines` instead of `:rows: in template
;;       - new objects graph format
;;   - refactor/update code
;;   - expand this task list, as it gets solved
;; - fill out reports (templates of lines / with different kinds of fields)
;;   - generic widgets
;;   - fields
;;     - separate ids for double-checkboxes
;; - synchronise to disk / works offline
;;   - better performant sync of db to disk
;;     - use localforage instead of localstorage
;;     - check if async single-blob is ok performancewise
;; - dynamic templates (repeat lines based on objects)
;;   - repeat lines based on object-graph traversal
;; - sync data  to server
;; - attach/show images for each line in the report
;;   - photo capture
;;     - make sure react-img has proper properties
;;     - fetch data to db
;;   - show images
;; - works on mobile, and table. iOS, Android, (and Windows Phone if time permits)
;;
;; #### Later
;;
;; - proper horizontal labels (probably also needs extra option in backend)
;;
;; ## DB
;;
;; notes - intended content
;;
;; - `:objects` (NB: root oid)
;;   - oid
;;     - `:name`
;;     - `:ParentId` oid
;;     - `:children` oid-list
;;     - `:api-id` id used to identify it in the api
;; - `:templates` list
;;   - `:TemplateGuid`
;;   - `:Name`
;;   - `:Description`
;;   - `:lines` list
;;     - `:PartId`
;;     - `:TaskDescription`
;;     - `:LineType`
;;     - `:fields` list
;;       - `:FieldGuid`
;;       - `:FieldType`
;;       - `:Columns`
;;       - `:DoubleField`
;;       - `:DoubleFieldSeperator` (NB: typo in api)
;;       - `:FieldValue`
;; - `:raw-report`
;; - `:ui`
;;   - [report-id field-id object-id (optional 1/2)] value
;; - `:data` (intended, not implemented yet)
;;   - report-id
;;     - field-id
;;       - object-id
;;         - value
;;
;; ## Notes / questions about API
;;
;; I assume the following:
;;
;; - √ObjectId of objects are unique (no ObjectId occur in different AreaGuids)
;; - Field/part-data put/get
;;   - Might we not need ObjectID?
;;   - Why do we need more than one Guid to identify part of template?
;;
;; # Literate source code

(ns solsort.fmtools.main ; ##
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop alt!]]
    [reagent.ratom :as ratom :refer  [reaction]])
  (:require
   [devtools.core :as devtools]
    [cljs.pprint]
    [cljsjs.localforage]
    [cognitect.transit :as transit]
    [solsort.misc :refer [<blob-url]]
    [solsort.util
     :refer
     [<p <ajax <seq<! js-seq normalize-css load-style! put!close!
      parse-json-or-nil log page-ready render dom->clj next-tick]]
    [reagent.core :as reagent :refer []]
    [cljs.reader :refer [read-string]]
    [clojure.data :refer [diff]]
    [clojure.walk :refer [keywordize-keys]]
    [re-frame.core :as re-frame
     :refer [register-sub subscribe register-handler
             dispatch dispatch-sync]]
    [clojure.string :as string :refer [replace split blank?]]
    [cljs.core.async :as async :refer [>! <! chan put! take! timeout close! pipe]]))

;; ## Generic code and definitions
;;
;; Reload application, when a new version is available

(when js/window.applicationCache
  (aset js/window.applicationCache "onupdateready" #(js/location.reload)))

(defonce dev-tools (devtools/install!))

(defonce empty-choice "· · ·")

(defn clj->json [s] (transit/write (transit/writer :json) s))
(defn json->clj [s] (transit/read (transit/reader :json) s))
(defn third [col] (nth col 2))

(defn to-map ; ####
  [o]
  (cond
    (map? o) o
    (sequential? o) (zipmap (range) o)
    :else {}))

(defn delta ; ####
  "get changes from a to b"
  [from to]
  (if (= from to)
    (if (coll? to) {} to)
    (if (coll? to)
      (let [from (to-map from)
            to (to-map to)
            ks (distinct (concat (keys from) (keys to)))
            ks (filter #(not= (from %) (to %)) ks)]
        (into {} (map (fn [k]  [k (delta (from k) (to k))])  ks)))
      to)))

;; ## Definitions
;;
(defonce field-types ; ###
  {0   :none
   1   :text-fixed
   2   :text-input
   3   :checkbox
   4   :integer
   5   :decimal-2-digit
   6   :decimal-4-digit
   7   :date
   8   :time
   9   :text-fixed-noframe
   10  :text-input-noframe
   11  :approve-reject
   12  :fetch-from
   13  :remark
   100 :case-no-from-location})
(defonce part-types ; ###
  {0 :none
   1 :header
   2 :line
   3 :footer})
(defonce line-types ; ###
  {0  :basic
   1  :simple-headline
   2  :vertical-headline
   3  :horizontal-headline
   4  :multi-field-line
   5  :description-line
   10 :template-control})
;; ## Application database

;; ### :db
(register-sub
  :db
  (fn  [db [_ & path]]
    (reaction
      (if path
        (get-in @db path)
        @db))))

(register-handler
  :db
  (fn  [db [_ & path]]
    (let [value (last path)
          path (butlast path)]
      (if path
        (assoc-in db path value)
        value))))

;(dispatch-sync [:db {}])

;; ### :raw-report

(register-handler
  :raw-report
  (fn  [db [_ report data role]]
    (dispatch [:sync-to-disk])
    (-> db
        (assoc-in [:reports (:ReportGuid report)] report)
        (assoc-in [:raw-report (:ReportGuid report)]
                  {:report report
                   :data data
                   :role role}))))
;; ### :ui

(register-sub
  :ui (fn  [db [_ id]]  (reaction (get-in @db [:ui id]))) )
(register-handler
  :ui (fn  [db  [_ id data]] (assoc-in db [:ui id] data)))

;; ### :template/:templates

(register-sub
  :templates (fn  [db]  (reaction (keys (get @db :templates {})))))
(register-sub
  :template (fn  [db [_ id]]  (reaction (get-in @db [:templates id] {}))))
(register-handler
  :template
  (fn  [db  [_ id template]]
    (dispatch [:sync-to-disk])
    (assoc-in db [:templates id] template)))

;; ### :area-object

(register-sub
  :area-object (fn  [db [_ id]]  (reaction (get-in @db [:objects id] {}))))
(register-handler
  :area-object
  (fn  [db  [_ obj]]
    (let [id (:ObjectId obj)
          obj (into (get-in db [:objects id] {}) obj)
          area-guid (:AreaGuid obj)
          parent-id (:ParentId obj)
          db
          (if (zero? parent-id)
            (-> db
                (assoc-in [:objects :root :children area-guid] true)
                (assoc-in [:objects area-guid]
                          (or (get-in db [:objects area-guid])
                              {:ParentId 0
                               :AreaGuid area-guid
                               :ObjectId area-guid
                               :ObjectName (str (:AreaName obj))}))
                (assoc-in [:objects area-guid :children id] true)
                ; todo add in-between-node
                )
            (assoc-in db [:objects parent-id :children id] true))]
      (assoc-in db [:objects id] obj))))

;; ## Disk-sync

;; we are writing the changes to disk.
;; The structure of a json object like
;; `{a: 1, b: ['c', {d: 'e'}]}` is:
;;
;; - 0 {a: 1, b: '\u00011'}
;; - 1 ['c', '\u00012']
;; - 2 {d: 'e'}
;;
;; if first char of string has ascii value < 4 it is prefixed with "\u0000"
;;
;; references in db are "\u0001" followed by id
;;
;; keywords are "\u0002" followed by keyword

(defonce prev-id (atom nil))
(defonce sync-in-progress (atom false))
(defonce diskdb (atom {}))

(defn <chan-seq [arr] (async/reduce conj nil (async/merge arr)))
(defn esc-str [s] (if (< (.charCodeAt s 0) 32) (str "\u0001" s) s))
(defn optional-escape-string [o] (if (string? o) (esc-str o) o))
(defn unescape-string [s] (case (.charCodeAt s 0) 1 (.slice s 1) s))
(defn optional-unescape-string [o] (if (string? o) (unescape-string o) o))
(defn next-id [] (swap! prev-id inc) (str "\u0002" @prev-id))
(defn is-db-node [s] (and (string? s) (= 2 (.charCodeAt s))))
(defn fourth-first [[v _ _ k]] [k v])
(defn <localforage [k] (<p (.getItem js/localforage k)))
(defn save-changes ; ####
  "(value id key) -> (result-value, changes, deleted, key)"
  [value id k]
  (go
    (if (= value :keep-in-db)
      [id {} [] k]
      (let
        [db-str (and id (<! (<localforage id)))
         db-map (read-string (or db-str "{}"))
         value-map (to-map value)
         all-keys (distinct (concat (keys db-map) (keys value-map)))
         save-fn #(save-changes (get value-map % :keep-in-db) (db-map %) %)
         children (<! (<chan-seq (map save-fn all-keys)))
         new-id (if (coll? value) (next-id) nil)
         saves (if new-id {new-id (into {} (map fourth-first children))} {})
         saves (apply merge saves (map second children))
         deletes (apply concat (if db-str [id] []) (map third children))]
        [(or new-id (optional-escape-string value)) saves deletes k]))))

(defn <load-db-item [k]
  (go
    (let [v (read-string (<! (<localforage k)))
          v (map
              (fn [[k v]]
                (go
                  [k (if (is-db-node v)
                       (<! (<load-db-item v))
                       (optional-unescape-string v))]))
              v)
          v (into {}  (<! (<chan-seq v)))
          v (if (every? #(and (integer? %) (<= 0 %)) (keys v))
              (let [length  (inc (apply max (keys v)))]
                (into [] (map v (range length))))
              v)]
      v)))

(defn <load-db [] ; ####
  (when @sync-in-progress
    (throw "<load-db sync-in-progress error"))
  (go
    (reset! sync-in-progress true)
    (let [root-id (<! (<localforage "root-id"))
          result (if root-id (<! (<load-db-item root-id)) {})]
      (reset! diskdb result)
      (reset! sync-in-progress false)
      result)))

(defn <to-disk  ; ####
  [db]
  (go
    (let [changes (delta @diskdb db)
          id (or (<! (<p (.getItem js/localforage "root-id"))) " 0")
          prev-id (reset! prev-id (js/parseInt (.slice id 1)))
          [root-id chans deletes] (<! (save-changes changes id nil))]
      (log 'to-disk db changes)
      (<! (<chan-seq (for [[k v] chans]
                       (let [v (into {} (filter #(not (nil? (second %))) v))]
                         (<p (.setItem js/localforage k (prn-str v)))))))
      (<! (<p (.setItem js/localforage "root-id" root-id)))
      (<! (<chan-seq (for [k deletes] (<p (.removeItem js/localforage k)))))
      (reset! diskdb db))))

(defn <sync-db [db] ; ####
  (log 'sync-start)
  (go
    (if @sync-in-progress
      (log 'in-progress)
      (do
        (reset! sync-in-progress true)
        (<! (<to-disk db))
        (reset! sync-in-progress false)))))

;(<sync-db {(js/Math.random) [:a :b] :c [:d]})
(defonce sync-runner
  (go
    (log 'loading-db)
    (dispatch-sync [:ui (<! (<load-db))])
    (log 'loaded-db)
    (loop []
      (log 'start-sync)
      (let [t0 (js/Date.now)]
        (<! (<sync-db @(subscribe [:db :ui])))
        (log 'sync-time (- (js/Date.now) t0)))
      (<! (timeout 10000))
      (recur)
      )
    ))
(log 'ui @(subscribe [:db :ui]))

;; #### re-frame :sync-to-disk

(register-handler
  :sync-to-disk
  (fn  [db]
    ; currently just a hack, needs reimplementation on localforage
    ; only syncing part of structure that is changed
    ;(js/localStorage.setItem "db" (js/JSON.stringify (clj->json db)))
    ;(<sync-db db)
    db))

(register-handler
  :restore-from-disk
  (fn  [db]
    ;(json->clj (js/JSON.parse (js/localStorage.getItem "db")))
    ;(go (dispatch [:db (<! (<load-db))]) )
    ;(go (log 'db-restore [:db (<! (<load-db))]) )
    db ; disable restore-from-disk
    ))

(defonce restore (dispatch [:restore-from-disk]))

;; ## UI
;; ### Styling

(declare app)
(defonce unit (atom 40))
(defn style []
  (reset! unit (js/Math.floor (* 0.95 (/ 1 12) (js/Math.min 800 js/window.innerWidth))))
  (let [unit @unit]
    (load-style!
      {:#main
       {:text-align :center}

       :.line
       {:min-height 44}

       :.main-form
       {:display :inline-block
        :text-align :left
        :width (* unit 12)}

       :.camera-input
       {:display :inline-block
        :position :absolute
        :right 0 }

       :.fmfield
       {:vertical-align :top
        :display :inline-block
        :text-align :center
        :clear :right }

       :.checkbox
       {:width 44
        :max-width "95%"
        :height 44 }

       :.multifield
       {:border-bottom "0.5px solid #ccc"}

       ".camera-input img"
       {:height 40
        :width 40
        :padding 4
        :border "2px solid black"
        :border-radius 6
        :opacity "0.5" }

       :.fields
       {:text-align :center }
       }
      "fmstyling"))
  (render [app]))
(aset js/window "onresize" style)
(js/setTimeout style 0)

;; ### Generic Components

(defn select [id options] ; ####
  (let [current @(subscribe [:ui id])]
    (into [:select
           {:value (prn-str current)
            :onChange
            #(dispatch [:ui id (read-string (.-value (.-target %1)))])}]
          (for [[k v] options]
            (let [v (prn-str v)]
              [:option {:key v :value v} k])))))

(defn checkbox [id] ; ####
  (let [value @(subscribe [:ui id])]
    [:img.checkbox
     {:on-click #(dispatch [:ui id (not value)])
      :src (if value "assets/check.png" "assets/uncheck.png")}]))

(defn input ; ####
  [id & {:keys [type size max-length options]
         :or {type "text"}}]
  (case type
    :select (select id options)
    :checkbox (checkbox id)
    [:input {:type type
             :name (prn-str id)
             :key (prn-str id)
             :size size
             :max-length max-length
             :value @(subscribe [:ui id])
             :on-change #(dispatch [:ui id (.-value (.-target %1))])}]))

;; ### Camera button

(defn handle-file [id file]
  (go
    (dispatch [:ui :camera-image (<! (<blob-url file))])))

(defn camera-button []
  (let [id (str "camera" (js/Math.random))]
    (fn []
      [:div.camera-input
       [:label {:for id}
        [:img.camera-button {:src (or @(subscribe [:ui :camera-image])
                                      "assets/camera.png")}]]
       [:input
        {:type "file" :accept "image/*"
         :id id :style {:display :none}
         :on-change #(handle-file id (aget (.-files (.-target %1)) 0))
         }]
       ])))

;; ### Objects / areas

(defn areas [id] ; ####
  (let [obj @(subscribe [:area-object id])
        children (:children obj)
        selected @(subscribe [:ui id])
        child @(subscribe [:area-object selected])]
    (if children
      [:div
       [select id
        (concat [[empty-choice]]
                (for [[child-id] children]
                  [(:ObjectName @(subscribe [:area-object child-id])) child-id]))]
       (areas selected)]
      [:div])))

(defn selected-object [id] ; ####
  (let [selected @(subscribe [:ui id])]
    (if selected (selected-object selected) id)))

(defn find-objects [id] ; ####
  (apply concat [id]
         (map find-objects
              (keys (get @(subscribe [:db :objects id]) :children {})))))

(defn object-list [] ; ####
  (let [selected (selected-object :root)]
    (into [:div "Object ids:"] (interpose " " (map str (find-objects selected))))))


(defn field [obj cols id] ; ###
  (let [field-type (:FieldType obj)
        columns (:Columns obj)
        double-field (:DoubleField obj)
        double-separator (:DoubleFieldSeperator obj)
        value (:FieldValue obj)]
    [:span.fmfield {:key id
                    :style {:width (* 11 @unit (/ columns cols)) }
                    :on-click (fn [] (log obj) false)}
     (if double-field
       (let [obj (dissoc obj :DoubleField)]
         [:span [field obj cols (conj id 1)]
          " " double-separator " "
          [field obj cols (conj id 2)]])
       (case field-type
         :fetch-from "Komponent-id"
         :approve-reject [checkbox id]
         :text-fixed [:span value]
         :time [input id :type :time]
         :remark [input id]
         :text-input-noframe [input id]
         :text-input [input id]
         :decimal-2-digit [input id :size 2 :max-length 2 :type "number"]
         :checkbox [checkbox id]
         :text-fixed-noframe [:span value]
         [:strong "unhandled field:" (str field-type) " " value]))]))

(defn line [line report-id] ; ###
  (let [id (:PartGuid line)
        line-type (:LineType line)
        cols (apply + (map :Columns (:fields line)))
        desc (:TaskDescription line)
        debug-str (dissoc line :fields)
        obj-id nil
        fields (into
                 [:div.fields]
                 (map #(field % cols [report-id obj-id (:FieldGuid %)])
                      (:fields line)))]
    [:div.line
     {:style
      {:padding-top 10}
      :key id
      :on-click #(log debug-str)}
     (case line-type
       :basic [:h3 "" desc]
       :simple-headline [:h3 desc]
       :vertical-headline [:div [:h3 desc] fields]
       :horizontal-headline [:div [:h3 desc ] fields]
       :multi-field-line [:div.multifield desc [camera-button id ]
                          fields ]
       :description-line [:div desc [:input {:type :text}]]
       [:span {:key id} "unhandled line " (str line-type) " " debug-str])
     ]))

;; ### Main

(defn choose-report [] ; ####
  [:div.field
   [:label "Rapport"]
   [select :report-id
    (concat [[empty-choice]]
            (for [report-id  (keys @(subscribe [:db :reports]))]
              [@(subscribe [:db :reports report-id :ReportName])
               report-id]))]])

(defn choose-area [report] ; ####
  (if (:children @(subscribe  [:area-object (:ObjectId report)]))
    [:div.field
     [:label "Område"]
     [areas (or (:ObjectId report) :root)]]
    [:span.empty]))

(defn render-template [id] ; ####
  (let [template @(subscribe [:template id])
        report-id @(subscribe [:ui :report-id])]
    (merge
      [:div.ui.form
       [:h1 (:Description template)]]
      (doall (map line (:rows template) (repeat report-id)))
      )))

(defn app [] ; ####
  (let [report @(subscribe [:db :reports @(subscribe [:ui :report-id])])]
    [:div.main-form
     "Under development, not functional yet"
     [:h1 {:style {:text-align :center}} "FM-Tools"]
     [:hr]
     [:div.ui.container
      [:div.ui.form
       [choose-report]
       [choose-area report]
       ;[object-list]
       ]]
     [:hr]
     ;[render-template @(subscribe [:ui :current-template])]]))
     [render-template (:TemplateGuid report)]]))

;; ## Loading-Data

(defn <api [endpoint] ; ###
  (<ajax (str "https://"
              "fmtools.solsort.com/api/v1/"
              ;"app.fmtools.dk/api/v1/"
              ;(js/location.hash.slice 1)
              ;"@fmproxy.solsort.com/api/v1/"
              endpoint)
         :credentials true))

;; ### Templates

(defn load-template [template-id] ; ####
  (go
    (let [template (keywordize-keys
                     (<! (<api (str "ReportTemplate?templateGuid="
                                    template-id))))
          template (:ReportTemplateTable template)
          fields (-> template
                     (:ReportTemplateFields )
                     (->>
                       (map #(assoc % :FieldType (field-types (:FieldType %))))
                       (sort-by :DisplayOrer)
                       (group-by :PartGuid)))
          parts (-> template (:ReportTemplateParts))
          parts (map
                  (fn [part]
                    (assoc part :fields
                           (sort-by :DisplayOrder
                                    (get fields (:PartGuid part)))))
                  (sort-by :DisplayOrder parts))
          parts (map #(assoc % :LineType (or (line-types (:LineType %))
                                             (log "invalid-LintType" %))) parts)
          parts (map #(assoc % :PartType (part-types (:PartType %))) parts)]
      (dispatch [:template template-id (assoc template :rows parts)]))))

(defn load-templates [] ; ####
  (go
    (let [templates (<! (<api "ReportTemplate"))
          template-id (-> templates
                          (get "ReportTemplateTables")
                          (nth 0)
                          (get "TemplateGuid"))]
      (doall (for [template (get templates "ReportTemplateTables")]
               (load-template (get template "TemplateGuid")))))))

;; ### Objects
;;
(defn load-area [area] ; ####
  (go
    (let [objects (:Objects (keywordize-keys
                              (<! (<api (str "Object?areaGuid=" (:AreaGuid area))))))]
      (doall
        (for [object objects]
          (let [object (assoc object :AreaName (:Name area))]
            (dispatch [:area-object object])
            ))))))

(defn load-objects [] ; ####
  (go (let [areas (keywordize-keys (<! (<api "Area")))]
        (doall (for [area (:Areas areas)]
                 (load-area area))))))
;; ### Report

(defn load-report [report] ; ####
  (go
    (let [data (keywordize-keys (<! (<api (str "Report?reportGuid=" (:ReportGuid report)))))
          role (keywordize-keys (<! (<api (str "Report/Role?reportGuid=" (:ReportGuid report)))))]
      (dispatch [:raw-report report data role])
      (log 'report report data role))))

(defn load-reports [] ; ####
  (go
    (let [reports (keywordize-keys (<! (<api "Report")))]
      #_(log 'reports reports)
      (doall
        (for [report (:ReportTables reports)]
          (load-report report))))))

(defn handle-reports [] ; ####
  (let [raw-reports (:raw-report @(subscribe [:db]))]
    (doall
      (for [[_ raw-report] raw-reports]
        (let [report (:report raw-report)
              report-guid (:ReportGuid report)
              data (:ReportTable (:data raw-report))
              role (:role raw-report)]
          (do
            (log 'report report-guid data)
            (doall
              (for [entry (:ReportFields report)]
                (dispatch [:db report-guid (:FieldGuid entry) ()])))))))))
;(handle-reports)

;; ### fetch

(defn fetch [] ; ####
  ;  (log 'fetching)
  (load-templates)
  #_(go (let [user (keywordize-keys (<! (<api "User")))] (dispatch [:user user])))
  (load-objects)
  (load-reports))

;; #### Execute

;(fetch)
(defonce loader (fetch))

;; ### Experiments
(let [db @(subscribe [:db])]
  (log db))
