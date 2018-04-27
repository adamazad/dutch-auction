(ns dutch-auction.core
  (:require [clojure.string :as str]
            [cljs.nodejs :as nodejs]

            [promesa.core :as p :include-macros true]
            [cognitect.transit :as t]

            ["express" :as express]
            ["sse-express" :as sse-express]
            ["body-parser" :as body-parser]
            ["http" :as http]
            ["sqlite3" :as sqlite]
            ["knex" :as Knex]))

(nodejs/enable-util-print!)

(def knex (Knex #js {:client           "sqlite3"
                     :connection       #js {:filename "./database.sqlite"}
                     :useNullAsDefault true}))

(defn create-tables []
  (-> (.-schema knex)
      (.dropTable "User")
      (.createTable
        "User" (fn [t]
                 (-> t (.increments "id") (.primary))
                 (-> t (.string "name" 100) (.notNullable) (.unique))))
      (.dropTable "Auction")
      (.createTable
        "Auction" (fn [t]
                    (-> t (.increments "id") (.primary))
                    (-> t (.string "title" 100) (.notNullable) (.unique))
                    (-> t (.integer "creator") (.notNullable) (.unsigned))
                    (-> t (.foreign "creator") (.references "User.id"))
                    (-> t (.dateTime "createdAt") (.notNullable) (.defaultTo (-> knex (.-fn) (.now))))
                    (-> t (.integer "initialPrice" 100))
                    (-> t (.integer "highestBid"))
                    (-> t (.integer "highestBidder") (.unsigned))
                    (-> t (.foreign "highestBidder") (.references "User.id"))))
      (p/then prn)))

(defn transact-login [uname]
  (.transaction knex
                (fn [tx f]
                  (p/alet [f (p/await (-> tx (.from "User") (.where "name" "=" uname)))]
                          (when (zero? (alength f))
                            (-> tx
                                (.insert #js {:name uname})
                                (.into "User")))))))

(def n-user 3)

(def n-auctions 3)

(defn insert-dummy-data []
  (dotimes [i n-user]
    (-> knex (.insert #js {:name (str (inc i))}) (.into "User") (.then prn)))
  (dotimes [i n-auctions]
    (-> knex
        (.insert #js {:title        (str (inc i))
                      :creator      (inc (rand-int n-user))
                      :initialPrice (+ (rand-int 300) 30)
                      :createdAt    (- (js/Date.now) (* 1000 20 i))})
        (.into "Auction")
        (.then prn))))

(defn process-auction [{:keys [createdAt highestBid initialPrice] :as m}]
  (let [p-min (/ (- (js/Date.now) createdAt) 1000 60)
        fifth (/ initialPrice 5)
        curPrice (max (js/Math.floor (- initialPrice (* p-min fifth))) 1)
        state (cond (<= curPrice highestBid) :sold
                    (= curPrice 1) :failed
                    :else :in-progress)]
    (when m
      (assoc m :createdAt createdAt :state state :curPrice curPrice))))

(defn auctions-qf [qb]
  (-> qb
      (.from "Auction")
      (.leftJoin "User as Bidder" "Auction.highestBidder" "Bidder.id")
      (.leftJoin "User as Creator" "Auction.creator" "Creator.id")
      (.select "Creator.name as creator" "Auction.title" "Auction.createdAt"
               "Auction.initialPrice" "Auction.highestBid"
               "Bidder.name as highestBidder")))

(defn query [qf & [tx]]
  (p/map #(js->clj % :keywordize-keys true) (qf (or tx knex))))

(defn find-user-id [uname tx]
  (p/map :id (query #(-> % (.from "User") (.where "name" "=" uname) (.first "id")) tx)))

(defn find-auction [title & [tx]]
  (p/map
    process-auction
    (query #(-> % (auctions-qf) (.where "title" "=" title) (.first)) (or tx knex))))

(defn transact-auction [{:keys [creator initialPrice highestBidder highestBid title] :as auction}]
  ; TODO: Run inside transaction
  (p/mapcat
    identity
    (p/alet [dbauc (p/await (find-auction title knex))
             cid (p/await (find-user-id creator knex))
             bid (p/await (find-user-id highestBidder knex))
             m (assoc auction :creator cid :highestBidder bid :createdAt (new js/Date))]
            (if dbauc
              (when (and (not= cid bid)
                         (= (:state dbauc) :in-progress)
                         (< (:highestBid dbauc) highestBid))
                (-> knex
                    (.update (clj->js (select-keys m [:highestBid :highestBidder])))
                    (.into "Auction")
                    (.where "Auction.title" "=" title)))
              (when (and cid (>= initialPrice 1))
                (-> knex
                    (.insert (clj->js (select-keys m [:title :initialPrice :creator :createdAt])))
                    (.into "Auction")))))))

(def subscribers (atom #{}))

(defn send-update [sub m]
  (.sse sub #js {:event "update" :data (t/write (t/writer :json) m)}))

(comment (-> knex (.select "Auction.title") (.from "Auction")))

(defn send-updates [& [auctitles subs]]
  (let [subs (if subs subs @subscribers)
        aucs (if auctitles
               (p/all (map find-auction auctitles))
               (query auctions-qf))]
    (p/mapcat
      (fn [aucs]
        (p/all
          (for [a aucs, s subs]
            (send-update s (process-auction a)))))
      aucs)))

(def timer (atom nil))

(defn auction-timer []
  (do (when @timer (js/clearInterval @timer))
      (reset! timer (js/setInterval send-updates 5000))))

(defn reset-auctions []
  (dotimes [i n-auctions]
    (let [title (str (inc i))]
      (-> knex
          (.update #js {:createdAt (js/Date.now)})
          (.into "Auction")
          (.where "Auction.title" "=" title)
          (.then prn))
      (send-updates [title]))))

(comment
  (.then (transact-auction {:title "2" :highestBid 80 :highestBidder "2"}) prn)
  (.then (send-updates ["2"]) prn)
  (.then (find-auction "3") prn)
  (reset-auctions)
  (do (create-tables)
      (insert-dummy-data)
      (reset-auctions)
      (auction-timer)))

(defn promise-respond [prom res]
  (p/branch prom
            (fn [r]
              (-> res
                  ;(.sendStatus 200)
                  (.send (clj->js r))
                  (.end)))
            (fn [e]
              (prn :err e)
              ())))

(def app
  (-> (express)
      (.use (.static express "resources" #js {}))
      (.use (.raw body-parser #js {:type "application/transit"}))
      (.put "/login/:name" (fn [req res next]
                             (let [uname (get (js->clj (.-params req)) "name")]
                               (p/branch (transact-login uname)
                                         #(.end res)
                                         next))))
      (.post "/auction" (fn [req res]
                          (let [auc (t/read (t/reader :json) (.-body req))]
                            (if (:title auc)
                              (p/map (fn [_]
                                       (send-updates [(:title auc)])
                                       (.end res))
                                     (transact-auction auc))
                              (next (new js/Error "no title provided") ))
                            )))
      (.get "/updates" (sse-express)
            (fn [req res]
              (p/alet [acs (p/await (query auctions-qf))
                       ms (mapv process-auction acs)]
                      (swap! subscribers conj res)
                      (.sse res #js {:event "connected" :data nil})
                      (send-updates nil [res])
                      (.on req "close" #(swap! subscribers disj res)))))
      (.use (fn [err req res next]
              (-> res
                  (.sendStatus 400)
                  (.end))))))

(defonce server (atom nil))

(defn restart []
  (create-tables)
  (insert-dummy-data)
  (when @server (.close @server))
  (let [s (.createServer http app)]
    (.listen s 3000 "0.0.0.0")
    (auction-timer)
    (reset! server s)))

(comment (restart))

(set! *main-cli-fn* restart)