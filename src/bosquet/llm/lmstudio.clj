(ns bosquet.llm.lmstudio
  (:require
   [bosquet.llm.chat :as chat]
   [bosquet.llm.llm :as llm]
   [bosquet.utils :as u]
   [clojure.string :as string]
   [hato.client :as hc]
   [taoensso.timbre :as timbre]))

(defn- fix-params
  "Snake case keys from `:max-tokens` to `:max_tokens`"
  [params]
  (reduce-kv
   (fn [m k v]
     (assoc m
            (-> k name (string/replace "-" "_") keyword)
            v))
   {}
   params))

(defn- post-completion
  [params {:keys [api-endpoint]}]
  (let [res (hc/post (str api-endpoint "/chat/completions")
                     {:content-type :json
                      :body         (-> params fix-params u/write-json)})]
    (-> res
        :body
        (u/read-json))))

(defn- ->completion [{choices :choices {prompt_tokens     :prompt_tokens
                                        completion_tokens :completion_tokens
                                        total_tokens      :total_tokens} :usage}]
  {llm/generation-type :chat
   llm/content         {:completion (-> choices first :message chat/chatml->bosquet :content)}
   llm/usage           {:prompt     prompt_tokens
                        :completion completion_tokens
                        :total      total_tokens}})

(defn- chat-completion
  [messages params opts]
  (timbre/infof "💬 Calling LM Studio with:")
  (timbre/infof "\tParams: '%s'" (dissoc params :prompt))
  (timbre/infof "\tConfig: '%s'" opts)
  (let [messages (if (string? messages)
                   [(chat/speak chat/user messages)]
                   messages)]
    (try
      (let [result
            (-> params
                (assoc :messages messages)
                (post-completion opts)
                ->completion)]
        (tap> result)
        result
        )
      (catch Exception e
        (throw (ex-info "LM Studio error" (-> e ex-data :body u/read-json)))))))

(deftype LMStudio
    [opts]
    llm/LLM
    (service-name [_this] ::lm-studio)
    (generate [this prompt params]
      (timbre/warn "LMStudio does not support 'completions'. Forcing to 'chat'.")
      (.chat this prompt params))
    (chat     [_this conversation params]
      (chat-completion conversation params opts)))

(comment
  (def llm (LMStudio.
            {:api-endpoint "http://localhost:1234/v1"}))
  (.chat llm
         [(chat/speak chat/system "You are a brilliant cook.")
          (chat/speak chat/user "What is a good cookie?")]
         {})
  #__)
