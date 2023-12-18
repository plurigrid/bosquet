(ns bosquet.template.tag
  (:require
   [bosquet.complete :as complete]
   [bosquet.llm.llm :as llm]
   [bosquet.llm :as llm2]
   [bosquet.wkk :as wkk]
   [clojure.string :as string]
   [selmer.parser :as parser]
   [bosquet.llm.chat :as chat]))

(def ^:private preceding-text
  "This is where Selmer places text preceding the `gen` tag"
  :selmer/preceding-text)

(defn args->map
  "Convert tag arguments to a clojure map. Tag arguments are passed in
  as a vector of 'key=value' strings."
  [args]
  (reduce (fn [m arg]
            (let [[k v] (string/split arg #"=")]
              (assoc m (keyword k) v)))
          {} args))

(defn- var-name [tag-args]
  ;; TODO deal with var-name deprecation
  (when-let [n (or (tag-args :var) (tag-args :var-name))]
    (keyword n)))

(defn generation-params
  [tag-args {config-params wkk/llm-config :as params}]
  (let [tag-args      (args->map tag-args)
        gen-var-name  (or (var-name tag-args) wkk/default-gen-var-name)
        config-params (gen-var-name config-params)
        params        (-> params
                          (assoc wkk/gen-var-name gen-var-name)
                          (assoc-in
                           [wkk/llm-config gen-var-name]
                           (merge (dissoc tag-args :var :var-name) config-params)))]
    ;; If there were no params after merge return blank
    (when-not (= (wkk/llm-config params) {wkk/default-gen-var-name {}})
      params)))

(defn gen-tag
  "Selmer custom tag to invoke AI generation"
  [args {prompt preceding-text
         :as    opts}]
  (-> prompt
      (complete/complete (generation-params args opts))
      llm/content
      :completion))

(defn gen-tag2
  [args {prompt preceding-text
         service :service
         properties :properties}]
  (let [target (-> args first keyword)]
    (-> (llm2/chat

         (assoc
          ((get-in properties [target llm2/service]) service)
          llm2/service (get-in properties [target llm2/service]))

         (assoc
          (get-in properties [target llm2/model-params])
          :messages (chat/converse chat/user prompt)))
        llm/content
        :completion
        :content)))

(defn add-tags []
  (parser/add-tag! :gen gen-tag)
  (parser/add-tag! :gen2 gen-tag2)
  ;; for backwards compatability
  (parser/add-tag! :llm-generate gen-tag))
