# Lucene Indexes

What does a cloudant developer need to know about how Lucene creates indexes?

## General Lucene Notes

### Overview

* http://www.ibm.com/developerworks/java/library/j-solr-lucene/index.html

### Indexing

A few general purpose links:
* http://blog.mikemccandless.com/2011/02/visualizing-lucenes-segment-merges.html
    * Nice overview and video demonstrating how segment indexes are merged
    * discusses various merge strategies
    * double cute - [python script](https://code.google.com/a/apache-extras.org/p/luceneutil/source/browse/mergeViz.py) to do segment index merges 
* Overview of why [Lucene Indexing is Fast](http://blog.mikemccandless.com/2010/09/lucenes-indexing-is-fast.html)
