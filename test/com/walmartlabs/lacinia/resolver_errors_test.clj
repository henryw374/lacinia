; Copyright (c) 2017-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns com.walmartlabs.lacinia.resolver-errors-test
  "Tests for errors and exceptions inside field resolvers, and for the exception converter."
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
   [com.walmartlabs.test-utils :refer [execute simplify] :as utils]
   [com.walmartlabs.lacinia.schema :as schema]
   [com.walmartlabs.lacinia :as lacinia])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private failure-exception (ex-info "Fail!" {:reason :testing}))

(def ^:private resolver-map
  {:single-error (fn [_ _ _]
                   (resolve-as nil {:message "Exception in error_field resolver."}))
   :exception (fn [_ _ _]
                (throw failure-exception))
   :multiple-errors (fn [_ _ _]
                      (resolve-as "Value"
                                  [{:message "1" :other-key 100}
                                   {:message "2"}
                                   {:message "3"}
                                   {:message "4"}]))
   :resolve-root (fn [_ _ _] {})})

(def default-schema
  (utils/compile-schema "field-resolver-errors.edn"
                        resolver-map))

;; This now bubbles up and out with no special handling or reporting.
(deftest exception-inside-resolver
  (when-let [e (is (thrown? ExceptionInfo
                            (execute default-schema
                                     "{ root { exception (range: 5) }}")))]
    (is (identical? failure-exception e))))

(deftest field-with-single-error
  (is (= {:data {:root {:error_field nil}}
          :errors [{:locations [{:column 10
                                 :line 1}]
                    :message "Exception in error_field resolver."
                    :path [:root :error_field]}]}
         (execute default-schema
                  "{ root { error_field }}"))))

(deftest field-with-multiple-errors
  (is (= [{:locations [{:column 10
                        :line 1}]
           :message "1"
           :path [:root :multiple_errors_field]
           :extensions {:other-key 100}}
          {:locations [{:column 10
                        :line 1}]
           :message "2"
           :path [:root :multiple_errors_field]}
          {:locations [{:column 10
                        :line 1}]
           :message "3"
           :path [:root :multiple_errors_field]}
          {:locations [{:column 10
                        :line 1}]
           :message "4"
           :path [:root :multiple_errors_field]}]
         (->> (execute default-schema "{ root { multiple_errors_field }}")
              :errors
              (sort-by :message)))))

(deftest errors-are-propagated
  (testing "errors are propagated from sub-selectors even when no data is returned"
    (let [container-data {"empty-container" {:contents []}
                          "full-container" {:contents [{:name "Book"}
                                                       {:name "Picture"}]}}
          schema (schema/compile {:objects
                                  {:item
                                   {:fields {:name {:type 'String}}}
                                   :container
                                   {:fields {:id {:type 'String}
                                             :contents {:type '(list :item)
                                                        :resolve (fn [ctx args v]
                                                                   (resolve-as
                                                                    (:contents v)
                                                                    [{:message "Some error"}]))}}}}
                                  :queries
                                  {:container {:type :container
                                               :args {:id {:type 'String}}
                                               :resolve (fn [ctx args v]
                                                          (get container-data (:id args)))}}})
          execute-query (fn [q]
                          (-> (lacinia/execute schema q {} nil nil)
                              (simplify)))]
      (testing "when the sub-selector returns data"
        (let [q "query foo { container(id:\"full-container\") { contents { name } } }"]
          (is (= {:data {:container {:contents [{:name "Book"}
                                                {:name "Picture"}]}}
                  :errors [{:locations [{:column 46
                                         :line 1}]
                            :message "Some error"
                            :path [:container :contents]}]}
                 (execute-query q)))))
      (testing "when the sub-selector returns an empty collection"
        (let [q "query foo { container(id:\"empty-container\") { contents { name } } }"]
          (is (= {:data {:container {:contents []}}
                  :errors [{:locations [{:column 47
                                         :line 1}]
                            :message "Some error"
                            :path [:container :contents]}]}
                 (execute-query q))))))))
