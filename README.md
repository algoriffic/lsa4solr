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
supplied by the user.  Also, matrix algebra is performed in memory therefore only
small document sets will work.  Development goals include determining the optimal
number of clusters, interfacing with Apache Mahout matrix algebra packages, optimizing
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
    incanter-full-1.0.0.jar
    apache-solr-clustering-3.1-dev.jar
    parallelcolt-0.7.2.jar
    lsa4solr.jar
    netlib-java-0.9.1.jar
  
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
schema you are working with.

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