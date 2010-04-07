(ns lsa4solr.core)

(defn to-array-of [class coll] 
  (let [array (make-array class (count coll))] 
    (dorun (map (fn [item index] (aset array index item)) 
                coll 
                (iterate inc 0))) 
    array))
