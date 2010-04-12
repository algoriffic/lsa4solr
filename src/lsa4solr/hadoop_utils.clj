(ns lsa4solr.hadoop-utils)

(defn write-vectors [writer
		     m]
  (doall (map #(.append writer %1 (new org.apache.mahout.math.VectorWritable (.vector %2))) 
	      (map #(new org.apache.hadoop.io.IntWritable %) 
		   (range 0 (.numRows m))) 
	      (iterator-seq (.iterator m)))))

(defn write-matrix [hadoop-conf m path-string]
  (let [fs (org.apache.hadoop.fs.FileSystem/get hadoop-conf)
	path (new org.apache.hadoop.fs.Path path-string)]
    (doto (new org.apache.hadoop.io.SequenceFile$Writer 
	       fs
	       hadoop-conf
	       path
	       org.apache.hadoop.io.IntWritable
	       org.apache.mahout.math.VectorWritable)
      (write-vectors m)
      (.close))))