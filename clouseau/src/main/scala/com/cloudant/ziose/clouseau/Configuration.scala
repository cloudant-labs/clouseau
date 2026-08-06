package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.Exponent
import com.cloudant.ziose.otp.OTPNodeConfig
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import pureconfig.ConfigWriter
import pureconfig.error.{ConfigReaderException, FailureReason}
import pureconfig.generic.ProductHint
import zio.{LogLevel, System, Task, UIO, ZIO, ZIOAppArgs, ZLayer}

sealed abstract class LogOutput
sealed abstract class LogFormat

object LogOutput {
  case object Stdout extends LogOutput
  case object Syslog extends LogOutput

  def parseLogOutput(value: String): Either[FailureReason, LogOutput] =
    value.trim().toUpperCase() match {
      case "STDOUT" => Right(Stdout)
      case "SYSLOG" => Right(Syslog)
      case _        =>
        Left(
          pureconfig.error.CannotConvert(
            value,
            "LogOutput",
            "LogOutput must be one of (case insensitive) STDOUT|SYSLOG"
          )
        )
    }
}

object LogFormat {
  case object Raw  extends LogFormat
  case object Text extends LogFormat
  case object JSON extends LogFormat

  def parseLogFormat(value: String): Either[FailureReason, LogFormat] =
    value.trim().toUpperCase() match {
      case "RAW"  => Right(Raw)
      case "TEXT" => Right(Text)
      case "JSON" => Right(JSON)
      case _      =>
        Left(
          pureconfig.error.CannotConvert(
            value,
            "LogFormat",
            "LogFormat must be one of (case insensitive) RAW|TEXT|JSON"
          )
        )
    }
}

final case class LogConfiguration(
  output: LogOutput = LogOutput.Stdout,
  format: LogFormat = LogFormat.Raw,
  level: LogLevel = LogLevel.Debug,
  syslog: SyslogConfiguration = SyslogConfiguration()
)

object LogConfiguration {
  def parseLogLevel(value: String): Either[FailureReason, LogLevel] = {
    value.trim().toUpperCase match {
      case "ALL"     => Right(LogLevel.All)
      case "FATAL"   => Right(LogLevel.Fatal)
      case "ERROR"   => Right(LogLevel.Error)
      case "WARNING" => Right(LogLevel.Warning)
      case "INFO"    => Right(LogLevel.Info)
      case "DEBUG"   => Right(LogLevel.Debug)
      case "TRACE"   => Right(LogLevel.Trace)
      case "NONE"    => Right(LogLevel.None)
      case _         =>
        Left(
          pureconfig.error.CannotConvert(
            value,
            "LogLevel",
            "LogLevel must be one of (case insensitive) ALL|FATAL|ERROR|WARNING|INFO|DEBUG|TRACE|NONE"
          )
        )
    }
  }
}

final case class ClouseauConfiguration(
  dir: String = "target/indexes",
  search_allowed_timeout_msecs: Long = 5000,
  count_fields: Boolean = false,
  count_locks: Boolean = false,
  close_if_idle: Boolean = false,
  idle_check_interval_secs: Int = 300,
  lru_update_interval_msecs: Int = 1000,
  max_indexes_open: Int = 100,
  field_count_warn_threshold: Int = 5000,
  commit_interval_secs: Int = 30,
  lock_class: String = "org.apache.lucene.store.NativeFSLockFactory",
  dir_class: String = "org.apache.lucene.store.NIOFSDirectory",
  concurrent_search_enabled: Boolean = false,
  concurrent_search_limit: Int = 1000,
  track_index_atimes: Boolean = false
)

final case class Configuration(
  node: OTPNodeConfig = OTPNodeConfig(),
  clouseau: ClouseauConfiguration = ClouseauConfiguration(),
  capacity: CapacityConfiguration = CapacityConfiguration()
) {
  import AppCfg.configurationWriter
  override def toString: String = ConfigWriter[Configuration].to(this).render(AppCfg.formatOptions)
}

final case class PropertyConfiguration(
  clouseau: ClouseauConfiguration = ClouseauConfiguration()
)

sealed abstract class SyslogProtocol

object SyslogProtocol {
  case object TCP extends SyslogProtocol
  case object UDP extends SyslogProtocol

  def parseSyslogProtocol(value: String): Either[FailureReason, SyslogProtocol] =
    value.trim().toUpperCase() match {
      case "TCP" => Right(TCP)
      case "UDP" => Right(UDP)
      case _     =>
        Left(
          pureconfig.error.CannotConvert(
            value,
            "SyslogProtocol",
            "SyslogProtocol must be one of (case insensitive) TCP|UDP"
          )
        )
    }
}

final case class SyslogConfiguration(
  protocol: SyslogProtocol = SyslogProtocol.UDP,
  host: String = "localhost",
  port: Int = 514,
  facility: String = "CONSOLE",
  level: String = "debug",
  tag: String = ""
)

final case class NodeIndexProperty(node: String)

/**
 * A data type to hold configured capacity exponent values. Exponent must be greater than 0. If not specified
 * backpressure wouldn't be applied.
 * @param analyzer_exponent
 *   An exponent to calculate capacity of the message queue used for ''AnalyzerService''.
 * @param cleanup_exponent
 *   An exponent to calculate capacity of the message queue used for ''CleanupService''.
 * @param index_exponent
 *   An exponent to calculate capacity of the message queue used for ''IndexService''.
 * @param init_exponent
 *   An exponent to calculate capacity of the message queue used for ''InitService''.
 * @param main_exponent
 *   An exponent to calculate capacity of the message queue used for ''InitService''.
 */
final case class CapacityConfiguration(
  analyzer_exponent: Option[Exponent] = None,
  cleanup_exponent: Option[Exponent] = None,
  index_exponent: Option[Exponent] = None,
  init_exponent: Option[Exponent] = None,
  main_exponent: Option[Exponent] = None
)

object CapacityConfiguration {
  def validateExponent(value: Int): Either[FailureReason, Exponent] =
    if (1 <= value && value <= 16)
      Right(Exponent(value))
    else
      Left(
        pureconfig.error.CannotConvert(
          value.toString,
          "Exponent",
          "Exponent must be between 1 and 16 inclusive"
        )
      )
}

final case class AppCfg(
  config: List[Configuration] = List(Configuration()),
  configIndex: Int = 0,
  logger: LogConfiguration = LogConfiguration()
) {
  override def toString: String = ConfigWriter[AppCfg].to(this).render(AppCfg.formatOptions)

  if (config.isEmpty)
    throw new IllegalArgumentException("'config' list in configuration file must not be empty")

  if (configIndex < 0 || configIndex >= config.length)
    throw new IllegalArgumentException(s"Node index ${configIndex + 1} must be a valid index: 1 to ${config.length}")
}

object AppCfg {
  import pureconfig._
  import pureconfig.generic.semiauto._

  implicit val otpNodeConfigReader: ConfigReader[OTPNodeConfig] = deriveReader
  implicit val otpNodeConfigWriter: ConfigWriter[OTPNodeConfig] = deriveWriter

  implicit val exponentReader: ConfigReader[Exponent] = ConfigReader[Int].emap(CapacityConfiguration.validateExponent)
  implicit val exponentWriter: ConfigWriter[Exponent] = deriveWriter
  implicit val capacityConfigurationReader: ConfigReader[CapacityConfiguration] = deriveReader
  implicit val capacityConfigurationWriter: ConfigWriter[CapacityConfiguration] = deriveWriter

  implicit val logOutputReader: ConfigReader[LogOutput] = ConfigReader[String].emap(LogOutput.parseLogOutput)
  implicit val stdoutOutputWriter: ConfigWriter[LogOutput.Stdout.type] = deriveWriter
  implicit val syslogOutputWriter: ConfigWriter[LogOutput.Syslog.type] = deriveWriter
  implicit val logOutputWriter: ConfigWriter[LogOutput]                = deriveWriter

  implicit val formatReader: ConfigReader[LogFormat] = ConfigReader[String].emap(LogFormat.parseLogFormat)
  implicit val levelReader: ConfigReader[LogLevel]   = ConfigReader[String].emap(LogConfiguration.parseLogLevel)
  implicit val rawLogFormatWriter: ConfigWriter[LogFormat.Raw.type]   = deriveWriter
  implicit val textLogFormatWriter: ConfigWriter[LogFormat.Text.type] = deriveWriter
  implicit val jsonLogFormatWriter: ConfigWriter[LogFormat.JSON.type] = deriveWriter
  implicit val formatWriter: ConfigWriter[LogFormat]                  = deriveWriter
  implicit val levelWriter: ConfigWriter[LogLevel]                    = deriveWriter

  implicit val tcpProtocolWriter: ConfigWriter[SyslogProtocol.TCP.type] = deriveWriter
  implicit val udpProtocolWriter: ConfigWriter[SyslogProtocol.UDP.type] = deriveWriter
  implicit val syslogProtocolReader: ConfigReader[SyslogProtocol]       =
    ConfigReader[String].emap(SyslogProtocol.parseSyslogProtocol)
  implicit val syslogProtocolWriter: ConfigWriter[SyslogProtocol] = deriveWriter
  implicit val syslogReader: ConfigReader[SyslogConfiguration]    = deriveReader
  implicit val syslogWriter: ConfigWriter[SyslogConfiguration]    = deriveWriter

  implicit val logConfigurationReader: ConfigReader[LogConfiguration] = deriveReader
  implicit val logConfigurationWriter: ConfigWriter[LogConfiguration] = deriveWriter

  implicit val configurationReader: ConfigReader[Configuration] = deriveReader
  implicit val configurationWriter: ConfigWriter[Configuration] = deriveWriter

  implicit val appConfigReader: ConfigReader[AppCfg]                            = deriveReader
  implicit val appConfigWriter: ConfigWriter[AppCfg]                            = deriveWriter
  implicit val nodeIndexPropertyReader: ConfigReader[NodeIndexProperty]         = deriveReader
  implicit val propertyConfigurationReader: ConfigReader[PropertyConfiguration] = deriveReader

  implicit val clouseauConfigurationReader: ConfigReader[ClouseauConfiguration] = deriveReader
  implicit val clouseauConfigurationWriter: ConfigWriter[ClouseauConfiguration] = deriveWriter

  implicit val propertyConfigurationWriter: ConfigWriter[PropertyConfiguration] = deriveWriter

  private val fieldCase: ConfigFieldMapping = ConfigFieldMapping(SnakeCase, SnakeCase)
  implicit val capacityConfigurationSyntaxOptions: ProductHint[CapacityConfiguration] = ProductHint(fieldCase)
  implicit val propertyConfigurationSyntaxOptions: ProductHint[PropertyConfiguration] = ProductHint(fieldCase)
  implicit val clouseauConfigurationSyntaxOptions: ProductHint[ClouseauConfiguration] = ProductHint(fieldCase)
  implicit val appCfgSyntaxOptions: ProductHint[AppCfg]                               = ProductHint(fieldCase)

  private val getProperties: ZIO[Any, Throwable, Map[String, String]] = for {
    properties <- System.properties
  } yield {
    val whitelist = List("node", "clouseau")
    properties.filter { case (k, _) =>
      k.split('.').headOption.exists(s => whitelist.contains(s))
    }
  }

  private def getNodeIndex: Task[Int] = {
    import scala.jdk.CollectionConverters._
    for {
      properties <- getProperties
      parseResult = ConfigSource.fromConfig(ConfigFactory.parseMap(properties.asJava)).load[NodeIndexProperty]
    } yield parseResult.fold(
      _ => 0,
      {
        case NodeIndexProperty(nodeName) if nodeName.nonEmpty =>
          val last = nodeName.last
          if ('1' <= last && last <= '3')
            last - '1'
          else
            0
        case _ =>
          0
      }
    )
  }

  def patchClouseauConfig(config: ClouseauConfiguration): Task[ConfigReader.Result[ClouseauConfiguration]] = {
    import scala.jdk.CollectionConverters._
    for {
      properties <- getProperties
      base    = ConfigWriter[PropertyConfiguration].to(PropertyConfiguration(config))
      patch   = ConfigFactory.parseMap(properties.asJava)
      patched = patch.withFallback(base)
    } yield ConfigSource.fromConfig(patched).load[PropertyConfiguration].map(_.clouseau)
  }

  val formatOptions: ConfigRenderOptions = ConfigRenderOptions.concise().setFormatted(true)

  def fromHoconFile(path: String): UIO[ConfigReader.Result[AppCfg]] =
    ConfigSource.file(path).config() match {
      case Right(contents) =>
        for {
          _ <- ZIO.logInfo(s"Parsed configuration file $path: ${contents.root().render(formatOptions)}")
        } yield ConfigSource.fromConfig(contents).load
      case Left(err) => ZIO.succeed(Left(err))
    }

  def layer: ZLayer[ZIOAppArgs, Throwable, AppCfg] =
    ZLayer {
      for {
        args      <- ZIOAppArgs.getArgs
        appConfig <- args.headOption.fold(ZIO.succeed(AppCfg()))(s =>
          for {
            fileParseResult <- fromHoconFile(s)
          } yield fileParseResult match {
            case Right(appConfig) => appConfig
            case Left(err)        => throw ConfigReaderException[AppCfg](err)
          }
        )
        index <- getNodeIndex
      } yield appConfig.copy(configIndex = index)
    }
}
