(ns votemeal.service
  (:require
   [buddy.core.codecs :as codecs]
   [buddy.core.mac :as mac]
   [clj-slack.users]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [environ.core :refer [env]]
   [io.pedestal.http :as http]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.interceptor.chain :as chain]
   [ring.util.response :as ring-resp]
   [votemeal.machine :as machine])
  (:import
   [clojure.lang ExceptionInfo]
   [java.time Instant]))

(defn help-text [command]
  (format "Usage: `%1$s action [arg*]`

Available actions:
`help [publish]` - display and optionally publish this help
`new [candidate*]` - create a new poll replacing an existing one
`vote [rank*]` - vote for candidates by grouping them to comma separated ranks
- Each rank consists of one or more candidates of the same preference separated by space
- Unranked candidates are considered to be the least preferred
- You can vote more than once, only your latest vote counts
`candidates [publish]` - display and optionally publish a list of candidates
`voters [publish]` - display and optionally publish a list of users who voted till now
`close` - close the poll an publish the results

Examples:
`%1$s new apple banana cherry kiwi orange`
`%1$s vote banana, apple orange, kiwi`
`%1$s voters publish`" command))

(defonce ^:private db (atom nil))

(def slack-connection
  {:api-url "https://slack.com/api"
   :token (env :slack-access-token)})

(def max-candidates 32)

(defmulti invoke :action)

(defmethod invoke :help [{command :command [arg] :args}]
  {:response_type (if (= arg "publish") "in_channel" "ephemeral")
   :text (help-text command)})

(defmethod invoke :new [{args :args}]
  (let [candidates (set args)]
    (cond
      (< (count candidates) 2)
      {:text "There must be at least two candidates"}

      (> (count candidates) max-candidates)
      {:text (format "There can be at most %d candidates" max-candidates)}

      :else
      (do
        (machine/new! db candidates)
        {:response_type "in_channel" :text "Poll created."}))))

(defn make-ballot [candidates input]
  (let [ranks (some-> input (str/split #"\s*,\s*"))
        ranking (mapv #(str/split % #"\s+") ranks)
        ranked (flatten ranking)
        unranked (vec (set/difference candidates (set ranked)))]
    (cond
      (some->> (seq ranked) (apply (complement distinct?)))
      (throw (ex-info "Each candidate can be ranked only once" {}))

      (not-every? candidates ranked)
      (throw (ex-info "You can vote only for the specified candidates" {}))

      :else
      (cond-> ranking (seq unranked) (conj unranked)))))

(defmethod invoke :vote [{:keys [user-id input]}]
  (if-let [poll (:poll @db)]
    (try
      (let [candidates (:candidates poll)
            ballot (make-ballot candidates input)]
        (machine/vote! db user-id ballot))
      (if (str/blank? input)
        {:text "Thank you for voting! You reset your vote."}
        {:text (format "Thank you for voting! You voted:\n`%s`" input)})
      (catch ExceptionInfo e
        {:text (str "Error: " (.getMessage e))}))
    {:text "You must create a poll first."}))

(defn unidentified-users [db]
  (let [voted (-> db :poll :ballots keys set)
        identified (-> db :users keys set)]
    (set/difference voted identified)))

(defn update-users!
  "Obtains user info of yet unidentified users and adds it to db."
  [db]
  (->> @db
       (unidentified-users)
       (mapv (fn [user-id]
               (future (clj-slack.users/info slack-connection user-id))))
       (run! #(some->> @% :user (machine/add-user! db)))))

(defn user-name [user]
  (let [display-name (-> user :profile :display_name)]
    (if (str/blank? display-name)
      (:real_name user)
      display-name)))

(defmethod invoke :candidates [{[arg] :args}]
  {:response_type (if (= arg "publish") "in_channel" "ephemeral")
   :text (if-let [candidates (-> @db :poll :candidates)]
           (->> candidates
                (sort)
                (map #(str "- " %))
                (cons "*List of candidates*")
                (str/join "\n"))
           "You must create a poll first.")})

(defmethod invoke :voters [{[arg] :args}]
  (update-users! db)
  {:response_type (if (= arg "publish") "in_channel" "ephemeral")
   :text (if-let [users (:users @db)]
           (->> users
                (vals)
                (map user-name)
                (sort)
                (map #(str "- " %))
                (cons "*List of voters*")
                (str/join "\n"))
           "No votes registered.")})

(defn format-winners [winners]
  (map-indexed (fn [i rank]
                 (format "%d. %s" (inc i) (str/join ", " (sort rank))))
               winners))

(defmethod invoke :close [_]
  (if @db
    (let [{:keys [winners count]} (machine/close! db)
          results (if (pos? count)
                    (concat (format-winners winners)
                            [(str "Number of voters: " count)])
                    ["No ballots."])]
      {:response_type "in_channel"
       :text (str/join "\n" (cons "*Results*" results))})
    {:text "You must create a poll first."}))

(defmethod invoke :default [cmd]
  (invoke (assoc cmd :action :help)))

(defn votemeal
  [{{:keys [command text user_id]} :form-params}]
  (let [[action input] (str/split (str/trim text) #"\s+" 2)
        args (some-> input (str/split #"\s+"))]
    (ring-resp/response (invoke {:command command
                                 :action (keyword action)
                                 :input input
                                 :args args
                                 :user-id user_id}))))

(def check (constantly {:status 204}))

(defn recent?
  "Returns true if timestamp is less than seconds apart from now, else false."
  [timestamp seconds]
  (< (Math/abs (- (.getEpochSecond (Instant/now))
                  timestamp))
     seconds))

(def auth-interceptor
  "Interceptor to verify requests from Slack.
  https://api.slack.com/docs/verifying-requests-from-slack"
  {:name ::auth-interceptor
   :enter (fn [context]
            (let [headers (-> context :request :headers)
                  timestamp (some-> headers
                                    (get "x-slack-request-timestamp")
                                    (Integer/parseInt))
                  signature (some-> headers
                                    (get "x-slack-signature")
                                    (str/replace-first "v0=" ""))]
              (if (and timestamp signature (recent? timestamp (* 60 5)))
                (let [body (-> context :request :body slurp)
                      input (str "v0:" timestamp ":" body)]
                  (if (mac/verify input (codecs/hex->bytes signature)
                                  {:key (env :slack-signing-secret)
                                   :alg :hmac+sha256})
                    ;; ServletInputStream can be read only once, so we put a
                    ;; copy of the body back.
                    (let [body-stream (io/input-stream (.getBytes body))]
                      (update context :request assoc :body body-stream))
                    (chain/terminate context)))
                (chain/terminate context))))})

(def common-interceptors [(body-params/body-params) http/json-body])

(def command-interceptors (into [auth-interceptor] common-interceptors))

(def routes #{["/" :post (conj command-interceptors `votemeal)]
              ["/check" :get (conj common-interceptors `check)]})

;; Consumed by votemeal.server/create-server
;; See http/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Tune the Secure Headers
              ;; and specifically the Content Security Policy appropriate to your service/application
              ;; For more information, see: https://content-security-policy.com/
              ;;   See also: https://github.com/pedestal/pedestal/issues/499
              ;;::http/secure-headers {:content-security-policy-settings {:object-src "'none'"
              ;;                                                          :script-src "'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:"
              ;;                                                          :frame-ancestors "'none'"}}

              ;; Root for resource interceptor that is available by default.
              ::http/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
              ;;  This can also be your own chain provider/server-fn -- http://pedestal.io/reference/architecture-overview#_chain_provider
              ::http/type :jetty
              ::http/host "0.0.0.0"
              ::http/port (or (some-> (env :port) Integer/parseInt) 8080)
              ;; Options to pass to the container (Jetty)
              ::http/container-options {:h2c? true
                                        :h2? false
                                        ;:keystore "test/hp/keystore.jks"
                                        ;:key-password "password"
                                        ;:ssl-port 8443
                                        :ssl? false}})
