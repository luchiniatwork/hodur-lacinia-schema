(ns hodur-lacinia-schema.core
  (:require [camel-snake-kebab.core :refer [->kebab-case-string]]
            [datascript.core :as d]
            [datascript.query-v3 :as q]))

(def ^:private primitive-type-map
  {"String"   'String
   "Integer"  'Int
   "Float"    'Float
   "Boolean"  'Boolean
   "ID"       'ID
   "DateTime" 'String})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private get-type-reference
  [{:keys [type/name type/nature]}]
  (if (= :user nature)
    (keyword name)
    (get primitive-type-map name)))

(defn ^:private get-field-type
  [{:keys [field/optional field/type field/cardinality]}]
  (let [inner-type (if optional
                     (get-type-reference type)
                     (list 'not-null (get-type-reference type)))]
    (if cardinality
      (if (and (= (first cardinality) 1)
               (= (second cardinality) 1))
        inner-type
        (list 'list inner-type))
      (list 'list inner-type))))

(defn ^:private get-param-type
  [{:keys [param/optional param/type]}]
  (if optional
    (get-type-reference type)
    (list 'not-null (get-type-reference type))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private parse-param
  [{:keys [param/doc param/deprecation] :as param}]
  (cond-> {:type (get-param-type param)}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)))

(defn ^:private parse-params
  [params]
  (reduce (fn [m {:keys [param/name] :as param}]
            (assoc m (keyword name) (parse-param param)))
          {} params))

(defn ^:private parse-field
  [{:keys [field/doc field/deprecation param/_parent
           lacinia/resolve lacinia/stream] :as field}]
  (cond-> {:type (get-field-type field)}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    resolve     (assoc :resolve resolve)
    stream      (assoc :stream stream)
    _parent     (assoc :args (->> _parent (sort-by :param/name) parse-params))))

(defn ^:private parse-fields
  [fields]
  (reduce (fn [m {:keys [field/name] :as field}]
            (assoc m (keyword name) (parse-field field)))
          {} fields))

(defn ^:private parse-enum-field
  [{:keys [field/name field/doc field/deprecation] :as field}]
  (cond-> {:enum-value (keyword name)}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)))

(defn ^:private parse-enum-fields
  [fields]
  (reduce (fn [c field]
            (conj c (parse-enum-field field)))
          [] fields))

(defn ^:private parse-union-field
  [{:keys [field/name] :as field}]
  (keyword name))

(defn ^:private parse-union-fields
  [fields]
  (reduce (fn [c field]
            (conj c (parse-union-field field)))
          [] fields))

(defn ^:prvate parse-type
  [{:keys [field/_parent type/doc type/deprecation]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :fields (->> _parent (sort-by :field/name) parse-fields))))

(defn ^:prvate parse-enum
  [{:keys [field/_parent type/doc type/deprecation]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :values (->> _parent (sort-by :field/name) parse-enum-fields))))

(defn ^:prvate parse-union
  [{:keys [field/_parent type/doc type/deprecation]}]
  (cond-> {}
    doc         (assoc :description doc)
    deprecation (assoc :deprecated deprecation)
    _parent     (assoc :members (->> _parent (sort-by :field/name) parse-union-fields))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private reduce-type-fields
  [m {:keys [field/_parent] :as t}]
  (->> _parent
       (sort-by :field/name)
       (reduce (fn [f-m field]
                 (assoc f-m (-> field :field/name keyword) (parse-field field)))
               m)))

(defn ^:private reduce-type
  [m {:keys [type/name] :as t}]
  (assoc m
         (keyword name)
         (parse-type t)))

(defn ^:private reduce-enum
  [m {:keys [type/name] :as t}]
  (assoc m
         (keyword name)
         (parse-enum t)))

(defn ^:private reduce-union
  [m {:keys [type/name] :as t}]
  (assoc m
         (keyword name)
         (parse-union t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME kind of a hack because query-v3 does not pull
(defn ^:private find-and-pull
  ([selector where conn]
   (find-and-pull selector where conn nil))
  ([selector where conn sort-by-key]
   (let [eids (-> (q/q (concat '[:find ?e :where] where) @conn)
                  vec flatten)]
     (cond->> eids
       true        (d/pull-many @conn selector)
       sort-by-key (sort-by sort-by-key)))))

(def ^:private selector
  '[* {:type/implements [*]
       :field/_parent
       [* {:field/type [*]
           :param/_parent
           [* {:param/type [*]}]}]}])

(def ^:private section-map
  {:objects
   {:where '[[?e :lacinia/tag true]
             [?e :type/nature :user]
             (not [?e :type/interface true])
             (not [?e :type/enum true])
             (not [?e :type/union true])
             (not [?e :lacinia/query true])
             (not [?e :lacinia/mutation true])
             (not [?e :lacinia/subscription true])]
    :reducer reduce-type}

   :interfaces
   {:where '[[?e :type/interface true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type}

   :enums
   {:where '[[?e :type/enum true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-enum}

   :unions
   {:where '[[?e :type/union true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-union}

   :input-objects
   {:where '[[?e :lacinia/input true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type}
   
   :queries
   {:where '[[?e :lacinia/query true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type-fields}

   :mutations
   {:where '[[?e :lacinia/mutation true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type-fields}

   :subscriptions
   {:where '[[?e :lacinia/subscription true]
             [?e :lacinia/tag true]
             [?e :type/nature :user]]
    :reducer reduce-type-fields}})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn schema
  [conn]
  (reduce-kv (fn [m k {:keys [where reducer]}]
               (let [types (find-and-pull selector where conn)]
                 (if (empty? types)
                   m
                   (assoc m k (reduce (fn [m t]
                                        (reducer m t))
                                      {} types)))))
             {} section-map))

(comment
  (do
    (require '[hodur-engine.core :as engine])

    (def conn (engine/init-schema
               '[^{:lacinia/tag true}
                 default

                 ^{:doc "A physical or virtual board game."}
                 BoardGame
                 [^ID id
                  ^String name
                  ^{:type String
                    :optional true
                    :doc "A one-line summary of the game."}
                  summary
                  ^{:type String
                    :optional true
                    :doc "A long-form description of the game."}
                  description
                  ^{:type Integer
                    :optional true
                    :doc "The minimum number of players the game supports."}
                  min_players
                  ^{:type Integer
                    :optional true
                    :doc "The maximum number of players the game supports."}
                  max_players
                  ^{:type Integer
                    :optional true
                    :doc "Play time, in minutes, for a typical game."}
                  play_time]

                 ^:interface
                 Player
                 [^String name]

                 ^:enum
                 PlayerType
                 [^{:doc "Yeah! Those"}
                  AMERITRASH
                  EUROPEAN]

                 ^:union
                 SearchResult
                 [Player BoardGame]

                 ^:lacinia/input
                 PlayerInput
                 [^String name]
                 
                 ^:lacinia/query
                 QueryRoot
                 [^{:type BoardGame
                    :optional true
                    :doc "Access a BoardGame by its unique id, if it exists."
                    :lacinia/resolve :query/game-by-id}
                  game_by_id
                  [^{:type ID
                     :optional true} id]
                  ^{:type SearchResult
                    :cardinality [0 n]}
                  search
                  [^String term]]]))

    (schema conn)
    
    ))