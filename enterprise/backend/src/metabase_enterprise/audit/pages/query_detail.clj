(ns metabase-enterprise.audit.pages.query-detail
  "Queries to show details about a (presumably ad-hoc) query."
  (:require [cheshire.core :as json]
            [honeysql.core :as hsql]
            [metabase-enterprise.audit.interface :as audit.i]
            [metabase-enterprise.audit.pages.common :as common]
            [metabase.util.honeysql-extensions :as hx]
            [metabase.util.schema :as su]
            [ring.util.codec :as codec]
            [schema.core :as s]))

(defmethod audit.i/internal-query ::bad-card
  [_ card-id]
  {:metadata [[:card_name       {:display_name "Question",           :base_type :type/Text    :remapped_from :card_id}]
              [:error_str       {:display_name "Error",              :base_type :type/Text    :code          true}]
              [:collection_id   {:display_name "Collection ID",      :base_type :type/Integer :remapped_to   :collection_name}]
              [:collection_name {:display_name "Collection",         :base_type :type/Text    :remapped_from :collection_id}]
              [:database_id     {:display_name "Database ID",        :base_type :type/Integer :remapped_to   :database_name}]
              [:database_name   {:display_name "Database",           :base_type :type/Text    :remapped_from :database_id}]
              [:table_id        {:display_name "Table ID",           :base_type :type/Integer :remapped_to   :table_name}]
              [:table_name      {:display_name "Table",              :base_type :type/Text    :remapped_from :table_id}]
              [:last_run_at     {:display_name "Last run at",        :base_type :type/DateTime}]
              [:total_runs      {:display_name "Total runs",         :base_type :type/Integer}]
              [:num_dashboards  {:display_name "Dashboards it's in", :base_type :type/Integer}]
              [:user_id         {:display_name "Created By ID",      :base_type :type/Integer :remapped_to   :user_name}]
              [:user_name       {:display_name "Created By",         :base_type :type/Text    :remapped_from :user_id}]
              [:updated_at      {:display_name "Updated At",         :base_type :type/DateTime}]]
   :results (common/reducible-query
              (->
                {:select    [[:card.name :card_name]
                             [:qe.error :error_str]
                             :collection_id
                             [:coll.name :collection_name]
                             :card.database_id
                             [:db.name :database_name]
                             :card.table_id
                             [:t.name :table_name]
                             [(hsql/call :max :qe.started_at) :last_run_at]
                             [:%count.qe.id :total_runs]
                             [:%distinct-count.dash_card.card_id :num_dashboards]
                             [:card.creator_id :user_id]
                             [(common/user-full-name :u) :user_name]
                             [(hsql/call :max :card.updated_at) :updated_at]]
                 :from      [[:report_card :card]]
                 :left-join [[:collection :coll]                [:= :card.collection_id :coll.id]
                             [:metabase_database :db]           [:= :card.database_id :db.id]
                             [:metabase_table :t]               [:= :card.table_id :t.id]
                             [:core_user :u]                    [:= :card.creator_id :u.id]
                             [:report_dashboardcard :dash_card] [:= :card.id :dash_card.card_id]
                             [:query_execution :qe]             [:= :card.id :qe.card_id]]
                 :where     [:and
                             [:= :card.id card-id]
                             [:<> :qe.error nil]]}))})

;; Details about a specific query (currently just average execution time).
(s/defmethod audit.i/internal-query ::details
  [_ query-hash :- su/NonBlankString]
  {:metadata [[:query                  {:display_name "Query",                :base_type :type/Dictionary}]
              [:average_execution_time {:display_name "Avg. Exec. Time (ms)", :base_type :type/Number}]]
   :results  (common/reducible-query
              {:select [:query
                        :average_execution_time]
               :from   [:query]
               :where  [:= :query_hash (codec/base64-decode query-hash)]
               :limit  1})
   :xform (map #(update (vec %) 0 json/parse-string))})