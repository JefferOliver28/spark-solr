package com.lucidworks.spark.example.query

import com.lucidworks.spark.SparkApp.RDDProcessor
import com.lucidworks.spark.rdd.SolrRDD
import com.lucidworks.spark.util.SolrQuerySupport
import org.apache.commons.cli.{CommandLine, Option}
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.common.SolrDocument
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.immutable.HashMap

import com.lucidworks.spark.util.ConfigurationConstants._

/**
 * Example of an wordCount spark app to process tweets from a Solr collection
 */
class WordCount extends RDDProcessor{
  def getName: String = "word-count"

  def getOptions: Array[Option] = {
    Array(
    Option.builder()
          .argName("QUERY")
          .longOpt("query")
          .hasArg()
          .required(false)
          .desc("URL encoded Solr query to send to Solr")
          .build()
    )
  }

  def run(conf: SparkConf, cli: CommandLine): Int = {
    val zkHost = cli.getOptionValue("zkHost", "localhost:9983")
    val collection = cli.getOptionValue("collection", "collection1")
    val queryStr = cli.getOptionValue("query", "*:*")

    val sc = SparkContext.getOrCreate(conf)
    val solrRDD: SolrRDD = new SolrRDD(zkHost, collection, sc)
    val solrQuery: SolrQuery = SolrQuerySupport.toQuery(queryStr)
    val rdd: RDD[SolrDocument]  = solrRDD.queryShards(solrQuery).rdd

    val words: RDD[String] = rdd.map(doc => scala.Option.apply(doc.get("text_t")).getOrElse("").toString)
    val pWords: RDD[String] = words.flatMap(s => s.toLowerCase.replaceAll("[.,!?\n]", " ").trim().split(" "))

    val wordsCountPairs: RDD[(String, Int)] = pWords.map(s => (s, 1))
                                                    .reduceByKey((a,b) => a+b)
                                                    .map(item => item.swap)
                                                    .sortByKey(false)
                                                    .map(item => item.swap)

    wordsCountPairs.take(20).iterator.foreach(println)

    // Now use schema information in Solr to build a queryable SchemaRDD
    val sqlContext = new SQLContext(sc)

    // Pro Tip: SolrRDD will figure out the schema if you don't supply a list of field names in your query
    val options = HashMap[String, String](
      SOLR_ZK_HOST_PARAM -> zkHost,
      SOLR_COLLECTION_PARAM -> collection,
      SOLR_QUERY_PARAM -> queryStr
      )

    val df: DataFrame = sqlContext.read.format("solr").options(options).load()
    val numEchos = df.filter(df.col("type_s").equalTo("echo")).count()
    println("numEchos >> " + numEchos)

    sc.stop()
    0
  }
}

