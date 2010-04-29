(ns lsa4solr.hierarchical-clustering
  (:use [lsa4solr mahout-matrix dendrogram])
  (:require [clojure [zip :as z]])
  (:require [clojure.contrib 
	     [combinatorics :as combine]
	     [zip-filter :as zf]
	     [seq-utils :as seq-utils]])
  (:import (org.apache.mahout.math SparseMatrix 
				   RandomAccessSparseVector
				   VectorWritable
				   Matrix
				   DenseMatrix)
	   (org.apache.mahout.math.hadoop DistributedRowMatrix)))

(defn get-count
  [cluster]
  (:count (meta cluster)))

(defn get-centroid
  [cluster]
  (:centroid (meta cluster)))

(defn merge-centroids
  [c1 c2]
  (add (mult (get-centroid c1) (double (/ 1 (get-count c1))))
       (mult (get-centroid c2) (double (/ 1 (get-count c2))))))

(defn get-vecs
  [mat idxs]
  (map #(.getRow mat %) idxs))

(defn average-dispersion
  [mat group centroid dist]
  (/ (reduce + (map #(dist centroid %) (get-vecs mat group)))
     (count group)))

(defn average-intercluster-dispersion
  [mat clusters dist]
  (let [centroids (map #(apply centroid (get-vecs mat %)) clusters)
	combos (combine/combinations centroids 2)]
    (/ (reduce + (map #(apply dist %) combos))
       (count combos))))

(defn hclust
  "Hierarchical clustering of the rows of mat.  Returns a dendrogram
   and a merge sequence.  The dendrogram is a tree with doc ids as 
   leaf nodes and meta data in the branch nodes indicating the number
   of children and the centroid of the branch."
  [mat]
  (let [dend (dendrogram (map #(with-meta {:id %} 
				      (hash-map 
				       :centroid (.getRow mat %) 
				       :count 1))
				   (range 0 (.numRows mat))))
	get-distance (memoize euclidean-distance)]
    (take 
     (- (.numRows mat) 1)
     (iterate (fn [[dend merge-sequence]]
		(let [clusters (z/children dend)
		      dists (map #(list % (get-distance (get-centroid (nth clusters (first %)))
							(get-centroid (nth clusters (second %)))))
				 (combine/combinations (range 0 (count clusters)) 2))
		      closest-pair (first (reduce #(if (< (second %1) (second %2)) %1 %2)
						  (first dists)
						  (rest dists)))]
		  (list (merge-nodes dend 
				     closest-pair
				     (fn [n1 n2] 
				       (with-meta (list (with-meta n1 (meta n1)) (with-meta n2 (meta n2)))
					 (hash-map :count (apply + (map get-count [n1 n2]))
						   :centroid (merge-centroids n1 n2)))))
			(conj merge-sequence closest-pair))))
	      (list dend '())))))