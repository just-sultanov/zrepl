(ns zrepl.core-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [zrepl.core :as sut]))

(deftest ^:unit square-test
  (testing "dummy test"
    (is (= 4 (sut/square 2)))))
