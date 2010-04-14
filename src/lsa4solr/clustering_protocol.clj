(ns lsa4solr.clustering-protocol
  (:use [clojure.contrib.seq-utils :only [indexed]]
	[lsa4solr core hadoop-utils lucene-utils mahout-matrix])
  (:import (org.apache.hadoop.conf Configuration)
	   (org.apache.hadoop.fs FileSystem Path)
	   (org.apache.hadoop.io Text SequenceFile$Reader)
	   (org.apache.hadoop.fs.permission FsPermission)
	   (org.apache.mahout.clustering.kmeans RandomSeedGenerator KMeansDriver)))

(defprotocol LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs])
  (init-frequency-vector [self n])
  (get-frequency-matrix [self reader field terms hits])
  (cluster-docs [self reader terms doc-seq k num-clusters narrative-field id-field]))

(defn get-mapper-common [terms vec-ref ndocs update-ref]
  (proxy [org.apache.lucene.index.TermVectorMapper]
      []
    (map [term frequency offsets positions]
	 (let [term-entry ((keyword term) terms)]
	   (dosync 
	    (update-ref vec-ref (- (:idx term-entry) 1)  (* frequency (:idf term-entry))))))
    (setExpectations [field numTerms storeOffsets storePositions]
		     nil)))


(deftype DistributedLSAClusteringEngine 
  [] 
  LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs]
	      (get-mapper-common terms vec-ref ndocs
				 (fn [vec-ref idx weight]
				   (set-value @vec-ref idx weight))))

  (init-frequency-vector [self n]
			 (ref (create-vector n)))
  
  (get-frequency-matrix 
   [self reader field terms hits]
   (matrix (extract-frequency-vectors 
	    reader
	    (fn [n] (init-frequency-vector self n))
	    (fn [terms vec-ref ndocs] 
	      (get-mapper self
			  terms
			  vec-ref
			  ndocs))
	    field
	    terms
	    hits)))
  
  (cluster-docs [self 
		 reader
		 terms
		 doc-seq
		 k
		 num-clusters
		 narrative-field
		 id-field]
		(let [fm (transpose (get-frequency-matrix self
							  reader
							  narrative-field
							  terms
							  doc-seq))
		      svd-factorization (decompose-svd fm k)
		      hadoop-conf (Configuration.)
		      fs (FileSystem/get hadoop-conf)
		      base-path (Path. (str "/lsa4solr/kmeans-clustering/" (java.lang.System/nanoTime)))
		      mkdirs-result (FileSystem/mkdirs fs 
						       base-path
						       (FsPermission/getDefault))
		      U (:U svd-factorization)
		      S (:S svd-factorization) 
		      V (:V svd-factorization)
;;		      reduced-fm (mmult U (mmult S (transpose V)))
		      reduced-fm (mmult V S)
		      reduced-m-path (str (.toString base-path) "/reducedm")
;;		      writer (write-matrix hadoop-conf (transpose reduced-fm) reduced-m-path)
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
			   k
			   num-clusters)
		      tkey (Text.)
		      tval (Text.)
		      groups (clojure.contrib.seq-utils/flatten
			      (map (fn [path-string] (let [path (Path. path-string)
							   seq-reader (SequenceFile$Reader. fs path hadoop-conf) 
							   valseq (take-while (fn [v] (.next seq-reader tkey tval)) (repeat [tkey tval]))]  
						       (map #(.toString (second %)) valseq)))
				   (map #(str cluster-output-path "/points/part-0000" %) (range 0 8))))
		      clusters (apply merge-with #(into %1 %2)
				      (map #(hash-map (keyword (second %))
						      (list (get-docid reader "id" (nth doc-seq (first %1)))))
					   (indexed groups)))]
		  {:groups groups
		   :clusters clusters
		   :U U
		   :S S
		   :V V
		   :reduced-fm reduced-fm}))
  )

