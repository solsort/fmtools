(ns solsort.fmtools.ui
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop alt!]])
  (:require
   [solsort.fmtools.definitions :refer
    [ObjectName FieldType Columns DoubleFieldSeperator FieldValue LineType
     TaskDescription AreaGuid ObjectId PartGuid FieldGuid ColumnHeader
     TemplateGuid Description DoubleField]]
   [solsort.fmtools.util :refer [clj->json json->clj third to-map delta empty-choice <chan-seq <localforage fourth-first]]
   [solsort.misc :refer [<blob-url]]
   [solsort.fmtools.db :refer [db-async! db! db]]
   [solsort.fmtools.api-client :as api :refer [<fetch <do-fetch]]
   [solsort.fmtools.definitions :refer [field-types]]
   [solsort.util
    :refer
    [<p <ajax <seq<! js-seq normalize-css load-style! put!close!
     parse-json-or-nil log page-ready render dom->clj next-tick]]
   [reagent.core :as reagent :refer []]
   [cljs.reader :refer [read-string]]
   [clojure.data :refer [diff]]
   [clojure.string :as string :refer [replace split blank?]]
   [cljs.core.async :as async :refer [>! <! chan put! take! timeout close! pipe]]))

(defn warn [& args]
  (apply log "Warning:" args)
  nil)
(def get-obj solsort.fmtools.db/obj)

;;;; Main entrypoint
(declare loading)
(declare choose-area)
(declare choose-report)
(declare render-template)
(declare settings)
(defn app []
  (let [report (get-obj (db [:ui :report-id]))]
    [:div.main-form
     "Under development, not functional yet"
     [loading]
     [:h1 {:style {:text-align :center}} "FM-Tools"]
     [:hr]

     [:div.ui.container
      [:div.ui.form
       [:div.field
        [:label "Område"]
        [choose-area (if (db [:ui :debug]) :root :areas)]
        ]
       [choose-report]
       [:hr]
       [render-template report]
       [:hr]
       [settings]
       (if (db [:ui :debug])
         [:div
          [:h1 "DEBUG Log"]
          [:div (str (db [:ui :debug-log]))]]
         [:div])]]]))
(aset js/window "onerror" (fn [err] (db! [:ui :debug-log] (str err))))

;;;; Styling
(defonce unit (atom 40))
(defn style []
  (reset! unit (js/Math.floor (* 0.95 (/ 1 12) (js/Math.min 800 js/window.innerWidth))))
  (let [unit @unit]
    (load-style!
     {:#main
      {:text-align :center}

      :.line
      {;:min-height 44
}

      :.main-form
      {:display :inline-block
       :text-align :left
       :width (* unit 12)}

      :.camera-input
      {:display :inline-block
       :position :absolute
       :right 0}

      :.fmfield
      {:vertical-align :top
       :display :inline-block
       :text-align :center
       :clear :right}

      :.checkbox
      {:width 44
       :max-width "95%"
       :height 44}

      :.multifield
      {:padding-bottom 5
       :min-height 44
       :border-bottom "0.5px solid #ccc"}

      ".image-button"
      {:height 37
       :width 37
       :padding 4
       :border "2px solid black"
       :border-radius 6}
      ".camera-input img"
      {:opacity "0.5"}

      :.fields
      {:text-align :center}}
     "fmstyling"))
  (render [app]))
(aset js/window "onresize" style)
(js/setTimeout style 0)

;;;; Generic Components
(defn loading "simple loading indicator, showing when (db [:loading])" []
  (if (db [:loading])
    [:div
     {:style {:position :fixed
              :display :inline-block
              :top 0 :left 0
              :width "100%"
              :heigth "100%"
              :background-color "rgba(0,0,0,0.6)"
              :color "white"
              :z-index 100
              :padding-top (* 0.3 js/window.innerHeight)
              :text-align "center"
              :font-size "48px"
              :text-shadow "2px 2px 8px #000000"
              :padding-bottom (* 0.7 js/window.innerHeight)}}
     "Loading..."]
    [:span]))
(defn select [id options]
  (let [current (db id)]
    (into [:select
           {:style {:padding-left 0
                    :padding-right 0}
            :value (prn-str current)
            :onChange
            #(db-async! id (read-string (.-value (.-target %1))))}]
          (for [[k v] options]
            (let [v (prn-str v)]
              [:option {:style {:padding-left 0
                                :padding-right 0}
                        :key v :value v} k])))))
(defn checkbox [id]
  (let [value (db id)]
    [:img.checkbox
     {:on-click (fn [] (db-async! id (not value)) nil)
      :src (if value "assets/check.png" "assets/uncheck.png")}]))
(defn input  [id & {:keys [type size max-length options]
                    :or {type "text"}}]
  (case type
    :select (select id options)
    :checkbox (checkbox id)
    [:input {:type type
             :style {:padding-right 0
                     :padding-left 0
                     :text-align :center
                     :overflow :visible}
             :name (prn-str id)
             :key (prn-str id)
             :size size
             :max-length max-length
             :value (db id)
             :on-change #(db-async! id (.-value (.-target %1)))}]))
(defn- fix-height "used by rot90" [o]
  (let [node (reagent/dom-node o)
        child (-> node (aget "children") (aget 0))
        width (aget child "clientHeight")
        height (aget child "clientWidth")
        style (aget node "style")]
    (aset style "height" (str height "px"))
    (aset style "width" (str width "px"))))
(def rot90 "reagent-component rotating its content 90 degree"
  (with-meta
    (fn [elem]
      [:div
       {:style {:position "relative"
                :display :inline-block}}
       [:div
        {:style {:transform-origin "0% 0%"
                 :transform "rotate(-90deg)"
                 :position "absolute"
                 :top "100%"
                 :left 0
                 :display :inline-block}}
        elem]])
    {:component-did-mount fix-height
     :component-did-update fix-height}))
(identity js/window.location.href)
;;; Camera button
(defn handle-file [id file]
  (go (let [image (<! (<blob-url file))]
        (log 'TODO-images id image)
        )))

(defn camera-button [id]
  (let [show-controls (get (db [:ui :show-controls]) id)
        images (db id)]
    [:div
     (if show-controls
       {:style {:border-left "3px solid gray"
                :border-top "3px solid gray"
                :padding-left "5px"
                :padding-top "3px"}}
       {})
     [:div.camera-input
      [:img.image-button
       {:src (if (< 0 (count images))
               "assets/photos.png"
               "assets/camera.png")
        :on-click #(db-async! [:ui :show-controls id] (not show-controls))}]]
     (if show-controls
       [:div {:style {:padding-right 44}}
        [:label {:for id}
         [:img.image-button {:src "assets/camera.png"}]]
        [:input
         {:type "file" :accept "image/*"
          :id id :style {:display :none}
          :on-change #(handle-file id (aget (.-files (.-target %1)) 0))}]
        " \u00a0 "
        (into
         [:span.image-list]
         (for [img images]
           [:div {:style {:display :inline-block
                          :position :relative
                          :max-width "60%"
                          :margin 3
                          :vertical-align :top}}
            [:img.image-button
             {:src "./assets/delete.png"
              :style {:position :absolute
                      :top 0
                      :right 0
                      :background "rgba(255,255,255,0.7)"}
              :on-click #(log 'todo [:remove-image id img])}]
            [:img {:src img
                   :style {:max-height 150
                           :vertical-align :top
                           :max-width "100%"}}]]))]
       "")]))

;;;; Area/report chooser
(defn- sub-areas "used by traverse-areas" [id]
  (let [area (get-obj id)]
    (apply concat [id] (map sub-areas (:children area)))))
(defn traverse-areas "find all childrens of a given id" [id]
  (let [selected (db [:ui id])]
    (if selected
      (into [id] (traverse-areas selected))
      (sub-areas id))))
(defn choose-area-name [obj]
  (str (or
        (get obj "ObjectName")
        (get obj "ReportName")
        (get obj "Name")
        (get obj "Description")
        (get obj "TaskDescription")
        (:id obj))))
(defn choose-area [id]
  (let [o (get-obj id)
        children (:children o)
        selected (db [:ui id])
        child (get-obj selected)]
    (when (and (db [:ui :debug])
               (or (and children (not selected))
                   (and (not children) (:id o))))
      (log 'choosen-area (choose-area-name o) o))
    (if children
      [:div
       [select [:ui id]
        (concat [[empty-choice]]
                (for [child-id children]
                  [(choose-area-name (get-obj child-id))
                   child-id]))]
       [choose-area selected]]
      [:div])))
(defn find-area [id]
  (let [selected (db [:ui id])]
    (if selected
      (find-area selected)
      id)))
(defn all-reports []
  (map get-obj (:children (get-obj :reports))))
(defn all-templates []
  (map get-obj (:children (get-obj :templates))))

(defn current-open-reports [areas]
        (filter #((into #{} areas) (% "ObjectId"))
                (all-reports)))

(defn list-available-templates [areas]
  (let [templates (all-templates)
        obj (get-obj (find-area :areas))
        area-guid (get obj "AreaGuid")
        templates (filter #((into #{} (get % "ActiveAreaGuids")) area-guid) templates)
        open-templates (into #{} (map #(get % "TemplateGuid")
                                      (current-open-reports
                                       areas)))
        templates (remove #(open-templates (:id %)) templates)
        ]
    (if (= :object (:type obj))
      templates
      [])))
(defn create-report [obj-id template-id name]
  (go
    (db! [:ui :new-report-name] "")
    (log 'create-report obj-id template-id name)
    (let [creation-response (<! (<ajax
               (str "https://"
                    "fmproxy.solsort.com/api/v1/"
                    "Report?objectId=" obj-id
                    "&templateGuid="template-id
                    "&reportName=" name)
               :method "POST"))
          new-report-id (get creation-response "ReportGuid")]
      (if new-report-id
        (do
          (<! (<do-fetch)) ; TODO this should just be fetch, but we have to do-fetch, as created reports are missing from audit trail
          (db! [:ui :report-id] new-report-id ))
        (warn "failed making new report" obj-id template-id name creation-response))
      )
    ))
(defn finish-report [report-id]
  (go
    (log 'finish-report)
    (let [response (<! (<ajax ; TODO not absolute url
                                 (str "https://"
                                      "fmproxy.solsort.com/api/v1/"
                                      "Report?ReportGuid=" report-id
                                      )
                                 :method "PUT"))
          ]
      (log 'finish-report-response response)
    )))
(defn render-report-list [reports]
  [:div.field
   [:label "Rapport"]
   [select [:ui :report-id]
    (concat [[empty-choice]]
            (for [report reports]
              [(report "ReportName")
               (report "ReportGuid")]))]
   (if ((into #{} (map :id reports)) (db [:ui :report-id]))
     [:p {:style {:text-align :right}}
      [:button.ui.red.button
       {:on-click #(finish-report (db [:ui :report-id]))}
      "Afslut rapport"]]
     "")
   ])
(defn choose-report "react component listing reports" []
  (let [areas (doall (traverse-areas :areas))
        reports (current-open-reports areas)
        available-templates (list-available-templates areas)]
    [:div
     (case (count reports)
      0 (do
          (db-async! [:ui :report-id] nil)
          ""
          ;(render-report-list reports)
          #_[:span.empty])
      1 (do
          (db-async! [:ui :report-id] ((first reports) "ReportGuid"))
          (render-report-list reports)
          #_[:div "Rapport: " ((first reports) "ReportName")])
      (render-report-list reports))
     (if (empty? available-templates)
       ""
       [:div.field
        [:label "Opret rapport"]
        [:p [input [:ui :new-report-name]]]
        (into [:div {:style {:text-align :right}}]
              (map
               (fn [template]
                 [:button.ui.button
                  {:on-click #(create-report (find-area :areas) (:id template) (db [:ui :new-report-name]))}
                  (get template "Name")
                  ])
               available-templates
               )
              )
        #_(str (map key (list-available-templates)))
        ]
       )
     ]))

;;;; Actual report
(def do-rot90 (not= -1 (.indexOf js/location.hash "rot90")))
(defn data-id [k]
  [:obj (or (db [:entries k]) :missing-data-object)])
  (def field-name "mapping from field-type to value name in api"
    {:approve-reject "String"
     :time "TimeSpan"
     :text-input-noframe "String"
     :text-input "String"
     :decimal-2-digit "Double"
     :checkbox "Boolean"
     :remark "String"
     })
(defn single-field [obj cols id area pos]
  (let [field-type (FieldType obj)
        value (FieldValue obj)
        id (conj id (str (field-name field-type) "Value" pos))]
    (case field-type
      :fetch-from (str (ObjectName area))
      :approve-reject [select id {"" ""
                               "Godkendt" "Approved"
                               "Afvist" "Rejected"
                               "-" "None"}]
                                        ; TODO: not checkbox - string value "Approved" "Rejected" "None" ""
      :text-fixed (if do-rot90 [rot90 [:span value]] [:span value])
      :time [input id :type :time]
      :remark [input id]
      :text-input-noframe [input id]
      :text-input [input id]
      :decimal-2-digit [input id :type "number"]
      :decimal-4-digit [input id :type "number"]
      :integer [input id :type "number"]
      :checkbox [checkbox id]
      :text-fixed-noframe [:span value]
      [:strong "unhandled field:" (str field-type) " " value])))
(defn field [obj-id cols id area]
  (let [obj (get-obj obj-id)
        columns (Columns obj)
        double-field (DoubleField obj)
        double-separator (DoubleFieldSeperator obj)
        id (data-id id)]
    ;(log id (data-id id))
    [:span.fmfield {:key id
                    :style {:width (- (* 12 @unit (/ columns cols)) (/ 50 cols))}
                    :on-click (fn [] (log {:obj obj :id id :id-obj (db id)}) nil)}
     (if double-field
       (let [obj (dissoc obj "DoubleField")]
         [:span (single-field obj cols id area 1)
          " " double-separator " "
          (single-field obj cols id area 2)])
       (single-field obj cols id area 1))]))

(defn template-control [id line-id position]
  (let [ctl (get-obj id)
        title (get ctl "Title")
        series (filter #(not= "" (get ctl (str "ChartSerieName" %))) (range 1 6))]
    [:div
     [:h3 position " " title]
     (into
      [:div]
      (for [serie (concat [0] series)]
        [:div.multifield
         (get ctl (str "ChartSerieName" serie))
         (into [:div]
               (for [i (range
                        (ctl "XAxisMin")
                        (+ (ctl "XAxisMax") (ctl "XAxisStep"))
                        (ctl "XAxisStep"))]
                 [:span {:style {:display :inline-block
                                 :text-align :center
                                 :width 60}}
                  (if (= serie 0)
                    (str i)
                    [input (concat line-id [:control serie i])
                     :type "number"])]))]))]))
(defn render-line [line-id report-id obj]

  (let [line (get-obj line-id)
        line-type (LineType line)
        cols (get line "ColumnsTotal")
        desc (str (get line "Position" "") " " (TaskDescription line))
        area (AreaGuid line)
        obj-id (ObjectId obj)
        id (data-id [report-id obj-id (PartGuid line)])
        debug-str (dissoc line :fields)
        debug-str (str [report-id obj-id (PartGuid line) id])
        data-id (db [:entries id])
        fields (into
                [:div.fields]
                (map #(field % cols [report-id obj-id %] obj)
                     (:children line)))]
    [:div.line
     {:style
      {:padding-top 10}
      :key [obj-id line-id]
      :on-click #(log debug-str)}
     (case line-type
       :template-control [template-control (get line "ControlGuid") id (get line "Position" "")]
       :basic [:h3 "" desc]
       :simple-headline [:h3 desc]
       :vertical-headline [:div [:h3 desc] fields]
       :horizontal-headline [:div [:h3 desc] fields]
       :multi-field-line [:div.multifield desc [camera-button (conj id :images)]
                          fields]
       :description-line [:div desc [input  (conj id "Remarks") {:type :text}]]
       [:span {:key id} "unhandled line " (str line-type) " " debug-str])]))
(defn render-section [lines report-id areas]
  (doall (for [obj areas]
           (doall (for [line lines]
                    (when (= (AreaGuid line) (AreaGuid obj))
                      (render-line (get line "PartGuid") report-id obj)))))))
(defn render-lines
  [lines report-id areas]
  (apply concat
         (for [section (partition-by ColumnHeader lines)]
           (render-section section report-id areas))))
(defn render-template [report]
  (let [id (TemplateGuid report)
        template (get-obj id)
        areas (conj (doall (map get-obj (traverse-areas (ObjectId report)))) {})
        max-objects 100]
    (log report)
    (into
     [:div.ui.form
      [:h1 (Description template)]
      (if (< max-objects (count areas))
        [:div
         [:div {:style {:display :inline-block :float :right}} [checkbox [:ui :nolimit]]]
         [:br]
         "Vis rapportindhold for områder med mere end " (str max-objects) " objekter (langsomt):"
         [:br]
         "- eller vælg underområde herover."]
        "")]
     (if (and (< max-objects (count areas)) (not (db [:ui :nolimit])))
       []
       (render-lines (map get-obj (:children template)) (:id report) areas)))))

;;;; Settings
(defn settings []
  [:div
   [:h1 "Indstillinger"]
   [:p [checkbox [:ui :debug]] "debug enabled"]
   [:span.blue.ui.button {:on-click #(<do-fetch)} "reload"]
   [:span.red.ui.button
    {:on-click
     #(go
        (<! (<p (js/localforage.clear)))
        (db! [] {})
        (js/location.reload)
        (<! (<do-fetch)))}
    "reset + reload"]])
