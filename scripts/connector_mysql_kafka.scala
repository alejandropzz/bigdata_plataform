import java.util.Properties
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.github.shyiko.mysql.binlog.event._
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer

import scala.collection.mutable

val kafkaProps = new Properties()
kafkaProps.put("bootstrap.servers", "broker-kafka:29092")
kafkaProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
kafkaProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

val kafkaTopic = "mysql.binlog.events"
val producer = new KafkaProducer[String, String](kafkaProps)

val binlogClient = new BinaryLogClient("mysql", 3306, "cdc_user", "amlh26c7")
binlogClient.setEventDeserializer(new EventDeserializer())

val tableMap = mutable.Map[java.lang.Long, (String, String)]()

binlogClient.registerEventListener { event =>
  try {
    val data: Any = event.getData

    data match {
      case _: RotateEventData =>
        println("Evento Rotate ignorado")

      case _: FormatDescriptionEventData =>
        println("Evento FormatDescription ignorado")

      case d: TableMapEventData =>
        val tableId = d.getTableId
        val dbName = d.getDatabase
        val tableName = d.getTable
        tableMap(tableId) = (dbName, tableName)

      case d: WriteRowsEventData =>
        val tableId = d.getTableId
        val (db, table) = tableMap.getOrElse(tableId, ("unknown_db", "unknown_table"))
        d.getRows.forEach { row =>
          val values = row.toArray.map(_.toString).mkString("[", ",", "]")
          val json = s"""{"event":"INSERT","database":"$db","table":"$table","values":$values}"""
          println(json)
          producer.send(new ProducerRecord[String, String](kafkaTopic, "default_key", json))
        }

      case d: UpdateRowsEventData =>
        val tableId = d.getTableId
        val (db, table) = tableMap.getOrElse(tableId, ("unknown_db", "unknown_table"))
        d.getRows.forEach { pair =>
          val oldRow = pair.getKey.toArray.map(_.toString).mkString("[", ",", "]")
          val newRow = pair.getValue.toArray.map(_.toString).mkString("[", ",", "]")
          val json = s"""{"event":"UPDATE","database":"$db","table":"$table","before":$oldRow,"after":$newRow}"""
          println(json)
          producer.send(new ProducerRecord[String, String](kafkaTopic, "default_key", json))
        }

      case d: DeleteRowsEventData =>
        val tableId = d.getTableId
        val (db, table) = tableMap.getOrElse(tableId, ("unknown_db", "unknown_table"))
        d.getRows.forEach { row =>
          val values = row.toArray.map(_.toString).mkString("[", ",", "]")
          val json = s"""{"event":"DELETE","database":"$db","table":"$table","values":$values}"""
          println(json)
          producer.send(new ProducerRecord[String, String](kafkaTopic, "default_key", json))
        }

      case other =>
        println(s"Evento no manejado: ${other.getClass.getName}")
    }
  } catch {
    case e: Exception =>
      println(s"Error procesando evento: ${e.getClass.getName} - ${e.getMessage}")
  }
}

binlogClient.connect()

sys.addShutdownHook {
  producer.close()
  binlogClient.disconnect()
}
