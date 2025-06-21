// Java standard libraries
import java.sql.{Connection, Timestamp}
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.{Properties, Timer, TimerTask}
import java.security.MessageDigest

// Scala utilities
import scala.jdk.CollectionConverters._

// External libraries
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import com.typesafe.config.{Config, ConfigFactory}
import io.github.cdimascio.dotenv.Dotenv
import play.api.libs.json._

// Kafka clients
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}



def hash(input: String): String = {
  val md = MessageDigest.getInstance("SHA-256")
  val bytes = md.digest(input.getBytes("UTF-8"))
  bytes.map("%02x".format(_)).mkString
}


object EnvPostgresLoader {
  private val dotenv = Dotenv.configure()
    .filename(".env.postgres")
    .load()

  def getOrFail(key: String): String = Option(dotenv.get(key))
    .getOrElse(throw new RuntimeException(s"Variable $key not found in .env.postgres"))

  val host: String = getOrFail("PGHOST")
  val port: String = getOrFail("PGPORT")
  val user: String = getOrFail("PGUSER")
  val password: String = getOrFail("PGPASSWORD")
  val database: String = getOrFail("PGDATABASE")
}


object EnvKafkaLoader {
  private val dotenv = Dotenv.configure()
    .filename(".env.kafka")
    .load()

  def getOrFail(key: String): String = Option(dotenv.get(key))
    .getOrElse(throw new RuntimeException(s"Variable $key not found in .env.kafka"))

  val bootstrapServers: String = getOrFail("KAFKA_BOOTSTRAP_SERVERS")
  val keySerializer: String = getOrFail("KAFKA_KEY_SERIALIZER")
  val valueSerializer: String = getOrFail("KAFKA_VALUE_SERIALIZER")
}


object AppConfig {
  private val props = new java.util.Properties()
  props.load(new java.io.FileInputStream(".conf"))

  
  val intervalSeconds: Int = Option(props.getProperty("INTERVAL")).map(_.toInt).getOrElse(5)
  val poolSize: Int = Option(props.getProperty("POOLSIZE")).map(_.toInt).getOrElse(5)
  val kfkTopic: String = Option(props.getProperty("KFK_TOPIC_SUFFIX")).getOrElse("kfk_stream_")
  val dataBaseType: String = Option(props.getProperty("BD_TYPE")).getOrElse("POSTGRESQL")
  val pkColumn: String = Option(props.getProperty("PK_COLUMN")).getOrElse("audit_log_id")
  val databaseNameColumn: String = Option(props.getProperty("DATABASE_NAME_COLUMN")).getOrElse("db_name")
  val tableNameColumn: String = Option(props.getProperty("TABLE_NAME_COLUMN")).getOrElse("table_name")
  val recordIdColumn: String = Option(props.getProperty("RECORD_ID_COLUMN")).getOrElse("record_id")
  val newDataColumn: String = Option(props.getProperty("NEW_DATA_COLUMN")).getOrElse("new_data")
  val operationColumn: String = Option(props.getProperty("OPERATION_COLUMN")).getOrElse("operation")
  val timestampColumn: String = Option(props.getProperty("TIMESTAMP_COLUMN")).getOrElse("timestamp")
  val controlColumn: String = Option(props.getProperty("CONTROL_COLUMN")).getOrElse("is_in_kafka")
}


object DatabaseService {
  Class.forName("org.postgresql.Driver")

  private val config = new HikariConfig()

  config.setJdbcUrl(s"jdbc:postgresql://${EnvPostgresLoader.host}:${EnvPostgresLoader.port}/${EnvPostgresLoader.database}")
  config.setUsername(EnvPostgresLoader.user)
  config.setPassword(EnvPostgresLoader.password)
  config.setMaximumPoolSize(AppConfig.poolSize)

  private val dataSource = new HikariDataSource(config)
  def getConnection: Connection = dataSource.getConnection
}


object KafkaProducerService {
  private val props = new Properties()
  props.put("bootstrap.servers", EnvKafkaLoader.bootstrapServers)
  props.put("key.serializer", EnvKafkaLoader.keySerializer)
  props.put("value.serializer", EnvKafkaLoader.valueSerializer)

  private val producer = new KafkaProducer[String, String](props)

  def send(topic: String, key: String, value: String)
          (callback: (RecordMetadata, Exception) => Unit): Unit = {
    val record = new ProducerRecord[String, String](topic, key, value)
    producer.send(record, new Callback {
      override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
        callback(metadata, exception)
      }
    })
  }

  def close(): Unit = {
    producer.close()
  }
}

def ensureTopicExists(topicName: String): Unit = {
  val adminProps = new Properties()
  adminProps.put("bootstrap.servers", EnvKafkaLoader.bootstrapServers)

  val adminClient = AdminClient.create(adminProps)

  val existingTopics = adminClient.listTopics().names().get()
  if (!existingTopics.contains(topicName)) {
    println(s"Creating topic $topicName because it does not exist.")
    val newTopic = new NewTopic(topicName, 1, 1.toShort)
    adminClient.createTopics(List(newTopic).asJava).all().get()
  }

  adminClient.close()
}


object ChangePublisher {
  private val timer = new Timer()

  def run(tableName: String): Unit = {
    val task = new TimerTask {
      override def run(): Unit = {
        var conn: Connection = null
        try {
          conn = DatabaseService.getConnection
          val stmt = conn.createStatement()
          val auditTableName=s"audit_log_${tableName}_stream"
          val topicName=AppConfig.kfkTopic+tableName            

          val rs = stmt.executeQuery(s"SELECT * FROM $auditTableName WHERE ${AppConfig.controlColumn} = false")

          while (rs.next()) {
            
            val pkValue = rs.getString(AppConfig.pkColumn)
            val recordId = rs.getString(AppConfig.recordIdColumn)
            val timestamp: Timestamp = rs.getTimestamp(AppConfig.timestampColumn)
            val timestampEpoch: Long = timestamp.getTime / 1000
            val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
            val formattedDate: String = dateFormat.format(timestamp)

            val dataMap = Map(
              "db_type" -> JsString(AppConfig.dataBaseType),
              "db_name" -> JsString(EnvPostgresLoader.database),
              "table_name" -> JsString(tableName),
              "record_id" -> JsString(rs.getString(AppConfig.recordIdColumn)),
              "new_data" -> JsString(rs.getString(AppConfig.newDataColumn)),
              "hash_id" -> JsString(hash(AppConfig.dataBaseType + EnvPostgresLoader.database + tableName + rs.getString(AppConfig.recordIdColumn))),
              "hash_pk" -> JsString(hash(AppConfig.dataBaseType + EnvPostgresLoader.database + tableName + rs.getString(AppConfig.recordIdColumn) + timestampEpoch)),
              "hash_record" -> JsString(hash(rs.getString(AppConfig.newDataColumn))),
              "operation" -> JsString(rs.getString(AppConfig.operationColumn)),
              "timestamp_epoch" -> JsNumber(timestampEpoch),
              "data" -> JsString(formattedDate)
            )
                      
            val dataJson = Json.stringify(Json.toJson(dataMap))
            
            KafkaProducerService.send(topicName, recordId, dataJson) { (metadata, exception) =>
              if (exception == null) {
                var updateConn: Connection = null
                try {
                  updateConn = DatabaseService.getConnection
                  val updateStmt = updateConn.prepareStatement(
                    s"UPDATE $auditTableName SET ${AppConfig.controlColumn} = true WHERE ${AppConfig.pkColumn} = ?::uuid"
                  )
                  updateStmt.setString(1, pkValue)
                  updateStmt.executeUpdate()
                  updateStmt.close()
                } catch {
                  case e: Exception => e.printStackTrace()
                } finally {
                  if (updateConn != null) updateConn.close()
                }
              } else {
                println(s" Error sending to Kafka: ${exception.getMessage}")
              }
            }
          }

          rs.close()
          stmt.close()
        } catch {
          case e: Exception => e.printStackTrace()
        } finally {
          if (conn != null) conn.close()
        }
      }
    }

    timer.schedule(task, 0, AppConfig.intervalSeconds * 1000)
  }

  def stop(): Unit = timer.cancel()
}

def main(args: Array[String]): Unit = {
  if (args.isEmpty) {
    println("You must provide the table name as an argument")
    sys.exit(1)
  }
  val tableName = args(0)

  println(tableName)

  val topicName=AppConfig.kfkTopic+tableName            
  ensureTopicExists(topicName)
  ChangePublisher.run(tableName)

  sys.addShutdownHook {
    println("Cerrando Kafka Producer...")
    ChangePublisher.stop()           
    KafkaProducerService.close()     
  }
}