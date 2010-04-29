(ns lsa4solr.clustering-protocol
  (:use	[lsa4solr core hadoop-utils lucene-utils mahout-matrix hierarchical-clustering dendrogram])
  (:require [clojure [zip :as z]])
  (:require [clojure.contrib 
	     [seq-utils :as seq-utils]
	     [zip-filter :as zf]])
  (:import (org.apache.hadoop.conf Configuration)
	   (org.apache.hadoop.fs FileSystem Path)
	   (org.apache.hadoop.io Text SequenceFile$Reader)
	   (org.apache.hadoop.fs.permission FsPermission)
	   (org.apache.mahout.clustering.kmeans RandomSeedGenerator KMeansDriver)))

(defn kmeans-cluster
    [num-clusters max-iterations V S]
    (let [hadoop-conf (Configuration.)
	  fs (FileSystem/get hadoop-conf)
	  base-path (Path. (str "/lsa4solr/kmeans-clustering/" (java.lang.System/nanoTime)))
	  mkdirs-result (FileSystem/mkdirs fs 
					   base-path
					   (FsPermission/getDefault))
	  reduced-fm (mmult V S)
	  reduced-m-path (str (.toString base-path) "/reducedm")
	  writer (write-matrix hadoop-conf reduced-fm reduced-m-path)
	  initial-centroids (RandomSeedGenerator/buildRandom reduced-m-path
							     (str (.toString base-path) "/centroids") 
							     num-clusters)
	  cluster-output-path (str (.toString base-path) "/clusterout")
	  job (KMeansDriver/runJob
	       reduced-m-path
	       (.toString initial-centroids)
	       cluster-output-path
	       "org.apache.mahout.common.distance.CosineDistanceMeasure"
	       0.00000001 
	       max-iterations
	       num-clusters)
	  tkey (Text.)
	  tval (Text.)
	  groups (clojure.contrib.seq-utils/flatten
		  (map (fn [file-status] (let [path (.getPath file-status)
					       seq-reader (SequenceFile$Reader. fs path hadoop-conf) 
					       valseq (take-while (fn [v] (.next seq-reader tkey tval))
								  (repeat [tkey tval]))]  
					   (map #(.toString (second %)) valseq)))
		       (.globStatus fs (Path. (str cluster-output-path "/points/part*")))))]
      groups))

(defn emit-leaf-node-fn
  [reader doc-seq id-field]
  (fn [node]
    (hash-map "name" (get-docid reader id-field (nth doc-seq (:id node)))
	      "id" (get-docid reader id-field (nth doc-seq (:id node)))
	      "data" {}
	      "children" [])))

(defn emit-branch-node-fn
  []
  (let [id (ref 0)]
    (fn [node children-arr]
      (hash-map "name" (:count (meta node))
		"id" (dosync (alter id inc))
		"data" (hash-map "count" (:count (meta node)))
		"children" children-arr))))

(defn hierarchical-clustering
  [reader id-field doc-seq mat]
  (let [[dend merge-sequence] (last (hclust mat))]
     (dendrogram-to-map dend (emit-branch-node-fn) (emit-leaf-node-fn reader doc-seq id-field))))

(defn get-mapper-common [terms vec-ref ndocs update-ref]
  (proxy [org.apache.lucene.index.TermVectorMapper]
      []
    (map [term frequency offsets positions]
	 (let [term-entry ((keyword term) terms)]
	   (dosync 
	    (update-ref vec-ref (- (:idx term-entry) 1)  (* frequency (:idf term-entry))))))
    (setExpectations [field numTerms storeOffsets storePositions]
		     nil)))


(defn get-mapper
  [terms vec-ref ndocs]
  (get-mapper-common terms vec-ref ndocs
		     (fn [vec-ref idx weight]
		       (set-value @vec-ref idx weight))))

(defn init-frequency-vector
  [n]
  (ref (create-vector n)))
  
(defn get-frequency-matrix 
  [reader field terms hits]
  (distributed-matrix (extract-frequency-vectors 
		       reader
		       (fn [n] (init-frequency-vector n))
		       (fn [terms vec-ref ndocs] 
			 (get-mapper terms
				     vec-ref
				     ndocs))
		       field
		       terms
		       hits)))

(defn decompose-term-doc-matrix
  [reader narrative-field terms doc-seq k]
  (let [fm (transpose (get-frequency-matrix reader
					    narrative-field
					    terms
					    doc-seq))
	svd-factorization (decompose-svd fm k)
	U (:U svd-factorization)
	S (:S svd-factorization) 
	V (:V svd-factorization)]
    (list U S V)))

(defn cluster-kmeans-docs 
  [reader
   terms
   doc-seq
   k
   num-clusters
   narrative-field
   id-field]
  (let [[U S V] (decompose-term-doc-matrix reader narrative-field terms doc-seq k)
	groups (kmeans-cluster num-clusters k V S)
	clusters (apply merge-with #(into %1 %2)
			(map #(hash-map (keyword (second %))
					(list (get-docid reader id-field (nth doc-seq (first %1)))))
			     (seq-utils/indexed groups)))]
    {:clusters clusters
     :U U
     :S S
     :V V}))

(defn cluster-hierarchical-docs 
  [reader
   terms
   doc-seq
   k
   narrative-field
   id-field]
  (let [[U S V] (decompose-term-doc-matrix reader narrative-field terms doc-seq k)
	SVt (transpose (mmult S (transpose V)))
	clusters (hierarchical-clustering reader id-field doc-seq SVt)]
    {:clusters clusters
     :U U
     :S S
     :V V}))