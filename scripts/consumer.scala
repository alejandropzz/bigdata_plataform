// 1. Objeto principal (US-ASCII ONLY)
object KafkaConsumer {
  def main(args: Array[String]): Unit = {
    // 2. Configuracion directa
    val df = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", "cdc")
      .option("startingOffsets", "earliest")
      .load()

    // 3. Procesamiento 
    df.selectExpr("CAST(value AS STRING) as message")
      .writeStream
      .format("console")
      .start()
      .awaitTermination()
  }
}

// 4. Llamada  (requerida en spark-shell)
KafkaConsumer.main(Array())