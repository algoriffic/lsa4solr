(ns lsa4solr.lucene-utils)

(defn extract-frequency-vectors
  [reader init-frequency-vector get-mapper field terms hits]
  (pmap #(let [m (init-frequency-vector (count terms))
	       mapper (get-mapper terms m (count hits))]
	   (do (. reader getTermFreqVector (int %1) field mapper)
	       @m)) 
	hits))