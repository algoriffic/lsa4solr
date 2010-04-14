(ns lsa4solr.hadoop-utils
  (:import (org.apache.mahout.math VectorWritable)
	   (org.apache.hadoop.io IntWritable)
	   (org.apache.hadoop.fs FileSystem Path)
	   (org.apache.hadoop.io SequenceFile$Writer)))

(defn write-vectors [writer
		     m]
  (doall (map #(.append writer %1 (VectorWritable. (.vector %2))) 
	      (map #(IntWritable. %) 
		   (range 0 (.numRows m))) 
	      (iterator-seq (.iterator m)))))

(defn write-matrix [hadoop-conf m path-string]
  (let [fs (FileSystem/get hadoop-conf)
	path (Path. path-string)]
    (doto (SequenceFile$Writer. fs
				hadoop-conf
				path
				IntWritable
				VectorWritable)
      (write-vectors m)
      (.close))))