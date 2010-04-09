(ns lsa4solr.cluster
  (:use [clojure.contrib.seq-utils :only [indexed]]
	[lsa4solr core clustering-protocol]
	[incanter.core]
	[incanter.stats])
  (:import (cern.colt.matrix.tdouble.algo.decomposition DoubleSingularValueDecomposition)
	   (incanter Matrix)))

(gen-class
 :name lsa4solr.cluster/LSAClusteringEngine
 :extends org.apache.solr.handler.clustering.SearchClusteringEngine
 :exposes-methods {init superinit}
 :init initialize-state
 :state state)

(defn -initialize-state []
    [[] (ref {})])

(defn init-term-freq-doc [reader field]
  (let [terms (. reader terms)
	numdocs (.maxDoc reader)
	counter (let [count (ref 0)] #(dosync (alter count inc)))]
    (apply merge
	   (take-while 
	    #(= (nil? %1) false) 
	    (repeatedly 
	     (fn [] 
	       (if (and (. terms next) (= field (.field (. terms term))))
		 (let [text (. (. terms term) text)
		       df (. terms docFreq)]
		   {(keyword text) 
		    {
		     :df df
		     :idf (log2 (/ numdocs df))
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
	id-field (.get config "id-field")
	name (.get config "name")]
    (dosync 
     (alter (.state this) assoc 
	    :reader reader 
	    :name name 
	    :narrative-field narrative-field 
	    :id-field id-field
	    :terms (init-term-freq-doc reader narrative-field))
     name)))

(defn cluster-dispatch [clustering-protocol
			reader
			field
			id-field
			terms
			doc-list
			k
			num-clusters]
  (let [doc-seq (iterator-seq (.iterator doc-list))
	m (get-frequency-matrix clustering-protocol reader field terms doc-seq)
	svd-factorization (svd clustering-protocol k m)
	clusters (cluster-docs clustering-protocol reader doc-seq svd-factorization k num-clusters id-field)]
    {:clusters clusters
     :svd svd-factorization}))


(defn -cluster [this
		query
		doc-list
		solr-request]
  (let [algorithm (.get (.getParams solr-request) "mode")
	engine (cond (= "distributed" algorithm) (DistributedLSAClusteringEngine)
			   :else (LocalLSAClusteringEngine))
	result (cluster-dispatch engine
				 (:reader @(.state this)) 
				 (:narrative-field @(.state this)) 
				 (:id-field @(.state this))
				 (:terms @(.state this)) 
				 doc-list 
				 (Integer. (.get (.getParams solr-request) "k"))
				 (Integer. (.get (.getParams solr-request) "nclusters")))]
    (:clusters result)))
