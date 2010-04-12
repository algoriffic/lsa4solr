(ns lsa4solr.clustering-protocol
  (:use [clojure.contrib.seq-utils :only [indexed]]
	[lsa4solr core hadoop-utils lucene-utils]
	[incanter.core]
	[incanter.stats])
  (:import (cern.colt.matrix.tdouble.algo.decomposition DoubleSingularValueDecomposition)
	   (incanter Matrix)))

(defprotocol LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs])
  (init-frequency-vector [self n])
  (get-frequency-matrix [self reader field terms hits])
  (svd [self k m])
  (cluster-docs [self reader doc-seq svd-factorization k num-clusters id-field]))

(defn get-mapper-common [terms vec-ref ndocs update-ref]
  (proxy [org.apache.lucene.index.TermVectorMapper]
      []
    (map [term frequency offsets positions]
	 (let [term-entry ((keyword term) terms)]
	   (dosync 
	    (update-ref vec-ref (- (:idx term-entry) 1)  (* frequency (:idf term-entry))))))
    (setExpectations [field numTerms storeOffsets storePositions]
		     nil)))


(deftype LocalLSAClusteringEngine 
  []
  LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs]
	      (get-mapper-common terms vec-ref ndocs
				 (fn [vec-ref idx weight]
				   (alter vec-ref assoc idx weight))))
  
  (init-frequency-vector [self n]
			 (ref (vec (repeat n 0))))
  
  (get-frequency-matrix [self reader field terms hits]
			(trans (matrix (extract-frequency-vectors
					reader
					(fn [n] (init-frequency-vector self n))
					(fn [terms vec-ref ndocs] 
					  (get-mapper self
						      terms
						      vec-ref
						      ndocs))
					field
					terms
					hits))))
  (svd [self k m] 
       (let [svd-result (DoubleSingularValueDecomposition. m)]
	 {:U (Matrix. (.getU svd-result))
	  :S (Matrix. (.getS svd-result))
	  :V (Matrix. (.getV svd-result))}))
  
  (cluster-docs [self reader doc-seq svd-factorization k num-clusters id-field]
		(let [U (:U svd-factorization)
		      S (:S svd-factorization)
		      V (:V svd-factorization)
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
		      labels (clojure.contrib.seq-utils/indexed (map #(first (last %)) sims))
		      clusters (reduce #(merge %1 %2) 
				       {} 
				       (map (fn [x] {(keyword (str x)) 
						     (map #(get-docid reader
								      id-field
								      (nth doc-seq %)) 
							  (map first
							       (filter #(= (second %) x) 
								       labels)))})
					    (range 0 num-clusters)))]
		  clusters))
  )

(deftype DistributedLSAClusteringEngine 
  [] 
  LSAClusteringEngineProtocol
  (get-mapper [self terms vec-ref ndocs]
	      (get-mapper-common terms vec-ref ndocs
				 (fn [vec-ref idx weight]
				   (.setQuick @vec-ref idx weight))))

  (init-frequency-vector [self n]
			 (ref (new org.apache.mahout.math.RandomAccessSparseVector n)))
  
  (get-frequency-matrix [self reader field terms hits]
			(let [rows (to-array-of org.apache.mahout.math.Vector
						(extract-frequency-vectors
						 reader
						 (fn [n] (init-frequency-vector self n))
						 (fn [terms vec-ref ndocs] 
						   (get-mapper self
							       terms
							       vec-ref
							       ndocs))
						 field
						 terms
						 hits))]
			  (.transpose (new org.apache.mahout.math.SparseRowMatrix 
					   (int-array [(count rows) (count terms)]) 
					   rows))))

  (svd [self k m]
       (let [hadoop-conf (new org.apache.hadoop.conf.Configuration)
	     fs (org.apache.hadoop.fs.FileSystem/get hadoop-conf)
	     base-path (org.apache.hadoop.fs.Path. (str "/doc-clustering/" (java.lang.System/nanoTime)))
	     mkdirs-result (org.apache.hadoop.fs.FileSystem/mkdirs fs 
								   base-path
								   (org.apache.hadoop.fs.permission.FsPermission/getDefault))
	     m-path (str (.toString base-path) "/mtosvd")
	     writer (write-matrix hadoop-conf m m-path)
	     dm (doto (new org.apache.mahout.math.hadoop.DistributedRowMatrix 
			   m-path
			   (str (.toString base-path) "/svdout")
			   (.numRows m)
			   (.numCols m))
		  (.configure (new org.apache.hadoop.mapred.JobConf hadoop-conf)))
	     eigenvalues (new java.util.ArrayList)
	     eigenvectors (new org.apache.mahout.math.DenseMatrix (+ k 2) (.numCols m))
	     decomposer (doto (new org.apache.mahout.math.hadoop.decomposer.DistributedLanczosSolver)
			  (.solve dm (+ k 2) eigenvectors eigenvalues false))]
	 {:eigenvectors eigenvectors
	  :eigenvalues eigenvalues
	  :U nil
	  :S (diag (map #(sqrt %) (reverse (take-last k eigenvalues))))
	  :V (trans 
	      (matrix (to-array (map (fn [vec] (map #(.get %1) 
						    (iterator-seq (.iterateAll (.vector vec)))))
				     (take k eigenvectors)))))}))

  (cluster-docs [self 
		 reader
		 doc-seq
		 svd-factorization 
		 k
		 num-clusters
		 id-field]
		(let [hadoop-conf (new org.apache.hadoop.conf.Configuration)
		      fs (org.apache.hadoop.fs.FileSystem/get hadoop-conf)
		      base-path (org.apache.hadoop.fs.Path. (str "/kmeans-clustering/" (java.lang.System/nanoTime)))
		      mkdirs-result (org.apache.hadoop.fs.FileSystem/mkdirs fs 
									    base-path
									    (org.apache.hadoop.fs.permission.FsPermission/getDefault))
		      U (:U svd-factorization)
		      S (:S svd-factorization) 
		      V (:V svd-factorization)
		      m (trans 
			 (mmult (sel S :cols (range 0 k) :rows (range 0 k))
				(trans (mmult (sel V :cols (range 0 k))))))
		      srm (doto (org.apache.mahout.math.SparseRowMatrix. (int-array (dim m)))
			    ((fn [sparse-row-matrix]
			       (doall 
				(for [i (range 0 (count m))
				      j (range 0 (count (sel m :rows 0)))]
				  (.setQuick sparse-row-matrix i j (sel m :rows i :cols j)))))))
		      reduced-m-path (str (.toString base-path) "/reducedm")
		      writer (lsa4solr.hadoop-utils/write-matrix hadoop-conf srm reduced-m-path)
		      initial-centroids (org.apache.mahout.clustering.kmeans.RandomSeedGenerator/buildRandom reduced-m-path
													     (str (.toString base-path) "/centroids") 
													     num-clusters)
		      cluster-output-path (str (.toString base-path) "/clusterout")
		      job (org.apache.mahout.clustering.kmeans.KMeansDriver/runJob
			   reduced-m-path
			   (.toString initial-centroids)
			   cluster-output-path
			   "org.apache.mahout.common.distance.CosineDistanceMeasure"
			   0.00000001 
			   k
			   num-clusters)
		      tkey (org.apache.hadoop.io.Text.)
		      tval (org.apache.hadoop.io.Text.)
		      groups (clojure.contrib.seq-utils/flatten
			      (map (fn [path-string] (let [path (org.apache.hadoop.fs.Path. path-string)
							   seq-reader (org.apache.hadoop.io.SequenceFile$Reader. fs path hadoop-conf) 
							   valseq (take-while (fn [v] (.next seq-reader tkey tval)) (repeat [tkey tval]))]  
						       (map #(.toString (second %)) valseq)))
				   (map #(str cluster-output-path "/points/part-0000" %) (range 0 8))))
		      clusters (apply merge-with #(into %1 %2)
				      (map #(hash-map (keyword (second %))
						      (list (lsa4solr.lucene-utils/get-docid reader "id" (nth doc-seq (first %1)))))
					   (indexed groups)))]
		  clusters))
		
  )

