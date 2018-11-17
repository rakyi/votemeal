(ns votemeal.service
  (:require
   [buddy.core.codecs :as codecs]
   [buddy.core.mac :as mac]
   [clj-slack.users]
   [clojure.core.async :as async :refer [<!! >!!]]
   [clojure.java.io :as jio]
   [clojure.set :as set]
   [clojure.string :as str]
   [environ.core :refer [env]]
   [io.pedestal.http :as http]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor.chain :as chain]
   [ring.util.response :as ring-resp]
   [votemeal.machine :as machine])
  (:import
   [clojure.lang ExceptionInfo]
   [java.time Instant]))

(def help-text
  "Usage: `/votemeal [action] [arg*]`

Available actions:
`help` - display this help
`remind` - remind current channel to vote
`vote [pair*]` - vote for places to eat
- Each pair consists of a place and score, which can be 0, 1 or 2
- If you don't vote for a place, it gets assigned implicit score of 0
- You can vote more than once, only your latest vote counts
`voters [publish]` - display and optionally publish a list of users who voted till now
`close` - publish the results and reset the votes

Examples:
`/votemeal vote quijote 2 gastrohouse 1 kantina 1`
`/votemeal voters publish`")

(defonce ^:private db (atom {:poll {} :users {}}))

(def slack-connection
  {:api-url "https://slack.com/api"
   :token (env :slack-access-token)})

(defmulti invoke :action)

(defmethod invoke :help [cmd]
  {:text help-text})

(defmethod invoke :remind [cmd]
  {:response_type "in_channel"
   :text (str "Please, vote for places to eat!\n\n" help-text)})

(defn args->scores [args]
  (if (odd? (count args))
    (throw (ex-info "Number of arguments must be even" {}))
    (let [pairs (partition 2 args)]
      (if (every? #{"0" "1" "2"} (map second pairs))
        (into {} (for [[place score] pairs]
                   [(str/lower-case place) (Integer. score)]))
        (throw (ex-info "Each score must be a number 0, 1 or 2" {}))))))

(defmethod invoke :vote [{:keys [user-id args]}]
  (try (do
         (machine/vote! db user-id (args->scores args))
         (if (seq args)
           {:text (format "Thank you for voting! You voted:\n`%s`"
                          (str/join " " args))}
           {:text "Thank you for voting! You reset your vote."}))
       (catch ExceptionInfo e
         {:text (str "Error: " (.getMessage e))})))

(defn unidentified-users [db]
  (let [{:keys [poll users]} @db
        voted (-> poll keys set)
        identified (-> users keys set)]
    (set/difference voted identified)))

(defn update-users
  "Obtains user info of yet unidentified users and adds it to db. Returns all
  users."
  [db]
  (when-let [unidentified (seq (unidentified-users db))]
    (let [c (async/chan)]
      (doseq [user-id unidentified]
        (async/thread (>!! c (clj-slack.users/info slack-connection user-id))))
      (dotimes [_ (count unidentified)]
        (when-let [user (:user (<!! c))]
          (machine/add-user! db user)))))
  (:users @db))

(defn user-name [user]
  (let [display_name (-> user :profile :display_name)]
    (if (seq display_name)
      display_name
      (:real_name user))))

(defmethod invoke :voters [{[arg] :args}]
  {:response_type (if (= arg "publish") "in_channel" "ephemeral")
   :text (if-let [users (seq (update-users db))]
           (str/join
            "\n"
            (concat
             ["*List of voters*"]
             (->> users
                  vals
                  (map user-name)
                  sort
                  (map #(str "- " %)))))
           "No votes registered.")})

(defmethod invoke :close [cmd]
  (let [{:keys [scores count]} (machine/close! db)]
    {:response_type "in_channel"
     :text (str/join
            "\n"
            (concat
             ["*Results*"]
             (if (seq scores)
               (concat
                ["```"]
                (for [[place score] (sort-by val > scores)]
                  (format "%-16s %.2f" place (float score)))
                ["```"])
               ["No scores."])
             [(str "Number of voters: " count)]))}))

(defmethod invoke :default [cmd]
  (invoke (assoc cmd :action :help)))

(defn votemeal
  [{{:keys [user_id text]} :form-params}]
  (let [[action & args] (str/split text #"\s")]
    (ring-resp/response (invoke {:user-id user_id
                                 :action (keyword action)
                                 :args args}))))

(def check (constantly {:status 204}))

(defn recent?
  "Returns true if timestamp is less than seconds apart from now, else false."
  [timestamp seconds]
  (< (Math/abs (- (.getEpochSecond (Instant/now)) timestamp))
     seconds))

(def auth-interceptor
  "Interceptor to verify requests from Slack.
  https://api.slack.com/docs/verifying-requests-from-slack"
  {:name ::auth-interceptor
   :enter (fn [context]
            (let [headers (-> context :request :headers)
                  timestamp (some-> headers
                                    (get "x-slack-request-timestamp")
                                    Integer.)
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
                    (let [body-stream (jio/input-stream (.getBytes body))]
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
              ::http/port (Integer. (or (env :port) 8080))
              ;; Options to pass to the container (Jetty)
              ::http/container-options {:h2c? true
                                        :h2? false
                                        ;:keystore "test/hp/keystore.jks"
                                        ;:key-password "password"
                                        ;:ssl-port 8443
                                        :ssl? false}})
