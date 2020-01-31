(ns bluegenes.pages.querybuilder.views
  (:require [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as reagent :refer [create-class]]
            [imcljs.path :as p]
            [clojure.string :refer [split join]]
            [oops.core :refer [ocall oget]]
            [clojure.string :as str :refer [starts-with? ends-with?]]
            [bluegenes.utils :refer [uncamel]]
            [bluegenes.components.ui.constraint :refer [constraint]]
            [bluegenes.components.bootstrap :refer [popover tooltip]]
            [imcljs.path :as im-path]
            [cljs.reader :refer [read]]
            [imcljs.query :refer [->xml]]
            [cljs.reader :refer [read-string]]
            [dommy.core :refer-macros [sel1]]
            [bluegenes.components.loader :refer [mini-loader loader]]
            [bluegenes.components.ui.results_preview :refer [preview-table]]))

(defn one-of? [haystack needle] (some? (some #{needle} haystack)))

(def auto (reagent/atom true))

(def aquery {:from "Gene"
             :constraintLogic "A or B"
             :select ["symbol"
                      "organism.name"
                      "alleles.symbol"
                      "alleles.phenotypeAnnotations.annotationType"
                      "alleles.phenotypeAnnotations.description"]
             :where [{:path "Gene.symbol"
                      :op "="
                      :code "A"
                      :value "zen"}
                     {:path "Gene.symbol"
                      :op "="
                      :code "B"
                      :value "mad"}]})

(defn root-class-dropdown []
  (let [current-mine (subscribe [:current-mine])
        root-class (subscribe [:qb/root-class])]
    (fn []
      (into [:select.form-control
             {:on-change (fn [e] (dispatch [:qb/set-root-class (oget e :target :value)]))
              :value @root-class}]
            (map (fn [[class-kw details]]
                   [:option {:value class-kw} (:displayName details)])
                 (sort-by (comp :displayName second) (get-in @current-mine [:service :model :classes])))))))

(defn dotsplit [string] (split string "."))

(defn im-query->map [{:keys [select]}]
  (reduce (fn [total next] (assoc-in total next {})) {} (map dotsplit select)))

(defn attributes-first [[_ v]] (empty? v))

(defn has-children? [[_ v]] (some? (not-empty v)))

(defn within? [haystack needle] (some? (some #{needle} haystack)))

(defn not-selected [selected [k attributes-map]]
  (not (within? selected (name k))))

(def q {:from "Gene"
        :select ["Gene.symbol"
                 "Gene.secondaryIdentifier"
                 "Gene.organism.name"]})

(defn attribute []
  (let [enhance-query (subscribe [:qb/enhance-query])]
    (fn [model [k properties] & [trail sub]]
      (let [path (conj trail (name k))
            selected? (get-in @enhance-query path)]
        [:li.haschildren
         [:span
          {:on-click (fn []
                       (if selected?
                         (dispatch [:qb/enhance-query-remove-view path sub])
                         (dispatch [:qb/enhance-query-add-view path sub])))}
          (if (get-in @enhance-query path)
            [:svg.icon.icon-checkbox-checked [:use {:xlinkHref "#icon-checkbox-checked"}]]
            [:svg.icon.icon-checkbox-unchecked [:use {:xlinkHref "#icon-checkbox-unchecked"}]])
          [:span.qb-label (:displayName properties)]]]))))

(defn node []
  (let [menu (subscribe [:qb/menu])]
    (fn [model [k properties] & [trail]]
      (let [path (vec (conj trail (name k)))
            open? (get-in @menu path)
            str-path (join "." path)
            sub (get-in @menu (conj path :subclass))]
        [:li.haschildren.qb-group
         {:class (cond open? "expanded-group")}
         [:div.group-title
          {:on-click (fn []
                       (if open?
                         (dispatch [:qb/collapse-path path])
                         (dispatch [:qb/expand-path path])))}
          [:svg.icon.icon-plus
           {:class (when open? "arrow-down")}
           [:use {:xlinkHref "#icon-plus"}]]
          [:span.qb-class (:displayName properties)]
          [:span.label-button
           {:on-click (fn [e]
                        (ocall e :stopPropagation)
                        (dispatch [:qb/enhance-query-add-summary-views path sub]))}
           "Summary"]]
         (when open?
           (into [:ul]
                 (concat
                  (when (im-path/class? model str-path)
                    (when-let [subclasses (im-path/subclasses model str-path)]
                      (list
                       [:li [:span
                             (into
                              [:select.form-control
                               {:on-change (fn [e] (dispatch [:qb/enhance-query-choose-subclass path (oget e :target :value)]))}]
                              (map (fn [subclass]
                                     [:option {:value subclass} (uncamel (name subclass))]) (conj subclasses (:referencedType properties))))]])))
                  (if sub
                    (map (fn [i] [attribute model i path sub]) (sort (remove (comp (partial = :id) first) (im-path/attributes model sub))))
                    (map (fn [i] [attribute model i path sub]) (sort (remove (comp (partial = :id) first) (im-path/attributes model (:referencedType properties))))))
                  (if sub
                    (map (fn [i] [node model i path false]) (sort (im-path/relationships model sub)))
                    (map (fn [i] [node model i path false]) (sort (im-path/relationships model (:referencedType properties))))))))]))))

(defn model-browser []
  (fn [model root-class]
    [:div.model-browser
     (let [path [root-class]]
       (into [:ul
              [:li [:div.model-button-group
                    [:button.btn.btn-slim
                     {:on-click #(dispatch [:qb/enhance-query-add-summary-views [root-class]])}
                     "Summarise"]
                    [:button.btn.btn-slim
                     {:on-click #(dispatch [:qb/expand-all])}
                     "Expand Selected"]
                    [:button.btn.btn-slim
                     {:on-click #(dispatch [:qb/collapse-all])}
                     "Collapse All"]]]]
             (concat
              (map (fn [i] [attribute model i path]) (sort (remove (comp (partial = :id) first) (im-path/attributes model root-class))))
              (map (fn [i] [node model i path]) (sort (im-path/relationships model root-class))))))]))

(defn data-browser-node [close! model hier tag]
  (let [open? (reagent/atom true)]
    (fn []
      (let [children (filter #(contains? (parents hier %) tag)
                             (descendants hier tag))]
        [:li.haschildren.qb-group
         {:class (when @open? "expanded-group")}
         [:div.group-title
          (when (seq children)
            [:a {:on-click #(swap! open? not)}
             [:svg.icon.icon-plus
              {:class (when @open? "arrow-down")}
              [:use {:xlinkHref "#icon-plus"}]]])
          [:a.qb-class
           {:class (when (empty? children) "no-icon")
            :on-click #(do (dispatch [:qb/set-root-class tag]) (close!))}
           tag]
          (when-let [count (get-in model [tag :count])]
            (when (pos? count)
              [:span.class-count count]))]
         (when (and (seq children) @open?)
           (into [:ul]
                 (for [child (sort children)]
                   [data-browser-node close! model hier child])))]))))

(defn data-browser [close!]
  (let [model @(subscribe [:model])
        hier (reduce (fn [h [child {:keys [extends]}]]
                       (reduce #(derive %1 child %2) h (map keyword extends)))
                     (make-hierarchy) model)]
    (fn []
      [:div.model-browser-column
       [:div.header-group
        [:h4 "Data Browser"]
        [:button.btn.btn-raised.btn-sm.browse-button
         {:on-click close!}
         [:svg.icon.icon-arrow-left [:use {:xlinkHref "#icon-arrow-left"}]]
         "Back to model"]]
       [:div.model-browser.class-browser
        (into [:ul]
              (for [root (->> (keys model)
                              (filter #(empty? (parents hier %)))
                              (sort))]
                [data-browser-node close! model hier root]))]])))

(defn browser-pane []
  (let [query (subscribe [:qb/query])
        current-mine (subscribe [:current-mine])
        root-class (subscribe [:qb/root-class])
        browse-model? (reagent/atom true)]
    (when (empty? @query)
      (dispatch [:qb/set-root-class "Gene"]))
    (fn []
      (if @browse-model?
        [:div.model-browser-column
         [:h4 "Model Browser"]
         (when @root-class
           [:div.input-group
            [root-class-dropdown]
            [:span.input-group-btn
             [:button.btn.btn-raised.btn-sm.browse-button
              {:on-click #(swap! browse-model? not)}
              [:svg.icon.icon-tree [:use {:xlinkHref "#icon-tree"}]]
              "Browse"]]])
         (when @root-class
           [model-browser (:model (:service @current-mine)) (name @root-class)])]
        [data-browser #(swap! browse-model? not)]))))

(defn dissoc-keywords [m]
  (apply dissoc m (filter keyword? (keys m))))

(defn queryview-node []
  (let [lists (subscribe [:current-lists])]
    (fn [model [k properties] & [trail]]
      (let [path (vec (conj trail (name k)))]
        [:li.tree.haschildren
         [:div.flexmex
          [:span.lab {:class (if (im-path/class? model (join "." path)) "qb-class" "qb-attribute")}
           [:span.qb-label {:style {:margin-left 5}} [tooltip {:on-click #(dispatch [:qb/expand-path path]) :title (str "Show " (uncamel k) " in the model browser")} [:a  (uncamel k)]]]
           (when-let [s (:subclass properties)] [:span.label.label-default (uncamel s)])
           [:svg.icon.icon-bin
            {:on-click (if (> (count path) 1)
                         (fn [] (dispatch [:qb/enhance-query-remove-view path]))
                         (fn [] (dispatch [:qb/enhance-query-clear-query path])))}
            [:use {:xlinkHref "#icon-bin"}]]

           [:svg.icon.icon-filter {:on-click (fn [] (dispatch [:qb/enhance-query-add-constraint path]))} [:use {:xlinkHref "#icon-filter"}]]
           (when-let [c (:id-count properties)]
             [:span.label.label-soft
              {:class (when (= 0 c) "label-no-results")
               :style {:margin-left 5}} (str c " row" (when (not= c 1) "s"))])]]
         (when-let [constraints (:constraints properties)]
           (into [:ul.tree.banana]
                 (map-indexed (fn [idx con]
                                [:li
                                 [:div.contract
                                  {:class (when (empty? (:value con)) "empty")}
                                  [constraint
                                   :model model
                                   :path (join "." path)
                                   :lists @lists
                                   :code (:code con)
                                   :on-remove (fn [] (dispatch [:qb/enhance-query-remove-constraint path idx]))
                                   :possible-values (when (some? (:possible-values properties)) (map :item (:possible-values properties)))
                                   :typeahead? true
                                   :value (or (:value con) (:values con))
                                   :op (:op con)
                                   :on-select-list (fn [c]
                                                     (dispatch [:qb/enhance-query-update-constraint path idx c])
                                                     (dispatch [:qb/enhance-query-build-im-query true]))
                                   :on-change-operator (fn [x]
                                                         (dispatch [:qb/enhance-query-update-constraint path idx x])
                                                         (dispatch [:qb/enhance-query-build-im-query true]))

                                   :on-change (fn [c]
                                                (dispatch [:qb/enhance-query-update-constraint path idx c]))
                                                ;(dispatch [:qb/enhance-query-build-im-query true])

                                   :on-blur (fn [c]
                                              (dispatch [:qb/enhance-query-update-constraint path idx c])
                                              (dispatch [:qb/enhance-query-build-im-query true]))

                                   ;(dispatch [:qb/build-im-query])

                                   :label? false]]]) constraints)))
         (when (not-empty (dissoc-keywords properties))
           (let [classes (filter (fn [[k p]] (im-path/class? model (join "." (conj path k)))) (dissoc-keywords properties))
                 attributes (filter (fn [[k p]] ((complement im-path/class?) model (join "." (conj path k)))) (dissoc-keywords properties))]
             (into [:ul.tree.banana2]
                   (concat
                    (map (fn [n] [queryview-node model n path]) (sort attributes))
                    (map (fn [n] [queryview-node model n path]) (sort classes))))))]))))

(defn example-button []
  [:button.btn.btn-primary.btn-raised
   {:on-click (fn [] (dispatch [:qb/load-example aquery]))}
   "Example"])

(defn queryview-browser []
  (let [enhance-query (subscribe [:qb/enhance-query])
        default-query? (subscribe [:qb/example])]
    (fn [model]
      (if (not-empty @enhance-query)
        [:div.query-browser
         (into [:ul.tree]
               (map (fn [n]
                      [queryview-node model n]) @enhance-query))]
        [:div
         [:p "Please select at least one attribute from the Model Browser on the left."]
         (cond (seq @default-query?) [example-button])]))))

(def <sub (comp deref subscribe))

(defn logic-box []
  (let [editing? (reagent/atom false)
        logic (subscribe [:qb/constraint-logic])]
    (fn []
      [:div.logic-box
       {:ref (fn [x]
               (some-> x (ocall :getElementsByTagName "input") array-seq first (ocall :focus)))}
       (if @editing?
         [:input.form-control.input-sm
          {:type "text"
           :value @logic
           :on-key-down (fn [e] (when (= (oget e :keyCode) 13)
                                  (reset! editing? false)
                                  (dispatch [:qb/format-constraint-logic @logic])))
           :on-blur (fn []
                      (reset! editing? false)
                      (dispatch [:qb/format-constraint-logic @logic]))
           :on-change (fn [e] (dispatch [:qb/update-constraint-logic (oget e :target :value)]))}]
         [:pre
          [:svg.icon.icon-edit {:on-click (fn [] (reset! editing? true))} [:use {:xlinkHref "#icon-edit"}]]
          [:span @logic]])])))

(defn controls []
  (let [results-preview (subscribe [:qb/preview])]
    (fn []
      [:div.button-group
       [:button.btn.btn-primary.btn-raised
        {:on-click (fn [] (dispatch [:qb/export-query]))}
        (str "Show All " (when-let [c (:iTotalRecords @results-preview)] (str c " ")) "Rows")]
       [:button.btn.btn-raised
        {:on-click (fn [] (println "TODO implement me"))}
        "Save Query"]
       [:button.btn.btn-raised
        {:on-click (fn [] (dispatch [:qb/enhance-query-clear-query]))}
        "Clear Query"]])))

(defn preview [result-count]
  (let [results-preview (subscribe [:qb/preview])
        fetching-preview? (subscribe [:qb/fetching-preview?])]
    [:div
     (if-let [res @results-preview]
       [preview-table
        :loading? false ; The loader is ugly.
        :hide-count? true
        :query-results res]
       [:p "No query available for generating preview."])]))

(defn drop-nth [n coll] (vec (keep-indexed #(if (not= %1 n) %2) coll)))

(defn sortable-list []
  (let [order (subscribe [:qb/order])
        state (reagent/atom {:items ["A" "B" "C" "D"] :selected nil})]
    (fn []
      (into [:div.sort-order-container
             {:class (when (some? (:selected @state)) "dragtest")}]
            (map-indexed
             (fn [idx i]
               [:div {:class (when (= idx (:selected @state)) "dragging")
                      :draggable true
                      :on-drag-start (fn [e]
                                        ;(.preventDefault e)
                                       (ocall e :stopPropagation)
                                       (ocall e "dataTransfer.setData" "banana" "cakes")
                                       (swap! state assoc :selected idx))
                      :on-drag-enter (fn [e]
                                       (ocall e :preventDefault)
                                       (ocall e :stopPropagation)
                                       (let [selected-idx (:selected @state)
                                             items @order
                                             selected-item (get items selected-idx)
                                             [before after] (split-at idx (drop-nth selected-idx items))]
                                         #_(swap! state assoc
                                                  :selected idx
                                                  :items (vec (concat (vec before) (vec (list selected-item)) (vec after))))
                                         (swap! state assoc :selected idx)
                                         (dispatch [:qb/set-order (vec (concat (vec before) (vec (list selected-item)) (vec after)))])))
                      :on-drag-end (fn [] (swap! state assoc :selected nil))}
                (into [:div] (map (fn [part] [:span.part part]) (interpose ">" (map uncamel (split i ".")))))])) @order))))

(defn xml-view []
  (let [query (subscribe [:qb/im-query])
        current-mine (subscribe [:current-mine])]
    (fn []

      [:pre (str (->xml (:model (:service @current-mine)) @query))])))

(defn query-viewer []
  (let [enhance-query (subscribe [:qb/enhance-query])
        current-mine (subscribe [:current-mine])
        query (subscribe [:qb/im-query])
        tab-index (reagent/atom 0)
        constraint-value-count (subscribe [:qb/constraint-value-count])]
    (fn []
      [:div.panel.panel-default
       [:div.panel-body
        [:ul.nav.nav-tabs
         [:li {:class (when (= @tab-index 0) "active")} [:a {:on-click #(reset! tab-index 0)} "Query"]]
         [:li {:class (when (= @tab-index 1) "active")} [:a {:on-click #(reset! tab-index 1)} "Column Order"]]]
        (case @tab-index
          0 [:div
             [queryview-browser (:model (:service @current-mine))]
             (when (>= @constraint-value-count 2)
               [:div
                [:h4 "Constraint Logic"]
                [logic-box]])]
          1 [sortable-list])
        (when (not-empty @enhance-query) [controls])]])))

(defn column-order-preview []
  (let [enhance-query (subscribe [:qb/enhance-query])
        current-mine (subscribe [:current-mine])
        tab-index (reagent/atom 0)
        prev (subscribe [:qb/preview])]
    (fn []
      [:div.panel.panel-default
       [:div.panel-body
        [:ul.nav.nav-tabs
         [:li {:class (when (= @tab-index 0) "active")} [:a {:on-click #(reset! tab-index 0)} "Preview"]]
         [:li {:class (when (= @tab-index 1) "active")} [:a {:on-click #(reset! tab-index 1)} "XML"]]]
        (case @tab-index
          0 [preview @prev]
          1 [xml-view])]])))

(defn recent-queries []
  (let [queries (take 5 @(subscribe [:results/historical-custom-queries]))
        active-query @(subscribe [:qb/im-query])]
    (if (empty? queries)
      [:p "Queries that you have run during this session will appear here."]
      [:table.table.query-table
       [:thead
        [:tr
         [:th "Start"]
         [:th "Results Format"]
         [:th "Last Run"]]]
       (into [:tbody]
             (for [[_ {:keys [value last-executed]}] queries
                   :let [{:keys [from select]} value
                         active? (= active-query (dissoc value :title))]]
               [:tr {:role "button"
                     :title (if active? "This query is active" "Load this query")
                     :class (when active? "active-query")
                     :on-click #(dispatch [:qb/load-query value])}
                [:td [:code.start {:class (str "start-" from)} from]]
                [:td (into [:<>] (for [c select] [:code c]))]
                [:td [:span.date (.toLocaleTimeString (js/Date. last-executed))]]]))])))

(defn saved-queries [])

(defn import-from-xml [])

(defn create-template [])

(defn other-query-options []
  (let [tab-index (reagent/atom 0)]
    (fn []
      [:div.panel.panel-default
       [:div.panel-body
        [:ul.nav.nav-tabs
         [:li {:class (when (= @tab-index 0) "active")}
          [:a {:on-click #(reset! tab-index 0)} "Recent Queries"]]
         [:li {:class (when (= @tab-index 1) "active")}
          [:a {:on-click #(reset! tab-index 1)} "Saved Queries"]]
         [:li {:class (when (= @tab-index 2) "active")}
          [:a {:on-click #(reset! tab-index 2)} "Import from XML"]]
         [:li {:class (when (= @tab-index 3) "active")}
          [:a {:on-click #(reset! tab-index 3)} "Create Template"]]]
        (case @tab-index
          0 [recent-queries]
          1 [saved-queries]
          2 [import-from-xml]
          3 [create-template])]])))

(defn main []
  [:div.column-container
   [browser-pane]
   [:div.query-view-column
    [:div.row
     [:div.col-xs-12.col-lg-5
      [query-viewer]]
     [:div.col-xs-12.col-lg-7
      [column-order-preview]]]
    [:div.row
     [:div.col-xs-12
      [other-query-options]]]]])
