package kamon.influxdb

import com.typesafe.config.Config
import kamon.influxdb.InfluxDBReporter.Settings
import kamon.metric.{MetricDistribution, MetricValue, PeriodSnapshot}
import kamon.util.EnvironmentTagBuilder
import kamon.{Kamon, MetricReporter}
import okhttp3.{MediaType, OkHttpClient, Request, RequestBody, Authenticator, Route, Response, Credentials}
import org.slf4j.LoggerFactory

import scala.util.Try

class InfluxDBReporter(config: Config = Kamon.config()) extends MetricReporter {
  private val logger = LoggerFactory.getLogger(classOf[InfluxDBReporter])
  private var settings = InfluxDBReporter.readSettings(config)
  private val client = buildClient(settings)

  override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
    val request = new Request.Builder()
      .url(settings.url)
      .post(translateToLineProtocol(snapshot))
      .build()

    Try {
      val response = client.newCall(request).execute()
      if(response.isSuccessful())
        logger.trace("Successfully sent metrics to InfluxDB")
      else {
        logger.error("Metrics POST to InfluxDB failed with status code [{}], response body: {}",
          response.code(),
          response.body().string())
      }

      response.close()

    }.failed.map {
      error => logger.error("Failed to POST metrics to InfluxDB", error)
    }
  }


  override def start(): Unit = {}

  override def stop(): Unit = {}

  override def reconfigure(config: Config): Unit = {
    settings = InfluxDBReporter.readSettings(config)
  }

  private def translateToLineProtocol(periodSnapshot: PeriodSnapshot): RequestBody = {
    import periodSnapshot.metrics._
    val builder = StringBuilder.newBuilder

    counters.foreach(c => writeMetricValue(builder, c, "count", periodSnapshot.to.getEpochSecond))
    gauges.foreach(g => writeMetricValue(builder, g, "value", periodSnapshot.to.getEpochSecond))
    histograms.foreach(h => writeMetricDistribution(builder, h, settings.percentiles, periodSnapshot.to.getEpochSecond))
    rangeSamplers.foreach(rs => writeMetricDistribution(builder, rs, settings.percentiles, periodSnapshot.to.getEpochSecond))

    RequestBody.create(MediaType.parse("text/plain"), builder.result())
  }

  private def writeMetricValue(builder: StringBuilder, metric: MetricValue, fieldName: String, timestamp: Long): Unit = {
    writeNameAndTags(builder, metric.name, metric.tags)
    writeIntField(builder, fieldName, metric.value, appendSeparator = false)
    writeTimestamp(builder, timestamp)
  }

  private def writeMetricDistribution(builder: StringBuilder, metric: MetricDistribution, percentiles: Seq[Double], timestamp: Long): Unit = {
    writeNameAndTags(builder, metric.name, metric.tags)
    writeIntField(builder, "count", metric.distribution.count)
    writeIntField(builder, "sum", metric.distribution.sum)
    writeIntField(builder, "min", metric.distribution.min)

    percentiles.foreach(p => {
      writeDoubleField(builder, "p" + String.valueOf(p), metric.distribution.percentile(p).value)
    })

    writeIntField(builder, "max", metric.distribution.max, appendSeparator = false)
    writeTimestamp(builder, timestamp)
  }

  private def writeNameAndTags(builder: StringBuilder, name: String, metricTags: Map[String, String]): Unit = {
    builder
      .append(name)

    val tags = if(settings.additionalTags.nonEmpty) metricTags ++ settings.additionalTags else metricTags

    if(tags.nonEmpty) {
      tags.foreach {
        case (key, value) =>
          builder
            .append(',')
            .append(escapeString(key))
            .append("=")
            .append(escapeString(value))
      }
    }

    builder.append(' ')
  }

  private def escapeString(in: String): String =
    in.replace(" ", "\\ ")
      .replace("=", "\\=")
      .replace(",", "\\,")

  def writeDoubleField(builder: StringBuilder, fieldName: String, value: Double, appendSeparator: Boolean = true): Unit = {
    builder
      .append(fieldName)
      .append('=')
      .append(String.valueOf(value))

    if(appendSeparator)
      builder.append(',')
  }

  def writeIntField(builder: StringBuilder, fieldName: String, value: Long, appendSeparator: Boolean = true): Unit = {
    builder
      .append(fieldName)
      .append('=')
      .append(String.valueOf(value))
      .append('i')

    if(appendSeparator)
      builder.append(',')
  }

  def writeTimestamp(builder: StringBuilder, timestamp: Long): Unit = {
    builder
      .append(' ')
      .append(timestamp)
      .append("\n")
  }

  protected def buildClient(settings: Settings): OkHttpClient = {
    val basicBuilder = new OkHttpClient.Builder()
    val authenticator = settings.credentials.map(credentials => new Authenticator() {
      def authenticate(route: Route, response: Response): Request = {
        response.request().newBuilder().header("Authorization", credentials).build()
      }
    })
    authenticator.foldLeft(basicBuilder){ case (builder, auth) => builder.authenticator(auth)}.build()
  }
}

object InfluxDBReporter {
  case class Settings(
    url: String,
    percentiles: Seq[Double],
    credentials: Option[String],
    additionalTags: Map[String, String]
  )

  def readSettings(config: Config): Settings = {
    import scala.collection.JavaConverters._
    val root = config.getConfig("kamon.influxdb")
    val host = root.getString("hostname")
    val authConfig = Try(root.getConfig("authentication")).toOption
    val credentials = authConfig.map(conf => Credentials.basic(conf.getString("user"), conf.getString("password")))
    val port = root.getInt("port")
    val database = root.getString("database")
    val protocol = root.getString("protocol").toLowerCase
    val url = s"$protocol://$host:$port/write?precision=s&db=$database"

    val additionalTags = EnvironmentTagBuilder.create(root.getConfig("additional-tags"))

    Settings(
      url,
      root.getDoubleList("percentiles").asScala.map(_.toDouble),
      credentials,
      additionalTags
    )
  }
}