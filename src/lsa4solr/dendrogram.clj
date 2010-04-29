(ns lsa4solr.dendrogram
  (:require [clojure [zip :as z]])
  (:require [clojure.contrib 
	     [combinatorics :as combine]
	     [zip-filter :as zf]
	     [seq-utils :as seq-utils]]))

(defn move-right
  "Moves right n steps from location"
  [loc n]
  (nth (iterate #(z/right %) loc) n))

(defn remove-nodes
  "Removes multiple nodes in a single pass"
  [loc & n]
  (let [sorted-indexes (sort n)
	increments (range 0 (count sorted-indexes))
	incremental-indexes (map #(- %1 %2) sorted-indexes increments)]
    (reduce #(z/seq-zip (z/root (z/remove (move-right (z/down %1) %2))))
	    loc
	    incremental-indexes)))

(defn merge-nodes 
  "Merges two nodes by calling new-node to create the new node.
   Pulls all other nodes up one level to maintain dendrogram."
  [root node-indexes new-node] 
  (let [n1 (z/node (move-right (z/down root) (first node-indexes)))
	n2 (z/node (move-right (z/down root) (second node-indexes)))
	new-tree (z/seq-zip 
		  (map #(with-meta
			  (list (with-meta (z/node %) (meta (z/node %))))
			  (meta (z/node %)))
		       (zf/children (apply remove-nodes root node-indexes))))]
    (z/seq-zip (z/root (z/insert-child 
			new-tree
			(new-node n1 n2))))))

(defn bfs-depth-seq [branch? children root height]
  "Walks a tree to a certain depth and returns a lazy sequence of all nodes at specified depth"
  (let [walk (fn walk [queue]
               (when-let [node (peek queue)]
                 (lazy-seq
		  (cond 
		   (< (:depth node) height) (walk
					     (into (pop queue)
						   (when (branch? (:node node))
						     (map #(hash-map :node % :depth (inc (:depth node)))
							  (children (:node node))))))
		   :default (cons (:node node) 
				  (walk (pop queue)))))))]
    (walk (conj clojure.lang.PersistentQueue/EMPTY (hash-map :node root :depth 0)))))


(defn dendrogram-to-map
  [node emit-branch-node emit-leaf-node]
  (cond (z/branch? node) (emit-branch-node (z/node node)
					   (map #(dendrogram-to-map % emit-branch-node emit-leaf-node)
						(zf/children node)))
	:default (emit-leaf-node (z/node node))))

(defn cut
  "Cuts dendrogram at depth.  Returns flattened descendants of groups at depth."
  [root depth]
  (map (fn [node] 
	 (map #(:id (z/node %))
	      (filter #(not (z/branch? %))
		      (zf/descendants node))))
       (bfs-depth-seq z/branch? zf/children root depth)))

(defn dendrogram
  "Constructs a new dendrogram from a sequence of elements"
  [els]
  (z/seq-zip els))