(ns dutch-auction.client
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [promesa.core :as p]
            [cognitect.transit :as t]))

(enable-console-print!)

(def root "http://localhost:3000/")

(rf/reg-event-db
  :login
  (fn [db [_ uname resp err]]
    (cond
      resp
      (assoc db :uname uname)
      err
      (assoc db :login-err err)
      :else
      (do
        (-> (js/fetch (str root "login/" uname) #js {:method "PUT"})
            (.then #(rf/dispatch [:login uname %]))
            (.catch #(rf/dispatch [:login uname nil %])))
        db))))

(rf/reg-event-db :merge-auction
                 (fn [db [_ {:keys [title] :as auction}]]
                   (if title
                     (apply update-in db [:auctions title] merge auction)
                     db)))

(rf/reg-event-db
  :subscribe
  (fn [db]
    (let [es (new js/EventSource (str root "updates"))]
      (when (:es db) (.close (:es db)))
      (.addEventListener
        es "update" #(rf/dispatch [:merge-auction (t/read (t/reader :json) (.-data %))]))
      (assoc db :es es))))

(rf/reg-sub :auction (fn [db [_ title]] (get-in db [:auctions title])))

(rf/reg-sub :login (fn [db] (:uname db)))

(rf/reg-sub :auction-titles
            (fn [db]
              (let [as (vals (get db :auctions))
                    titles (map :title (sort-by (juxt :state :createdAt :title) as))]
                titles)))

(rf/reg-event-db :login-button
                 (fn [{:keys [curname uvalid?] :as db}]
                   (if uvalid?
                     (do (rf/dispatch [:login curname])
                         db)
                     db)))

(rf/reg-event-db :auction (fn [db [_ auction]]
                            (js/fetch (str root "auction")
                                      #js {:method  "POST"
                                           :body    (t/write
                                                      (t/writer :json)
                                                      auction)
                                           :headers #js {"Content-Type" "application/transit"}})
                            db))

(rf/reg-event-db :bid
                 (fn [db [_ title]]
                   (let [{:keys [bid-valid? bid-price] :as a} (get-in db [:auctions title])]
                     (when bid-valid?
                       (rf/dispatch [:auction (assoc a :highestBidder (:uname db)
                                                       :highestBid bid-price)]))
                     db)))

(rf/reg-event-db :start-auction
                 (fn [db]
                   (rf/dispatch [:auction {:creator      (:uname db)
                                           :title        (-> db :new-auction :title)
                                           :initialPrice (-> db :new-auction :initialPrice)}])
                   db))

(rf/reg-event-db :new-auction-set [(rf/path :new-auction)]
                 (fn [{:keys [] :as db} [_ field value]]
                   (assoc db field value)))

(rf/reg-event-db :assoc (fn [db [_ & kvs]] (apply assoc db kvs)))
(rf/reg-event-db :reset (fn [db] {}))
(rf/reg-event-db :fn (fn [db [_ f & args]] (apply f db args)))

(rf/reg-sub :all (fn [db] db))

(comment
  (-> (js/fetch (str root "auction")
                (-> (js/fetch (str root "auction")
                              #js {:method  "POST"
                                   :body    (t/write (t/writer :json)
                                                     {:highestBidder "2"
                                                      :title         "3"
                                                      :highestBid    31})
                                   :headers #js {"Content-Type" "application/transit"}})
                    (.catch prn)
                    (.then prn)))))

(comment
  (rf/dispatch [:login "testname"])
  (rf/dispatch [:assoc :uname "2" :uvalid? true])
  (rf/dispatch [:merge-auction {:title "9" :blubb :bla}])
  (rf/dispatch [:reset])
  (rf/dispatch [:subscribe])
  (rf/dispatch [:fn update :auctions dissoc nil])

  @(rf/subscribe [:get :uname])
  @(rf/subscribe [:all])
  @(rf/subscribe [:auction "3"]))

(comment
  (let [es (new js/EventSource (str root "updates"))]
    (.addEventListener es "update"
                       #(prn (t/read (t/reader :json) (.-data %))))))

(defn ui-auction [title]
  (let [a @(rf/subscribe [:auction title])
        {:keys [title initialPrice creator name curPrice
                state highestBidder highestBid bid-valid?]} a
        login @(rf/subscribe [:login])
        seller? (= creator login)
        buyer? (= highestBidder login)]
    [:div {:class "list-group-item flex-column align-items-start"}
     [:div {:class "d-flex w-100 justify-content-between"}
      [:h5 (str title " (" (case state :in-progress "running" :failed "passed" :sold "sold" "error!") ")")]
      [:small (str initialPrice " CHF")]]
     [:div {:class "d-flex w-100 justify-content-between" :style {:padding-top "20px"}}
      [:h6 (str "Seller: " (if seller? "Me" (or name creator)))]
      (if (= :in-progress state) [:small (str "Current Price: " curPrice " CHF")])]
     (cond (and (= :in-progress state) (not seller?))
           [:div {:class "input-group mb-3"}
            [:input {:type        "number"
                     :class       "form-control"
                     :placeholder "Amount"
                     :step        "1" :min "1" :max initialPrice
                     :on-change   #(rf/dispatch [:merge-auction
                                                 {:title      title
                                                  :bid-valid? (-> % (.-target) (.-validity) (.-valid))
                                                  :bid-price  (-> % (.-target) (.-value) (js/parseInt))}])}]
            [:div {:class "input-group-append"}
             [:button {:class    "btn btn-outline-secondary" :type "button"
                       :on-click #(do (.preventDefault %) (rf/dispatch [:bid title]) false)}
              "Bid!"]]]
           (= state :sold)
           [:div {:class "d-flex w-100 justify-content-between" :style {:padding-top "20px"}}
            [:h6 (str "Buyer: " (if buyer? "Me" highestBidder))]
            [:small (str "Price: " highestBid " CHF")]])]))

(defn ui-new-auction []
  [:form {:class     "form-inline" :style {:padding-bottom 25}
          :on-submit #(do (.preventDefault %) (rf/dispatch [:start-auction]))}
   [:label {:class "mr-sm-2"} "New Auction"]
   [:input {:type      "number" :step "1" :placeholder "Price"
            :on-change #(rf/dispatch [:new-auction-set :initialPrice
                                      (-> % (.-target) (.-value) (js/parseInt))])}]
   [:input {:type      "text" :pattern "\\w{3,100}" :placeholder "Title"
            :on-change #(rf/dispatch [:new-auction-set :title
                                      (-> % (.-target) (.-value))])}]
   [:button {:class    "btn btn-primary" :style {:margin-left 10}
             :type "submit"} "Start"]])


(defn ui-root []
  [:div {:class "container"}
   (if @(rf/subscribe [:login])
     [:div {:class "list-group" :style {:padding-top "40px"}}
      [ui-new-auction]
      (doall
        (for [t @(rf/subscribe [:auction-titles])]
          ^{:key t} [ui-auction t]))]
     [:div {:style {:display         "flex"
                    :padding-top     "20px"
                    :justify-content "center"
                    :align-items     "center"}}
      [:form.form-inline {:on-submit #(do (.preventDefault %))}
       [:div.input-group
        [:label.mr-sm-2 "Welcome"]
        [:input.form-control.mb-2.mr-sm-2.mb-sm-0
         {:class       ""
          :id          "login"
          :placeholder "Login..."
          :type        "text"
          :pattern     "\\w{1,100}"
          :on-change   #(rf/dispatch [:assoc
                                      :curname (-> % (.-target) (.-value))
                                      :uvalid? (-> % (.-target) (.-validity) (.-valid))])}]]
       [:button {:class    "btn btn-primary"
                 :type     "button"
                 :id       "search"
                 :value    "search"
                 :on-click #(rf/dispatch [:login-button])}
        "Submit"]]])
   [:link {:rel  "stylesheet"
           :href "//stackpath.bootstrapcdn.com/bootstrap/4.1.0/css/bootstrap.min.css"}]
   ])

(defn init []
  (r/render [ui-root] (.getElementById js/document "app"))
  (rf/dispatch [:subscribe]))

(comment (init))

(comment
  (do (defonce uit (atom nil))
      (when @uit (js/clearInterval @uit))
      (init)
      (js/setInterval init 2000)))

(init)

