(ns lsa4solr.cluster
  (:use [clojure.contrib.seq-utils :only [indexed]]
	[incanter.core]
	[incanter.stats])
  (:import (cern.colt.matrix.tdouble.algo.decomposition DoubleSingularValueDecomposition)
	   (incanter Matrix))
  (:gen-class
   :name lsa4solr.cluster/LSAClusteringEngine
   :extends org.apache.solr.handler.clustering.SearchClusteringEngine
   :exposes-methods {init superinit}
   :init initialize-state
   :state state))

(defn -initialize-state []
    [[] (ref {})])

(defn init-term-freq-doc [reader field]
  (let [terms (. reader terms)
	counter (let [count (ref 0)] #(dosync (alter count inc)))]
    (apply merge
	   (take-while 
	    #(= (nil? %1) false) 
	    (repeatedly 
	     (fn [] 
	       (if (and (. terms next) (= field (.field (. terms term))))
		 (let [text (. (. terms term) text)]
		   {(keyword text) 
		    {
		     :df (. terms docFreq)
		     :idx (counter)
		     }
		    })
		 nil)))))))

(defn -init [this
	     config
	     solr-core]
  (let [super-result (.superinit this config solr-core)
	reader (.getReader (.get (.getSearcher solr-core true true nil)))
	narrative-field (.get config "narrative-field")
	name (.get config "name")]
    (dosync 
     (alter 
      (.state this) assoc :reader reader :name name :narrative-field narrative-field :terms (init-term-freq-doc reader narrative-field) )
     name)))


(defn get-mapper [terms vec-ref]
     (proxy [org.apache.lucene.index.TermVectorMapper]
	 []
       (map [term frequency offsets positions]
	    (dosync (alter vec-ref assoc (- (:idx ((keyword term) terms)) 1) frequency)))
       (setExpectations [field numTerms storeOffsets storePositions]
			nil)))

(defn init-frequency-vector [n]
  (ref (vec (repeat n 0))))

(defn get-frequency-matrix [reader field terms hits]
  (pmap #(let [m (init-frequency-vector (length terms))
	       mapper (get-mapper terms m)]
	   (do
	     (. reader getTermFreqVector (int %1) field mapper)
	     @m))
	hits))

(defn get-docid [reader id] (.stringValue (.getField (.document reader id) "id")))

(defn cluster [reader
	       field
	       terms
	       doc-list
	       k
	       num-clusters]
  (let [doc-seq (iterator-seq (.iterator doc-list))
	m (trans (matrix (get-frequency-matrix reader field terms doc-seq)))
	svd (DoubleSingularValueDecomposition. m)
	U (Matrix. (.getU svd))
	S (Matrix. (.getS svd))
	V (Matrix. (.getV svd))
	VS (mmult (sel V :cols (range 0 k)) 
		  (sel (sel S :cols (range 0 k)) :rows (range 0 k)))
	pca (principal-components VS)
	pcs (sel (:rotation pca) :cols (range 0 num-clusters))
	sims (map (fn [docvec] 
		    (sort-by #(second %) 
			     (map (fn [pc] 
				    [(first pc) (cosine-similarity docvec (second pc))]) 
				  (indexed (trans pcs))))) 
		  VS)
	labels (clojure.contrib.seq-utils/indexed (map #(first (last %)) sims))]
    (reduce #(merge %1 %2) 
	    {}
	    (map 
	     (fn [x] 
	       {(keyword (str x)) (map #(get-docid reader (nth doc-seq %)) (map first (filter #(= (second %) x) labels)))})
	     (range 0 num-clusters)))))

(defn -cluster [this
		query
		doc-list
		solr-request]
  (cluster 
   (:reader @(.state this)) 
   (:narrative-field @(.state this)) 
   (:terms @(.state this)) 
   doc-list 
   (Integer. (.get (.getParams solr-request) "k"))
   (Integer. (.get (.getParams solr-request) "nclusters"))))
