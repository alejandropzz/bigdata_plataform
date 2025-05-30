import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._


object KafkaConsumer {
  def main(args: Array[String]): Unit = {
    
    val df = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "broker-kafka:29092")
      .option("subscribe", "cdc.public.transactions")
      .option("startingOffsets", "latest")
      .load()


    val transactionPayloadSchema = new StructType()
      .add("before", new StructType()
        .add("transaction_id", StringType)
        .add("user_id", StringType)
        .add("timestamp", LongType)
        .add("amount", StringType)
        .add("city", StringType)
        .add("country", StringType)
        .add("merchant_name", StringType)
        .add("payment_method", StringType)
        .add("affiliateid", StringType)
      )
      .add("after", new StructType()
        .add("transaction_id", StringType)
        .add("user_id", StringType)
        .add("timestamp", LongType)
        .add("amount", StringType)
        .add("city", StringType)
        .add("country", StringType)
        .add("merchant_name", StringType)
        .add("payment_method", StringType)
        .add("affiliateid", StringType)
      )
      .add("op", StringType)
      .add("ts_ms", LongType)

    val envelopeSchema = new StructType()
      .add("payload", transactionPayloadSchema)


    val parsedDF = df
      .selectExpr("CAST(value AS STRING) as message")
      .withColumn("jsonData", from_json(col("message"), envelopeSchema))
      .select("jsonData.payload.*")

    val dfWithPartitioning = parsedDF
      .withColumn("year", year(from_unixtime(col("ts_ms") / 1000)))
      .withColumn("month", month(from_unixtime(col("ts_ms") / 1000)))
      .withColumn("day", dayofmonth(from_unixtime(col("ts_ms") / 1000)))      

    dfWithPartitioning.writeStream
      .format("parquet")  
      .option("checkpointLocation", "hdfs://namenode:9000/user/bigdata_plataform/cdc/transactions/_checkpoint")
      .option("path", "hdfs://namenode:9000/user/bigdata_plataform/cdc/transactions")
      .partitionBy("year", "month", "day")
      .start()
      .awaitTermination()

/*
    dfWithPartitioning.writeStream
      .format("console")
      .option("truncate", false)
      .start()
      .awaitTermination()
*/
  
  }
}

KafkaConsumer.main(Array())


