(ns bosquet.memory.memory
  (:require
   [bosquet.llm.openai-tokens :as oai.tokenizer]))

;; https://gentopia.readthedocs.io/en/latest/agent_components.html#long-short-term-memory
;; Memory component is used for one of the following purposes:

;; - Escaping context limitation of LLMs. eg. when you expect a very long
;;   conversation or task solving trajectory, exceeding the max_token limit
;;   of LLMs.

;; - Saving token consumption. eg. when you expect to have lengthy and
;;   unnecessary tool response (like Wikipedia Search) stored in-context.

;; https://arxiv.org/pdf/2304.03442.pdf
;; memory stream, a long-term memory module that records, in natural language,
;; a comprehensive list of the agent’s experiences.
;; A memory retrieval model combines relevance, recency, and importance to
;; surface the records needed to inform the agent’s moment-to-moment behavior.

;; The memory stream maintains a comprehensive record of the agent’s experience.
;; It is a list of memory objects, where each object contains a natural language
;; description, a creation timestamp, and a most recent access timestamp. The most basic element
;; of the memory stream is an observation, which is an event directly
;; perceived by an agent.
;;
;; Components of memory retrieval
;; - recency
;; - relevancy
;; - importance
;; - reflection

(defprotocol Memory
  ;; Encode and store an observation into memory store
  (remember [this observation])
  ;; Recall the memory by cueue
  (recall [this cueue])
  ;; Remove all memory objects matching cueue from memory store
  (forget [this cueue]))

;; (defprotocol Encoder
;;   ;; Encode an observation into a memory object
;;   (encode [this observation]))

(defprotocol Storage
  ;; Store a memory object for later retrieval via recall
  (store [this observation])
  (query [this params])
  ;; What is the size in `tokens` of the memory
  (volume [this opts]))

(defn- token-count [tokenizer-fn text model]
  (tokenizer-fn text model))

(def in-memory-memory (atom []))

(deftype AtomicStorage
         []
  Storage
  (store [_this observation]
    (swap! in-memory-memory conj observation))
  (query [_this pred] (filter pred @in-memory-memory))
    ;; TODO no passing in opts! Construct Memory with Opts and
    ;; have `volume` calc returned from Memory
  (volume [_this {service :bosquet.llm/service
                  {model :bosquet.llm/model-parameters} :model}]
    (let [tokenizer
          (condp = service
            [:llm/openai :provider/azure]  oai.tokenizer/token-count
            [:llm/openai :provider/openai] oai.tokenizer/token-count
            :else                          oai.tokenizer/token-count)]
      (reduce (fn [m txt] (+ m  (token-count tokenizer txt model)))
              0 @in-memory-memory))))

(deftype SimpleMemory
         [storage encoder retriever]
  Memory
  (remember [_this observation]
    (if (vector? observation)
      (doseq [item observation]
        (.store storage (encoder item)))
      (.store storage (encoder observation))))
  (recall [_this cueue]
    (retriever storage {}))
  (forget [_this cueue]))

;; Someone who forgets it all. To be used when memory is not needed (default)
(deftype Amnesiac
         []
  Memory
  (remember [_this observation])
  (recall [_this cueue])
  (forget [_this cueue]))

;; Encode: Chunking, Semantic, Metadata
;; Store: Atom, VectorDB
;; Retrieve: Sequential, Cueue, Query
;;

(defmacro with-memory
  "This macro will execute LLM `completions` with the aid of supplied
  `memory`.

  The macro will build a code to execute the following sequence:
  * recall needed memory items from its storage
  * inject that retrieved data into `completion` input
    -  for `chat` generation it will be ChatML messages preappended to the
       beginning of the conversation
    - for `completion` generation those will be added as extra data points to
      the Pathom execution map
  * make a LLM generation call (chat or completion)
  * remember the generated data
  * return generated data"
  [memory & completions]
  `(doseq [~'[gen-fn messages inputs params] ~completions]
     (let [~'memories (recall ~memory identity)
           ~'res      (~'gen-fn (concat ~'memories ~'messages) ~'inputs ~'params)]
       (remember ~memory ~'messages)
       (remember ~memory ~'res))))

(comment
  (require '[bosquet.llm.generator :as gen])
  (require '[bosquet.llm.chat :as chat])
  (def s (AtomicStorage.))
  (def mem (SimpleMemory.))

  (def params {chat/conversation
               {:bosquet.llm/service          [:llm/openai :provider/openai]
                :bosquet.llm/model-parameters {:temperature 0
                                               :model       "gpt-3.5-turbo"}}})
  (def inputs {:role "cook" :meal "cake"})
  (remember mem (chat/speak chat/system "You are a brilliant {{role}}."))
  (with-memory mem
    (gen/chat
     [(chat/speak chat/user "What is a good {{meal}}?")
      (chat/speak chat/assistant "Good {{meal}} is a {{meal}} that is good.")
      (chat/speak chat/user "Help me to learn the ways of a good {{meal}} by giving me one great recipe")]
     inputs params)
    (gen/chat
     [(chat/speak chat/user "How many calories are in one serving of this recipe?")]
     inputs params))

  ;; ---

  (def params {chat/conversation
               {:bosquet.memory/type          :memory/simple-short-term
                :bosquet.llm/service          [:llm/openai :provider/openai]
                :bosquet.llm/model-parameters {:temperature 0
                                               :model       "gpt-3.5-turbo"}}})

  (gen/chat
   [(chat/speak chat/system "You are a brilliant {{role}}.")
    (chat/speak chat/user "What is a good {{meal}}?")
    (chat/speak chat/assistant "Good {{meal}} is a {{meal}} that is good.")
    (chat/speak chat/user "Help me to learn the ways of a good {{meal}}.")]
   {:role "cook" :meal "cake"}
   params)

  (gen/chat
   [(chat/speak chat/user "What would be the name of this recipe?")]
   {:role "cook" :meal "cake"}
   params)

  (gen/generate
   {:role            "As a brilliant {{you-are}} answer the following question."
    :question        "What is the distance between Io and Europa?"
    :question-answer "Question: {{question}}  Answer: {% gen var-name=answer %}"
    :self-eval       "{{answer}} Is this a correct answer? {% gen var-name=test %}"}
   {:you-are  "astronomer"
    :question "What is the distance from Moon to Io?"}

   {:question-answer {:bosquet.llm/service          [:llm/openai :provider/openai]
                      :bosquet.llm/model-parameters {:temperature 0.4
                                                     :model "gpt-4"}
                       ;; ?
                      :bosquet.memory/type          :bosquet.memory/short-term}
    :self-eval       {:bosquet.llm/service          [:llm/openai :provider/openai]
                      :bosquet.llm/model-parameters {:temperature 0}}}))