(ns lsa4solr.hierarchical-clustering
  (:use [lsa4solr mahout-matrix])
  (:require [clojure.contrib [combinatorics :as combine] [seq-utils :as seq-utils]])
  (:import (org.apache.mahout.math SparseMatrix RandomAccessSparseVector VectorWritable Matrix DenseMatrix)
	   (org.apache.mahout.math.hadoop DistributedRowMatrix)))

(defn get-vecs
  [mat idxs]
  (map #(.getRow mat %) idxs))

(defn drop-nth [coll & idxs]
  (cond
   (> (count idxs) 1) (concat (take (dec (first idxs)) coll)
			      (apply drop-nth (drop (first idxs) coll)
				     (map #(- % (first idxs)) (rest idxs))))
   :default (concat (take (dec (first idxs)) coll)
		    (drop (first idxs) coll))))

(defn new-grouping
  [groups to-combine]
  (conj (apply drop-nth groups (map inc to-combine))
	(seq-utils/flatten (apply conj (map #(nth groups %) to-combine)))))

(defn hclust
  [mat]
  (let [groups (map list (range 0 (.numRows mat)))
	cosine-dist (org.apache.mahout.common.distance.CosineDistanceMeasure.)
	centroids (ref {})
	distance-map (ref {})
	dist-fn (fn [g1 g2] 
		  (let [get-or-update (fn [g h]
					(cond
					 (not (contains? @centroids h1))
					 (dosync 
					  (let [c (apply centroid (apply get-vecs mat g)]
					    (alter centroids assoc h c)
					    c)))))
			h1 (hash g1)
			h2 (hash g2)
			c1 (get-or-update g1 h1)
			c2 (get-or-update g2 h2)]
		    (.distance cosine-dist v1 v2)))
	get-distance (fn [g1 g2]
		       (let [h (hash (list g1 g2))]
			 (cond 
			  (contains? @distance-map h) (val (find @distance-map h))
			  :default (let [d (dist-fn g1 g2)]
				     (dosync
				      (alter distance-map assoc h d)
				      d)))))]
    (take (.numRows mat) 
	  (iterate (fn [groups] 
		     (let [dists (doall (pmap #(list % (apply get-distance (map second %)))
					      (combine/combinations (seq-utils/indexed groups) 2)))
			   closest-pair (map first
					     (first (reduce (fn [l r]
							      (cond
							       (< (second l) (second r)) l
							       :default r))
							    dists)))]
		     (new-grouping groups (seq-utils/flatten closest-pair)))) 
		   groups))))