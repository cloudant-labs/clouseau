package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.Exponent
import com.cloudant.ziose.otp.OTPNodeConfig
import com.typesafe.config.ConfigFactory
import pureconfig.error.ConfigReaderException
import zio.Config.Error
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.{Config, ConfigProvider, IO, LogLevel, ZIO, ZIOAppArgs, ZLayer}

sealed abstract class LogOutput
sealed abstract class LogFormat

object LogOutput {
  case object Stdout extends LogOutput
  case object Syslog extends LogOutput
}

object LogFormat {
  case object Raw  extends LogFormat
  case object Text extends LogFormat
  case object JSON extends LogFormat
}

final case class LogConfiguration(
  output: LogOutput = LogOutput.Stdout,
  format: LogFormat = LogFormat.Raw,
  level: LogLevel = LogLevel.Debug,
  syslog: SyslogConfiguration = SyslogConfiguration()
)

object LogConfiguration {
  def readLogLevel(value: String): Either[Error, LogLevel] = {
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
          Error.InvalidData(message = {
            s"LogLevel must be one of (case insensitive) ALL|FATAL|ERROR|WARNING|INFO|DEBUG|TRACE|NONE (got '${value}')"
          })
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
)

sealed abstract class SyslogProtocol

object SyslogProtocol {
  case object TCP extends SyslogProtocol
  case object UDP extends SyslogProtocol
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
  def validateExponent(value: Int): Either[Error, Exponent] =
    if (1 <= value && value <= 16)
      Right(Exponent(value))
    else
      Left(
        Error.InvalidData(
          message = s"Exponent must be between 1 and 16 inclusive, got '$value'"
        )
      )
}

final case class AppCfg(
  config: List[Configuration] = List(Configuration()),
  configIndex: Int = 0,
  logger: LogConfiguration = LogConfiguration()
) {
  override def toString: String = ConfigWriter[AppCfg].to(this).render(AppCfg.formatOptions)

  if (configIndex < 0 || configIndex >= config.length)
    throw new IllegalArgumentException(s"Node index ${configIndex + 1} must be a valid index: 1 to ${config.length}")
}

object AppCfg {
  import pureconfig._
  import pureconfig.generic.semiauto._

  implicit val clouseauConfigurationReader: ConfigReader[ClouseauConfiguration] = deriveReader
  implicit val otpNodeConfigReader: ConfigReader[OTPNodeConfig]                 = deriveReader
  implicit val exponentReader: ConfigReader[Exponent]                           = deriveReader
  implicit val capacityConfigurationReader: ConfigReader[CapacityConfiguration] = deriveReader
  implicit val stdoutOutputReader: ConfigReader[LogOutput.Stdout.type]          = deriveReader
  implicit val syslogOutputReader: ConfigReader[LogOutput.Syslog.type]          = deriveReader
  implicit val logOutputReader: ConfigReader[LogOutput]                         = deriveReader
  implicit val rawLogFormat: ConfigReader[LogFormat.Raw.type]                   = deriveReader
  implicit val textLogFormat: ConfigReader[LogFormat.Text.type]                 = deriveReader
  implicit val jsonLogFormat: ConfigReader[LogFormat.JSON.type]                 = deriveReader
  implicit val formatReader: ConfigReader[LogFormat]                            = deriveReader
  implicit val levelReader: ConfigReader[LogLevel]                              = deriveReader
  implicit val tcpProtocolReader: ConfigReader[SyslogProtocol.TCP.type]         = deriveReader
  implicit val udpProtocolReader: ConfigReader[SyslogProtocol.UDP.type]         = deriveReader
  implicit val syslogProtocolReader: ConfigReader[SyslogProtocol]               = deriveReader
  implicit val syslogReader: ConfigReader[SyslogConfiguration]                  = deriveReader
  implicit val configurationReader: ConfigReader[Configuration]                 = deriveReader
  implicit val logConfigurationReader: ConfigReader[LogConfiguration]           = deriveReader
  implicit val appConfigReader: ConfigReader[AppCfg]                            = deriveReader
  implicit val nodeIndexPropertyReader: ConfigReader[NodeIndexProperty]         = deriveReader

  implicit val exponentDescriptor: DeriveConfig[Exponent] =
    DeriveConfig[Int].mapOrFail(CapacityConfiguration.validateExponent)

  implicit val logLevelDescriptor: DeriveConfig[LogLevel] =
    DeriveConfig[String].mapOrFail(LogConfiguration.readLogLevel)

  val config: Config[AppCfg]                        = deriveConfig[AppCfg]
  val clouseauConfig: Config[ClouseauConfiguration] = deriveConfig[ClouseauConfiguration]

  def fromHoconFilePath(pathToCfgFile: String): IO[Config.Error, AppCfg] =
    ConfigProvider.fromHoconFilePath(pathToCfgFile).load(config)

  def fromHoconString(input: String): IO[Config.Error, AppCfg] =
    ConfigProvider.fromHoconString(input).load(config)

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
