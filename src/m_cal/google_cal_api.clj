(ns m-cal.google-cal-api
  (:import (java.io File)
           (java.util Date Collections)
           (java.util Collections)
           (java.io ByteArrayInputStream BufferedReader InputStreamReader)
           (java.security KeyFactory PrivateKey PublicKey Security)
           (com.google.api.client.googleapis.auth.oauth2 GoogleCredential GoogleCredential$Builder)
           (com.google.api.client.googleapis.javanet GoogleNetHttpTransport)
           (com.google.api.client.http HttpTransport)
           (com.google.api.client.json JsonFactory)
           (com.google.api.client.json.jackson2 JacksonFactory)
           (com.google.api.services.calendar CalendarScopes Calendar$Builder)
           (com.google.api.services.calendar.model Event EventDateTime)
           (com.google.api.client.util DateTime)
           (org.bouncycastle.jce.provider BouncyCastleProvider)
           (org.bouncycastle.openssl PEMParser)
           (org.bouncycastle.openssl.jcajce JcaPEMKeyConverter)))


(def jsonFactory (JacksonFactory/getDefaultInstance))
(def httpTransport (GoogleNetHttpTransport/newTrustedTransport))
(def applicationName "M-Calendar/0.1")
(def serviceAccountEmail (System/getenv "SERVICE_ACCOUNT_EMAIL"))
(def calendarId (System/getenv "CALENDAR_ID"))
(def privateKey (System/getenv "CALENDAR_PRIVATE_KEY"))
(def parsed-private-key (atom ""))
(def calendar-lock (java.lang.Object.))

(defn setup
  []
  (Security/addProvider (BouncyCastleProvider.)))

(defn read-pem-private-key
  []
  (let [input-stream (ByteArrayInputStream. (. privateKey getBytes))
        pemParser (PEMParser. (BufferedReader. (InputStreamReader. input-stream)))
        converter (.. (JcaPEMKeyConverter.) (setProvider "BC"))]
    (. converter getPrivateKey (. pemParser readObject))))

(defn private-key
  []
  (when (= @parsed-private-key "")
    (compare-and-set! parsed-private-key "" (read-pem-private-key)))
  @parsed-private-key)

(defn calendar
  []
  (let
      [credential (.. (GoogleCredential$Builder.)
                      (setTransport httpTransport)
                      (setJsonFactory jsonFactory)
                      (setServiceAccountId serviceAccountEmail)
                      (setServiceAccountPrivateKey (private-key))
                      (setServiceAccountScopes (Collections/singleton CalendarScopes/CALENDAR))
                      (build))]
    (.. (Calendar$Builder. httpTransport jsonFactory credential)
        (setApplicationName applicationName)
        (build))))

(defn print-calendar-list
  []
  (let [calendars (.. (calendar)
                      (calendarList)
                      (list)
                      (execute)
                      (getItems))]
    (when calendars
      (doseq [calendar calendars]
        (println calendar)))))

(defn get-bookings
  [cal]
  (.. cal
      (events)
      (list calendarId)
      (execute)
      (getItems)))

(defn bookings-contain
  [candidate-dates bookings]
  (letfn [(date-is-in [d]
            (not-empty (filter #(= d %) candidate-dates)))]
    (not-empty (filter date-is-in (map #(:date %) bookings)))))

(defn list-bookings
  [& [cal]]
  (let [bookings (get-bookings (or cal (calendar)))]
    (map #(hash-map :description (. % getDescription)
                    :name (. % getSummary)
                    :date (.. % getStart getDate toString))
         bookings)))

(defn print-bookings
  []
  (let [bookings (get-bookings)]
    (when bookings
      (doseq [booking bookings]
        (println booking)))))

(defn make-event-date
  [booking-date]
    (. (EventDateTime.) (setDate (DateTime. booking-date))))

(defn add-booking
  [cal booking-date name email boat]
  (let [event (Event.)
        event-date (make-event-date booking-date)]
    (doto event
      (.setSummary name)
      (.setDescription (str boat " " email))
      (.setStart event-date)
      (.setEnd event-date)
      (.setStatus "confirmed"))
    (.. cal
        (events)
        (insert calendarId event)
        (execute))))

(defn insert-bookings
  [{dates "dates"
    name "name"
    email "email"
    boat "boat"}]
  (locking calendar-lock
    (let [cal (calendar)
          bookings (list-bookings cal)
          conflicting-dates (bookings-contain dates bookings)]
      (if conflicting-dates
        {:status 409   ;; conflict
         :body {:result "Nok"
                :alreadyBookedDates bookings}}
        (do (doall (map #(add-booking cal % name email boat) dates))
            (println "Returning results")
            {:body {:result "Ok"
                    :name name
                    :email email
                    :boat boat
                    :dates dates}})))))
