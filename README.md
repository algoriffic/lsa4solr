lsa4solr
========

A clustering engine for Solr based on Latent Semantic Analysis.  The engine
constructs a term frequency matrix which it stores in memory.  When requests for
clustering documents are made, the term document matrix is constructed for the
documents in the query result and decomposed using Singular Value Decomposition.
The document vectors are then reconstructed based on a reduced rank parameter to
get rid of noise.  These reconstructed document vectors are clustered by comparing
the cosine similarity distance of each individual document to the first n principal
components.

This first version requires that the number of clusters and the reduced rank be
supplied by the user.  Decomposition is performed using the DistributedLanczosSolver 
from Apache Mahout on a Hadoop cluster.  After decomposition of the term-document
matrix, the reduced rank document vectors are clusters using k-means clustering also
from Apache Mahout.

Development goals include determining the optimal number of clusters, optimizing
the reduced rank, etc.

Building
--------

lsa4solr depends on the 3.1 development version of Solr and the
1.2 development version of Clojure.  In order to build lsa4solr,
you will need to build the appropriate versions of Solr and Clojure,
generate the maven artifacts, and install them in your local
maven repository.  Then

  lein deps
  lein jar

Installing
----------

Due to some Clojure classloader requirements, you will need to install the 
lsa4solr jar and its dependencies into the Solr webapp/WEB-INF/lib directory
rather than using the solrconfig.xml file to configure the path to the
lsa4solr dependencies.  The dependencies that need to be in the System
classloader include:

    arpack-combo-0.1.jar
    clojure-1.2.0.jar
    clojure-contrib-1.2.0-master-20100122.191106-1.jar
    apache-solr-clustering-3.1-dev.jar
    parallelcolt-0.7.2.jar
    lsa4solr.jar
    netlib-java-0.9.1.jar
    hadoop-core-0.20.2.jar
    mahout-collections-0.4-SNAPSHOT.jar
    mahout-core-0.4-SNAPSHOT.jar
    mahout-math-0.4-SNAPSHOT.jar
  
Configuring Solr
----------------

Add the following to your solrconfig.xml

    <searchComponent
      name="lsa4solr"
      enable="${solr.clustering.enabled:false}"
      class="org.apache.solr.handler.clustering.ClusteringComponent" >
      <lst name="engine">
        <str name="classname">lsa4solr.cluster.LSAClusteringEngine</str>
        <str name="name">lsa4solr</str>
        <str name="narrative-field">Summary</str>
        <str name="id-field">Summary</str>
      </lst>
    </searchComponent>
     <requestHandler name="/lsa4solr"
                    enable="${solr.clustering.enabled:false}"
                    class="solr.SearchHandler">
       <lst name="defaults">
         <bool name="clustering">true</bool>
         <str name="clustering.engine">lsa4solr</str>
         <bool name="clustering.results">true</bool>
      </lst>     
      <arr name="last-components">
        <str>lsa4solr</str>
      </arr>
    </requestHandler>
  
Configure the narrative-field parameter to be the text field of the
schema you are working with and the id-field parameter to be the unique
field that will be returned.

You will need to tweak the Solr filters on the narrative field in order
to get the best results.  I have been using the following set of filters
to get decent results:

    <fieldType name="text" class="solr.TextField" positionIncrementGap="100">
      <analyzer type="index">
        <tokenizer class="solr.WhitespaceTokenizerFactory"/>
        <filter class="solr.StopFilterFactory" ignoreCase="true" words="stopwords.txt" />
        <filter class="solr.WordDelimiterFilterFactory" 
		generateWordParts="0"
		generateNumberParts="0"
		catenateWords="1"
		catenateNumbers="1"
		catenateAll="0"/>
         <filter class="solr.LowerCaseFilterFactory"/>
         <filter class="solr.SnowballPorterFilterFactory" language="English" protected="protwords.txt"/>
         <filter class="solr.RemoveDuplicatesTokenFilterFactory"/>
      </analyzer>
   </fieldType>


Hadoop Setup
-----------------

In order to use lsa4solr with Hadoop, make sure that the mahout-math-0.4.jar is
in the Hadoop lib directory.  This is a dependency of the mahout-core-0.4.jar which
contains the distributed job.  Put the core-site.xml and mapred-site.xml files from
the resources directory into Solr's webapp/WEB-INF/classes directory and configure
them to point to your Hadoop setup.


Using
-----

Start Solr with the -Dsolr.clustering.enabled=true option.  Once the server
has started, cluster your documents using an URL like

    http://localhost:8983/solr/lsa4solr?nclusters=2&q=Summary:.*&rows=100&k=10

where

    k        - the rank of the reduced SVD matrix
    ncluster - the number of clusters to group the documents into
    q        - the standard Solr query parameter
    rows     - the standard Solr rows parameter
  
The cluster information will be at the bottom of the response.

Testing
-------

On the Downloads page, there is a Usenet dataset which can be found [here](http://people.csail.mit.edu/jrennie/20Newsgroups/)
Import some documents from two or more of the newsgroups into your Solr instance and access the lsa4solr URL.

You can also use the cluster algorithm directly from the REPL

    lein swank

    user> (in-ns 'lsa4solr.cluster)
    #<Namespace lsa4solr.cluster>
    lsa4solr.cluster> (def reader (org.apache.lucene.index.IndexReader/open (org.apache.lucene.store.FSDirectory/open (new java.io.File "/path/to/solr/data/index"))))
    #'lsa4solr.cluster/reader
    lsa4solr.cluster> (def initial-terms (init-term-freq-doc reader "Summary"))
    #'lsa4solr.cluster/initial-terms
    lsa4solr.cluster> (def searcher (new org.apache.lucene.search.IndexSearcher reader))
    #'lsa4solr.cluster/searcher
    lsa4solr.cluster> (def queryparser 
         (new org.apache.lucene.queryParser.QueryParser 
    	  (org.apache.lucene.util.Version/LUCENE_30)
    	  "Summary"
    	  (new org.apache.lucene.analysis.SimpleAnalyzer)))
    #'lsa4solr.cluster/queryparser
    lsa4solr.cluster> (def result (. searcher search (. queryparser parse "Summary:br*") (. reader maxDoc)))
    #'lsa4solr.cluster/result
    lsa4solr.cluster> (def docids (map #(. %1 doc) (. result scoreDocs)))
    #'lsa4solr.cluster/docids
    lsa4solr.cluster> (def docslice (new org.apache.solr.search.DocSlice 0 (count docids) (int-array docids) (float-array (repeat (count docids) 1)) (count docids) 1))
    #'lsa4solr.cluster/docslice
    lsa4solr.cluster> (def clst (cluster (LocalLSAClusteringEngine) reader "Summary" "id" initial-terms docslice 50 2))
