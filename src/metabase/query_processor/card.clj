(ns metabase.query-processor.card
  "Code for running a query in the context of a specific Card."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase.api.common :as api]
            [metabase.mbql.schema :as mbql.s]
            [metabase.models.card :as card :refer [Card]]
            [metabase.models.dashboard :refer [Dashboard]]
            [metabase.models.database :refer [Database]]
            [metabase.models.query :as query]
            [metabase.public-settings :as public-settings]
            [metabase.query-processor :as qp]
            [metabase.query-processor.error-type :as qp.error-type]
            [metabase.query-processor.middleware.constraints :as constraints]
            [metabase.query-processor.middleware.permissions :as qp.perms]
            [metabase.query-processor.streaming :as qp.streaming]
            [metabase.query-processor.util :as qputil]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs]]
            [toucan.db :as db]))

(defn- query-magic-ttl
  "Compute a 'magic' cache TTL time (in seconds) for QUERY by multipling its historic average execution times by the
  `query-caching-ttl-ratio`. If the TTL is less than a second, this returns `nil` (i.e., the cache should not be
  utilized.)"
  [query]
  (when-let [average-duration (query/average-execution-time-ms (qputil/query-hash query))]
    (let [ttl-seconds (Math/round (float (/ (* average-duration (public-settings/query-caching-ttl-ratio))
                                            1000.0)))]
      (when-not (zero? ttl-seconds)
        (log/info (trs "Question''s average execution duration is {0}; using ''magic'' TTL of {1}"
                       (u/format-milliseconds average-duration) (u/format-seconds ttl-seconds))
                  (u/emoji "💾"))
        ttl-seconds))))

(defn- ttl-hierarchy
  "Returns the cache ttl (in seconds), by first checking whether there is a stored value for the database,
  dashboard, or card (in that order of increasing preference), and if all of those don't exist, then the
  `query-magic-ttl`, which is based on average execution time."
  [card dashboard database query]
  (when (public-settings/enable-query-caching)
    (let [ttls (map :cache_ttl [card dashboard database])
          most-granular-ttl (first (filter some? ttls))]
      (or (when most-granular-ttl ; stored TTLs are in hours; convert to seconds
            (* most-granular-ttl 3600))
          (query-magic-ttl query)))))

(defn query-for-card
  "Generate a query for a saved Card"
  [{query :dataset_query
    :as   card} parameters constraints middleware & [ids]]
  (let [query     (-> query
                      ;; don't want default constraints overridding anything that's already there
                      (m/dissoc-in [:middleware :add-default-userland-constraints?])
                      (assoc :constraints constraints
                             :parameters  parameters
                             :middleware  middleware))
        dashboard (db/select-one [Dashboard :cache_ttl] :id (:dashboard-id ids))
        database  (db/select-one [Database :cache_ttl] :id (:database_id card))
        ttl-secs  (ttl-hierarchy card dashboard database query)]
    (assoc query :cache-ttl ttl-secs)))

(def ^:dynamic *allow-arbitrary-mbql-parameters*
  "In 0.41.0+ you can no longer add arbitrary `:parameters` to a query for a saved question -- only parameters for
  template tags that are part of a /native/ query may be supplied (only native queries can have template tags); the
  type of the parameter has to agree with the type of the template tag as well. This variable controls whether or not
  this constraint is enforced.

  Normally, when running a query in the context of a /Card/, this is `false`, and the constraint is enforced. By
  binding this to a truthy value you can disable the checks. Currently this is only done
  by [[metabase.query-processor.dashboard]], which does its own parameter validation before handing off to the code
  here."
  false)

(defn- card-template-tag-parameters
  "Template tag parameters that have been specified for the query for Card with `card-id`, if any, returned as a map in
  the format

    {\"template_tag_parameter_name\" :parameter-type, ...}

  Template tag parameter name is the name of the parameter as it appears in the query, e.g. `{{id}}` has the `:name`
  `\"id\"`.

  Parameter type in this case is something like `:string` or `:number` or `:date/month-year`; parameters passed in as
  parameters to the API request must be allowed for this type (i.e. `:string/=` is allowed for a `:string` parameter,
  but `:number/=` is not)."
  [card-id]
  (let [query (api/check-404 (db/select-one-field :dataset_query Card :id card-id))]
    (into
     {}
     (comp
      (map (fn [[param-name {widget-type :widget-type, tag-type :type}] ]
             ;; Field Filter parameters have a `:type` of `:dimension` and the widget type that should be used is
             ;; specified by `:widget-type`. Non-Field-filter parameters just have `:type`. So prefer
             ;; `:widget-type` if available but fall back to `:type` if not.
             (cond
               (= tag-type :dimension)
               [param-name widget-type]

               (contains? mbql.s/raw-value-template-tag-types tag-type)
               [param-name tag-type])))
      (filter some?))
     (get-in query [:native :template-tags]))))

(defn- allowed-parameter-type-for-template-tag-widget-type? [parameter-type widget-type]
  (when-let [allowed-template-tag-types (get-in mbql.s/parameter-types [parameter-type :allowed-for])]
    (contains? allowed-template-tag-types widget-type)))

(defn- allowed-parameter-types-for-template-tag-widget-type [widget-type]
  (into #{} (for [[parameter-type {:keys [allowed-for]}] mbql.s/parameter-types
                  :when                                  (contains? allowed-for widget-type)]
              parameter-type)))

(defn- validate-card-parameters
  "Unless [[*allow-arbitrary-mbql-parameters*]] is truthy, check to make all supplied `parameters` actually match up
  with template tags in the query for Card with `card-id`."
  [card-id parameters]
  (when-not *allow-arbitrary-mbql-parameters*
    (let [template-tags (card-template-tag-parameters card-id)]
      (doseq [request-parameter parameters]
        (let [matching-template-tag-type (or (get template-tags (:name request-parameter))
                                             (throw (ex-info (tru "Invalid parameter: Card does not have a template tag named {0}."
                                                                  (pr-str (:name request-parameter)))
                                                             {:type               qp.error-type/invalid-parameter
                                                              :invalid-parameter  request-parameter
                                                              :allowed-parameters (keys template-tags)})))]
          ;; now make sure the type agrees as well
          (when-not (allowed-parameter-type-for-template-tag-widget-type? (:type request-parameter) matching-template-tag-type)
            (throw (ex-info (tru "Invalid parameter type {0} for template tag {1}. Parameter type must be one of: {2}"
                                 (:type request-parameter)
                                 (pr-str (:name request-parameter))
                                 (str/join ", " (sort (allowed-parameter-types-for-template-tag-widget-type
                                                       matching-template-tag-type))))
                            {:type              qp.error-type/invalid-parameter
                             :invalid-parameter request-parameter
                             :template-tag-type matching-template-tag-type
                             :allowed-types     (allowed-parameter-types-for-template-tag-widget-type :id)}))))))))

(defn run-query-for-card-async
  "Run the query for Card with `parameters` and `constraints`, and return results in a
  `metabase.async.streaming_response.StreamingResponse` (see [[metabase.async.streaming-response]]) that should be
  returned as the result of an API endpoint fn. Will throw an Exception if preconditions (such as read perms) are not
  met before returning the `StreamingResponse`.

  `context` is a keyword describing the situation in which this query is being ran, e.g. `:question` (from a Saved
  Question) or `:dashboard` (from a Saved Question in a Dashboard). See [[metabase.mbql.schema/Context]] for all valid
  options."
  [card-id export-format
   & {:keys [parameters constraints context dashboard-id middleware qp-runner run ignore_cache]
      :or   {constraints constraints/default-query-constraints
             context     :question
             qp-runner   qp/process-query-and-save-execution!}}]
  {:pre [(u/maybe? sequential? parameters)]}
  (let [run   (or run
                  ;; param `run` can be used to control how the query is ran, e.g. if you need to
                  ;; customize the `context` passed to the QP
                  (^:once fn* [query info]
                   (qp.streaming/streaming-response [context export-format (u/slugify (:card-name info))]
                     (binding [qp.perms/*card-id* card-id]
                       (qp-runner query info context)))))
        card  (api/read-check (db/select-one [Card :id :name :dataset_query :database_id :cache_ttl :collection_id] :id card-id))
        query (-> (assoc (query-for-card card parameters constraints middleware {:dashboard-id dashboard-id}) :async? true)
                  (update :middleware (fn [middleware]
                                        (merge
                                         {:js-int-to-string? true :ignore-cached-results? ignore_cache}
                                         middleware))))
        info  {:executed-by  api/*current-user-id*
               :context      context
               :card-id      card-id
               :card-name    (:name card)
               :dashboard-id dashboard-id}]
    (api/check-not-archived card)
    (run query info)))
