package kamon.influxdb

import java.time.Instant
import java.util.concurrent.TimeUnit

import com.typesafe.config.Config
import kamon.influxdb.InfluxDBReporter.Settings
import kamon.metric.{MetricSnapshot, PeriodSnapshot}
import kamon.Kamon
import kamon.module.{MetricReporter, ModuleFactory}
import kamon.tag.{Tag, TagSet}
import kamon.util.{EnvironmentTags, Filter}
import okhttp3.{Credentials, Interceptor, MediaType, OkHttpClient, Request, RequestBody, Response}
import org.slf4j.LoggerFactory

import scala.util.Try


class InfluxDBReporterFactory extends ModuleFactory {
  override def create(settings: ModuleFactory.Settings): InfluxDBReporter = new InfluxDBReporter()
}

class InfluxDBReporter extends MetricReporter {
  protected val logger = LoggerFactory.getLogger(classOf[InfluxDBReporter])
  @volatile protected var settings = InfluxDBReporter.readSettings(Kamon.config())
  @volatile protected var client = buildClient(settings)

  override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
    val request = new Request.Builder()
      .url(settings.url)
      .post(translateToLineProtocol(snapshot))
      .build()

    Try {
      val response = client.newCall(request).execute()
      if(response.isSuccessful()) {
        if (logger.isTraceEnabled()) {
          logger.trace("Successfully sent metrics to InfluxDB")
        }
      } else {
        logger.error(
          "Metrics POST to InfluxDB failed with status code [{}], response body: {}",
          response.code(),
          response.body().string()
        )
      }

      response.close()

    }.failed.map {
      error => logger.error("Failed to POST metrics to InfluxDB", error)
    }
  }


  override def stop(): Unit = {}

  override def reconfigure(config: Config): Unit = {
    settings = InfluxDBReporter.readSettings(config)
    client = buildClient(settings)
  }

  protected def getTimestamp(instant: Instant): String = {
     settings.measurementPrecision match {
       case "s" =>
         instant.getEpochSecond.toString
       case "ms" =>
         instant.toEpochMilli.toString
       case "u" | "µ" =>
         ((BigInt(instant.getEpochSecond) * 1000000) + TimeUnit.NANOSECONDS.toMicros(instant.getNano)).toString
       case "ns" =>
          ((BigInt(instant.getEpochSecond) * 1000000000) + instant.getNano).toString
     }
  }

  private def translateToLineProtocol(periodSnapshot: PeriodSnapshot): RequestBody = {
    val builder = StringBuilder.newBuilder

    val timestamp = getTimestamp(periodSnapshot.to)

    periodSnapshot.counters.foreach(c => writeLongMetricValue(builder, c, "count", timestamp))
    periodSnapshot.gauges.foreach(g => writeDoubleMetricValue(builder, g, "value", timestamp))
    periodSnapshot.histograms.foreach(h => writeMetricDistribution(builder, h, settings.percentiles, timestamp))
    periodSnapshot.rangeSamplers.foreach(rs => writeMetricDistribution(builder, rs, settings.percentiles, timestamp))
    periodSnapshot.timers.foreach(t => writeMetricDistribution(builder, t, settings.percentiles, timestamp))

    RequestBody.create(MediaType.parse("text/plain"), builder.result())
  }

  private def writeLongMetricValue(builder: StringBuilder, metric: MetricSnapshot.Values[Long], fieldName: String, timestamp: String): Unit = {
    metric.instruments.foreach{instrument =>
      writeNameAndTags(builder, metric.name, instrument.tags)
      writeIntField(builder, fieldName, instrument.value, appendSeparator = false)
      writeTimestamp(builder, timestamp)
    }
  }

  private def writeDoubleMetricValue(builder: StringBuilder, metric: MetricSnapshot.Values[Double], fieldName: String, timestamp: String): Unit = {
    metric.instruments.foreach{instrument =>
      writeNameAndTags(builder, metric.name, instrument.tags)
      writeDoubleField(builder, fieldName, instrument.value, appendSeparator = false)
      writeTimestamp(builder, timestamp)
    }
  }

  private def writeMetricDistribution(builder: StringBuilder, metric: MetricSnapshot.Distributions, percentiles: Seq[Double], timestamp: String): Unit = {
    metric.instruments.foreach { instrument =>
      if (instrument.value.count > 0) {
        writeNameAndTags(builder, metric.name, instrument.tags)
        writeIntField(builder, "count", instrument.value.count)
        writeIntField(builder, "sum", instrument.value.sum)
        writeIntField(builder, "min", instrument.value.min)

        percentiles.foreach { p =>
          writeDoubleField(builder, "p" + String.valueOf(p), instrument.value.percentile(p).value)

        }

        writeIntField(builder, "max", instrument.value.max, appendSeparator = false)
        writeTimestamp(builder, timestamp)
      }
    }
  }

  private def writeNameAndTags(builder: StringBuilder, name: String, metricTags: TagSet): Unit = {
    builder
      .append(escapeName(name))

    val tags = (if(settings.additionalTags.nonEmpty) metricTags.withTags(settings.additionalTags) else metricTags).all()

    if(tags.nonEmpty) {
      tags.foreach { t =>
        if (settings.tagFilter.accept(t.key)) {
          builder
            .append(',')
            .append(escapeString(t.key))
            .append("=")
            .append(escapeString(Tag.unwrapValue(t).toString))
        } else {
          if (logger.isTraceEnabled) {
            logger.trace("Filtered tag {}", t.key)
          }
        }
      }
    }

    builder.append(' ')
  }

  private def escapeName(in: String): String =
    in.replace(" ", "\\ ")
      .replace(",", "\\,")


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

  def writeTimestamp(builder: StringBuilder, timestamp: String): Unit = {
    builder
      .append(' ')
      .append(timestamp)
      .append("\n")
  }

  protected def buildClient(settings: Settings): OkHttpClient = {
    val basicBuilder = new OkHttpClient.Builder()
    val authenticator = settings.credentials.map(credentials => new Interceptor {
      override def intercept(chain: Interceptor.Chain): Response = {
        chain.proceed(chain.request().newBuilder().header("Authorization", credentials).build())
      }
    })
    authenticator.foldLeft(basicBuilder){ case (builder, auth) => builder.addInterceptor(auth)}.build()
  }
}

object InfluxDBReporter {
  case class Settings(
    url: String,
    percentiles: Seq[Double],
    credentials: Option[String],
    tagFilter: Filter,
    postEmptyDistributions: Boolean,
    additionalTags:TagSet,
    measurementPrecision: String
  )

  def readSettings(config: Config): Settings = {
    import scala.collection.JavaConverters._
    val root = config.getConfig("kamon.influxdb")
    val host = root.getString("hostname")
    val credentials = if (root.hasPath("authentication")) {
      Some(Credentials.basic(root.getString("authentication.user"), root.getString("authentication.password")))
    } else {
      None
    }
    val port = root.getInt("port")
    val database = root.getString("database")
    val protocol = root.getString("protocol").toLowerCase
    val additionalTags = EnvironmentTags.from(Kamon.environment, root.getConfig("environment-tags"))

    val precision = root.getString("precision")

    if (!Set("ns","u","µ","ms","s").contains(precision)){
      throw new RuntimeException("Precision must be one of `[ns,u,µ,ms,s]` to match https://docs.influxdata.com/influxdb/v1.7/tools/api/#query-string-parameters-1")

    }

    val url = s"$protocol://$host:$port/write?precision=$precision&db=$database"

    Settings(
      url,
      root.getDoubleList("percentiles").asScala.toList.map(_.toDouble),
      credentials,
      Filter.from("kamon.influxdb.tag-filter"),
      root.getBoolean("post-empty-distributions"),
      additionalTags,
      precision
    )
  }
}
