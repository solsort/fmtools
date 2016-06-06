<!-- [![Stories in Ready](https://badge.waffle.io/solsort/fmtools.png?label=ready&title=Ready)](https://waffle.io/solsort/fmtools) -->
[![Build Status](https://travis-ci.org/solsort/fmtools.svg?branch=master)](https://travis-ci.org/solsort/fmtools)

# FM-Tools

Formålet er at lave en simpel app hvor det er let at udfylde rapporter fra FM-tools.

Krav til app'en:

- muligt at udfylde rapporterne, ud fra rapportskabelon bestående af linjer med felter
- understøtte dynamiske rapportskabeloner, hvor afsnit(linjer) af rapporten bliver gentaget for hver enhed på de forskellige niveauer. (eksempelvie projekt/tavle/anlæg/komponent)
- muligt at navigere mellem enheder på forskellige niveauer, og finde rapport for pågældende ehned
- forskellige former for felter, ie.: overskrifter/labels, tekstformulare, checkbokse, tal, dato, etc.
- muligt at vedhæfte/se billeder for hver linje i formularen
- formater: håndholdt mobil, samt tablet
- skal kunne funger/udfyldes offline, udfyldte formularer synkroniseres næste gang at der er internetforbindelse
- skal fungere på nyere Android og iOS, - enten som webapp, eller som hybrid app hvis ikke al nødvendig funktionalitet er tilgængelig via webbrowseren.

# Changelog

v0.0.3

- changelog/roadmap
- cors testing

## v0.0.2

- offline version with cache manifest
- document data structure
- refactoring
- issue-tracking in documentation/file

## v0.0.1

- checkbox component that writes to application database
- initial version of camera button (data not fetched yet)
- simple buggy rendition of templates, test that table-format also works on mobile (mostly)
- generic select widget
- choose current template (should be report later)
- responsive ui
- basic communication with api - load data
- Proxy api on demo-deploy-server

# Roadmap / Tasks

v0.1.0

- general
  - better data model / data mapping
    - function for mapping api-data to internal data
    - make implentation match documentation
      - templates should be list instead of object
      - `:lines` instead of `:rows: in template
      - new objects graph format
  - refactor/update code
  - expand this task list, as it gets solved
- fill out reports (templates of lines / with different kinds of fields)
  - generic widgets
  - fields
    - separate ids for double-checkboxes
- synchronise / works offline
  - better performant sync of db to disk
    - use localforage instead of localstorage
    - check if async single-blob is ok performancewise
- dynamic templates (repeat lines based on objects)
  - draw/choose object from object-graph
- navigate the object hierachy, and find the relevant report
- attach/show images for each line in the report
  - photo capture
    - make sure react-img has proper properties
    - fetch data to db
  - show images
- works on mobile, and table. iOS, Android, (and Windows Phone if time permits)

## Later

- proper horizontal labels (probably also needs extra option in backend)

# DB

notes - intended content

- `:objects` (NB: root oid)
  - oid
    - `:name`
    - `:parent` oid
    - `:children` oid-list
    - `:api-id` id used to identify it in the api
- `:templates` list
  - `:TemplateGuid`
  - `:Name`
  - `:Description`
  - `:lines` list
    - `:PartId`
    - `:TaskDescription`
    - `:LineType`
    - `:fields` list
      - `:FieldGuid`
      - `:FieldType`
      - `:Columns`
      - `:DoubleField`
      - `:DoubleFieldSeperator` (NB: typo in api)
      - `:FieldValue`

# Notes / questions about API

I assume the following:

- √ObjectId of objects are unique (no ObjectId occur in different AreaGuids)
- Field/part-data put/get 
  - Might we not need ObjectID? 
  - Why do we need more than one Guid to identify part of template?

# Literate source code



    (ns solsort.fmtools.main
      (:require-macros
        [cljs.core.async.macros :refer [go go-loop alt!]]
        [reagent.ratom :as ratom :refer  [reaction]])
      (:require
        [cljs.pprint]
        [cognitect.transit :as transit]
        [solsort.util
         :refer
         [<ajax <seq<! js-seq normalize-css load-style! put!close!
          parse-json-or-nil log page-ready render dom->clj]]
        [reagent.core :as reagent :refer []]
        [clojure.walk :refer [keywordize-keys]]
        [re-frame.core :as re-frame
         :refer [register-sub subscribe register-handler
                 dispatch dispatch-sync]]
        [clojure.string :as string :refer [replace split blank?]]
        [cljs.core.async :refer [>! <! chan put! take! timeout close! pipe]]))

# Util

Reload application, when a new versionis available
    (when js/window.applicationCache
      (aset js/window.applicationCache "onupdateready" #(js/location.reload)))

# Definitions

    (defonce field-types
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
    (defonce part-types
      {0 :none
       1 :header
       2 :line
       3 :footer})
    (defonce line-types
      {0  :basic
       1  :simple-headline
       2  :vertical-headline
       3  :horizontal-headline
       4  :multi-field-line
       5  :description-line
       10 :template-control})

# Application database
    (register-sub :db (fn  [db [_ id]]  (reaction @db)))
## UI

    (register-sub
      :ui (fn  [db [_ id]]  (reaction (get-in @db [:ui id]))) )
    (register-handler
      :ui (fn  [db  [_ id data]] (assoc-in db [:ui id] data)))

## Templates
    (register-sub
      :templates (fn  [db]  (reaction (keys (get @db :templates {})))))
    (register-sub
      :template (fn  [db [_ id]]  (reaction (get-in @db [:templates id] {}))))
    (register-handler
      :template
      (fn  [db  [_ id template]]
        (dispatch [:sync-to-disk])
        (assoc-in db [:templates id] template)))

## Objects
    (register-handler
      :area-object
      (fn  [db  [_ id object]]
        (assoc-in db [:objects id] object)))
    (register-handler
      :area-object-graph
      (fn  [db  [_ from to]]
        (assoc-in db [:object-graph from to] true)))

## Simple disk-sync
    (defn clj->json [s] (transit/write (transit/writer :json) s))
    (defn json->clj [s] (transit/read (transit/reader :json) s))

    (register-handler
      :sync-to-disk
      (fn  [db]
        ; currently just a hack, needs reimplementation on localforage
        ; only syncing part of structure that is changed
        (js/localStorage.setItem "db" (js/JSON.stringify (clj->json db)))
        db))

    (register-handler
      :restore-from-disk
      (fn  [db]
        (json->clj (js/JSON.parse (js/localStorage.getItem "db")))))
    (dispatch [:restore-from-disk])

# Styling

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
           {:clear :right }
           :.checkbox
           { :width 44
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
            :opacity "0.5"
            }
           :.fields
           {:text-align :center }
           }
          "check-style"))
      (render [app]))
    (aset js/window "onresize" style)
    (js/setTimeout style 0)

# Generic Components
## select
    (defn select [id options]
      (into [:select
             {:onChange
              #(dispatch [:ui id (.-value (.-target %1))])}]
            (for [[k v] options]
              [:option {:key v :value v} k])))

## checkbox

    (defn checkbox [id]
      (let [value @(subscribe [:ui id])]
        [:img.checkbox
         {:on-click #(dispatch [:ui id (not value)])
          :src (if value "assets/check.png" "assets/uncheck.png")}]))

# App layout
## Camera button

    (defn camera-button []
      (let [id (str "camera" (js/Math.random))]
        (fn []
          [:div.camera-input
           [:label {:for id}
            [:img.camera-button {:src "assets/camera.png"}]]
           ; TODO apparently :camera might not be a supported property in react
           [:input {:type "file" :capture "camera" :accept "image/*" :id id :style {:display :none}}]
           ])))

## field

    (defn field [field cols]
      (let [field-type (:FieldType field)
            columns (:Columns field)
            double-field (:DoubleField field)
            double-separator (:DoubleFieldSeperator field)
            guid (:FieldGuid field)
            value (:FieldValue field)]
        [:span.fmfield {:key guid
                        :style
                        {:width (* 11 @unit (/ columns cols))
                         :vertical-align :top
                         :display :inline-block
                         ;:border-left "1px solid black"
                         ;:border-right "1px solid black"
                         :text-align :center}
                        :on-click (fn [] (log field) false)}
         (case field-type
           :fetch-from "Komponent-id"
           :approve-reject
           (if double-field
             [:span [checkbox guid] " " double-separator " " [checkbox guid] " \u00a0 "]
             [checkbox guid])
           :text-fixed [:span.text-fixed-frame.outer-vertical
                        [:span.inner-vertical value]]
           :time [:input {:type :text :name guid}]
           :remark [:input {:type :text :name guid}]
           :text-input-noframe [:input {:type :text :name guid}]
           :text-input [:input {:type :text :name guid}]
           :decimal-2-digit
           [:div.ui.input
            [:input {:type :text :size 2 :max-length 2 :name guid}]]
           :checkbox
           (if double-field
             [:span [checkbox guid] " " double-separator " " [checkbox guid] " \u00a0 "]
             [checkbox guid])
           :text-fixed-noframe [:span.text-fixed-noframe value]
           [:strong "unhandled field:"
            (str field-type) " " value])
         ]))


## line

    (defn line [line]
      (let [id (:PartGuid line)
            line-type (:LineType line)
            cols (apply + (map :Columns (:fields line)))
            desc (:TaskDescription line)
            debug-str (dissoc line :fields)   
            fields (into
                     [:div.fields]
                     (map #(field % cols)  (:fields line)))]
        [:div.line
         {:style
          {:padding-top 10}
          :key id
          :on-click #(log debug-str)}
         (case line-type
           :basic [:h3 "" desc]
           :simple-headline [:h3 desc]
           #_:vertical-headline #_[:h3.vertical desc]
           :vertical-headline [:div [:h3 desc] fields]
           :horizontal-headline [:div [:h3 desc ] fields]
           :multi-field-line [:div.multifield desc [camera-button id ]
                              fields ]
           :description-line [:div desc [:input {:type :text}]]
           [:span {:key id} "unhandled line " (str line-type) " " debug-str])
         ]))

## template

    (defn render-template [id]
      (let [template @(subscribe [:template id])]
        ;(log (with-out-str (cljs.pprint/pprint template)))
        (merge
          [:div.ui.form
           [:h1 (:Description template)]]
          (map line (:rows template))
          ;[:pre (js/JSON.stringify (clj->js template) nil 2)]
          )))

## main
    (defn form []
      [:div.main-form
       [:div.ui.container
        [:div.ui.form
         [:div.field
          [:label "Skabelon"]
          [select :current-template

           (for [template-id  @(subscribe [:templates])]
             [(str (:Name @(subscribe [:template template-id])) " / "
                   (:Description @(subscribe [:template template-id])))
              template-id])]]]]
       [:hr]
       [render-template @(subscribe [:ui :current-template])]])

    (defn app []
      [:div

       [:h1 "FM-Tools"]
       [:hr]
       [form]
       ])

# Loading-Data
## <api

    (defn <api [endpoint]
      (<ajax (str "https://"
                  "fmtools.solsort.com/api/v1/"
                  ;"app.fmtools.dk/api/v1/"
                  ;(js/location.hash.slice 1)
                  ;"@fmproxy.solsort.com/api/v1/"
                  endpoint)
             :credentials true))

## Templates
    (defn load-template [template-id]
      (go
        (let [template (keywordize-keys
                         (<! (<api (str "ReportTemplate?templateGuid="
                                        template-id))))
              template (:ReportTemplateTable template)
              ; TODO: (group-by :ControlGuid (api/v1/ReportTemplate/Control :ReportControls)) into :template-control lines
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

    (defn load-templates []
      (go
        (let [templates (<! (<api "ReportTemplate"))
              template-id (-> templates
                              (get "ReportTemplateTables")
                              (nth 0)
                              (get "TemplateGuid"))]
          (doall (for [template (get templates "ReportTemplateTables")]
                   (load-template (get template "TemplateGuid")))))))

## Objects
    (defn load-area [area]
      (go
        (let [objects (:Objects (keywordize-keys
                                  (<! (<api (str "Object?areaGuid=" (:AreaGuid area))))))]
          (doall
            (for [object objects]
              (let [object (assoc object :AreaName (:Name area))]
                (dispatch [:area-object (:ObjectId object) object])
                (dispatch [:area-object-graph (:ParentId object) (:ObjectId object)])
                ))))))

    (defn load-objects []
      (go (let [areas (keywordize-keys (<! (<api "Area")))]
            (log 'areas (:Areas areas))
            (doall (for [area (:Areas areas)]
                     (load-area area)
                     )))))
## Report


    (defn load-report [report]
      (go
        (let [data (keywordize-keys (<! (<api (str "Report?reportGuid=" (:ReportGuid report)))))
              role (keywordize-keys (<! (<api (str "Report/Role?reportGuid=" (:ReportGuid report)))))]
          #_(log 'report report data role))))

    (defn load-reports []
      (go
        (let [reports (keywordize-keys (<! (<api "Report")))]
          #_(log 'reports reports)
          (doall
            (for [report (:ReportTables reports)]
              (load-report report))))))

## fetch

    (defn fetch []
      (load-templates)
      ;(go (let [user (keywordize-keys (<! (<api "User")))] (dispatch [:user user])))
      (load-objects)
      (load-reports))

    ;(fetch)

    (defonce loader (fetch))

## Experiments
    (let [db @(subscribe [:db])
          graph (:objects db)
          ]
      #_(log 'experiments db (count graph) (keys (graph 202)) (map (fn [[k v]] [(:AreaGuid v) k (:ObjectId v)]) (seq graph)))
      (log db)
      
      )
