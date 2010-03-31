(defproject lsa4solr "1.0.0-SNAPSHOT"
  :description "Clustering component for Solr based on Latent Semantic Analysis"
  :namespaces :all
  :repositories {"incanter" "http://repo.incanter.org"
		 "apache" "https://repository.apache.org/"}
  :dependencies [[org.clojure/clojure "1.2.0-master-SNAPSHOT"]
                 [org.clojure/clojure-contrib "1.2.0-master-SNAPSHOT"]
		 [org.incanter/incanter-full "1.0.0"]]
  :dev-dependencies [[leiningen/lein-swank "1.1.0"]
		     [org.apache.solr/solr-core "3.1-SNAPSHOT" :exclusions [org.apache.lucene/lucene-snowball]]
		     [org.apache.solr/solr-clustering "3.1-SNAPSHOT" :exclusions [org.carrot2/carrot2-mini]]])
