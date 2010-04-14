(ns lsa4solr.mahout-matrix
  (:import (org.apache.mahout.math SparseMatrix RandomAccessSparseVector VectorWritable Matrix DenseMatrix)
	   (org.apache.mahout.math.hadoop DistributedRowMatrix)
	   (org.apache.mahout.math.hadoop.decomposer DistributedLanczosSolver)
	   (org.apache.mahout.math.function UnaryFunction)
	   (org.apache.hadoop.fs Path FileSystem)
	   (org.apache.hadoop.fs.permission FsPermission)
	   (org.apache.hadoop.conf Configuration)
	   (org.apache.hadoop.mapred JobConf)
	   (org.apache.hadoop.io IntWritable SequenceFile$Writer)))

(defn create-vector 
  [data]
  (cond
   (coll? data) (doto (RandomAccessSparseVector. (count data))
		  ((fn [vec] (map #(.setQuick vec %1 %2) 
				  (range 0 (count data)) 
				  data))))
   (integer? data) (doto (RandomAccessSparseVector. data))))
   
   

(defn set-value
  ([#^RandomAccessSparseVector vector index value] (.setQuick vector index value)))

(defn matrix
  [vec-iterator]
  (let [hadoop-conf (Configuration.)
	fs (FileSystem/get hadoop-conf)
	base-path (Path. (str "/lsa4solr/matrix/" (java.lang.System/nanoTime)))
	mkdirs-result (FileSystem/mkdirs fs 
					 base-path
					 (FsPermission/getDefault))
	m-path (str (.toString base-path) "/m")
	tmp-path (str (.toString base-path) "/tmp")
	nrows (count vec-iterator)
	ncols (.size (first vec-iterator))
	writer (doto (SequenceFile$Writer. fs
					   hadoop-conf
					   (Path. m-path)
					   IntWritable
					   VectorWritable)
		 ((fn [wrt]
		    (doall 
		     (map #(.append wrt 
				    (IntWritable. %1)
				    (VectorWritable. %2))
		    (range 0 nrows)
		    vec-iterator))))
		 (.close))]
    (doto
	(DistributedRowMatrix. m-path
			       tmp-path
			       nrows
			       ncols)
			       (.configure (JobConf. hadoop-conf)))))

(defmulti mmult (fn [A & B] (type A)))

(defmethod mmult DistributedRowMatrix [A B]
  (let [num-rows (.numRows A)
	num-cols (second (int-array (.size B)))]
    (doto (DenseMatrix. num-rows num-cols)
      ((fn [m] (doall (pmap #(.assignColumn m % (.times A (.getColumn B %))) 
			    (range 0 num-cols))))))))
(defmethod mmult :default [A B]
     (.times A B))

(defn diag
  [vals]
  (doto (SparseMatrix. (int-array [(count vals) (count vals)]))
    ((fn [m] (doall (map #(.setQuick m %1 %2 %3) 
			 (range 0 (count vals))
			 (range 0 (count vals))
			 vals))))))

(defn invert-diagonal
  [mat]
  (.assign mat
	   (proxy [UnaryFunction]
	       []
	     (apply [arg1] (if (= arg1 0) 0 (/ 1 arg1))))))


(defn transpose
  [mat]
  (.transpose mat))

(defn normalize-matrix-columns
  [mat]
  (let [num-rows (.numRows mat)
	num-cols (.numCols mat)]
    (doto (DenseMatrix. num-rows num-cols)
      ((fn [m] (doall (pmap #(.assignColumn m % (.normalize (.getColumn mat %))) 
			    (range 0 num-cols))))))))

(defn decompose-svd
  [mat k]
  (let [eigenvalues (new java.util.ArrayList)
	eigenvectors (DenseMatrix. (+ k 2) (.numCols mat))
	decomposer (doto (DistributedLanczosSolver.)
			  (.solve mat (+ k 2) eigenvectors eigenvalues false))
	V (normalize-matrix-columns (.viewPart (.transpose eigenvectors) 
					       (int-array [0 0]) 
					       (int-array [(.numCols mat) k])))
	U (mmult mat V)
	S (diag (take k (reverse eigenvalues)))]
    {:U U
     :S S
     :V V}))
     


