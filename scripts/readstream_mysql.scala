import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.github.shyiko.mysql.binlog.event._
import java.util.concurrent.LinkedBlockingQueue

// 1. Configuración inicial
val spark = SparkSession.builder().appName("MySQLBinlogProcessor").getOrCreate()
import spark.implicits._

// 2. Cola para transferir eventos entre hilos
val eventQueue = new LinkedBlockingQueue[(String, Long, String)](1000)

// 3. Iniciar cliente Binlog en hilo separado
val binlogClient = new BinaryLogClient("mysql-container", 3306, "cdc_user", "amlh26c7")

new Thread {
  override def run(): Unit = {
    binlogClient.registerEventListener { event =>
      event.getData match {
        case d: WriteRowsEventData =>
          eventQueue.put(("INSERT", d.getTableId, d.getRows.toString))
        case d: UpdateRowsEventData =>
          eventQueue.put(("UPDATE", d.getTableId, d.getRows.toString))
        case d: DeleteRowsEventData =>
          eventQueue.put(("DELETE", d.getTableId, d.getRows.toString))
        case _ => // Ignorar otros eventos
      }
    }
    binlogClient.connect()
  }
}.start()

// 4. Crear DataFrame streaming desde la cola
val binlogStream = spark.readStream
  .format("rate")
  .option("rowsPerSecond", 1) // Solo para activar el stream
  .load()
  .map(_ => {
    if (!eventQueue.isEmpty) {
      val (eventType, tableId, rows) = eventQueue.take()
      (eventType, tableId, rows, new java.sql.Timestamp(System.currentTimeMillis()))
    } else {
      ("NO_EVENT", 0L, "[]", new java.sql.Timestamp(System.currentTimeMillis()))
    }
  })
  .toDF("event_type", "table_id", "rows", "event_time")
  .filter($"event_type" =!= "NO_EVENT")

// 5. Procesar y mostrar resultados
val query = binlogStream.writeStream
  .outputMode("append")
  .format("console")
  .option("truncate", false)
  .start()

// 6. Manejo de shutdown
sys.addShutdownHook {
  binlogClient.disconnect()
  spark.stop()
}

query.awaitTermination()