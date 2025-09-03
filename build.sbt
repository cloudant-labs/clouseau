import sbtassembly.Assembly.Dependency
ThisBuild / scalaVersion := "2.13.16"

import net.nmoncho.sbt.dependencycheck.settings.*
import org.owasp.dependencycheck.reporting.ReportGenerator.Format

import com.eed3si9n.jarjarabrams.ShadeRule

val readVersion = {
  val content      = IO.read(file("version.sbt"))
  val versionRegex = """.*version\s*:=\s*"([^"]+)".*""".r
  versionRegex.findFirstMatchIn(content) match {
    case Some(m) => m.group(1)
    case None    => throw new Exception("Could not parse version from version.sbt")
  }
}

ThisBuild / version := s"${readVersion}"

updateOptions := updateOptions.value.withCachedResolution(true)

val versions: Map[String, String] = Map(
  "zio"         -> "2.1.16",
  "zio.config"  -> "4.0.4",
  "zio.logging" -> "2.5.0",
  "zio.metrics" -> "2.3.1",
  "jmx"         -> "1.14.5",
  "reflect"     -> "2.13.16",
  "lucene"      -> "4.6.1-cloudant1",
  "tinylog"     -> "2.7.0"
)

lazy val luceneComponents = Seq(
  // The single % is for java libraries
  // the %% appends the version of scala used, and should be used for scala libraries;
  // the %%% is for scala-js (and scala native).
  "org.apache.lucene" % "lucene-core"               % versions("lucene"),
  "org.apache.lucene" % "lucene-grouping"           % versions("lucene"),
  "org.apache.lucene" % "lucene-queryparser"        % versions("lucene"),
  "org.apache.lucene" % "lucene-analyzers-common"   % versions("lucene"),
  "org.apache.lucene" % "lucene-analyzers-stempel"  % versions("lucene"),
  "org.apache.lucene" % "lucene-analyzers-smartcn"  % versions("lucene"),
  "org.apache.lucene" % "lucene-analyzers-kuromoji" % versions("lucene"),
  "org.apache.lucene" % "lucene-facet"              % versions("lucene"),
  "org.apache.lucene" % "lucene-spatial"            % versions("lucene"),
  "org.apache.lucene" % "lucene-highlighter"        % versions("lucene")
)

/**
  * The `testOverrides` map defines a mechanism for selectively overriding class
  * implementations during testing, while keeping production code untouched.
  * This allows us to inject test-specific behavior without modifying production logic.
  *
  * Key: Name of the class used in production.
  * Value: Name of the class used in testing.
  *
  * During the test build:
  *   - The test class (value) is renamed to match the production class name (key).
  *   - This renamed class is compiled into the corresponding .class file,
  *     effectively replacing the production implementation for testing purposes.
  *
  * During the production build:
  *
  *   - The original production class is used as-is.
  *   - No overrides or renaming occur.
  **/
val testOverrides = Map(
  // override "EchoService" with content of "TestEchoService" in `_test.jar``
  "EchoService" -> "TestEchoService"
)

def isOverriden(classFileName: String) =
  getOverride(classFileName).isDefined

def getOverride(classFileName: String) = {
  // this is inefficient, but we don't have a prefix trie structure
  testOverrides.find { case (production, _) => {
    isRelatedClassFile(production, classFileName)
  }}.map(_._2)
}

val shadeRules: Seq[ShadeRule] = testOverrides.map { case (production, testing) => {
  ShadeRule.rename(s"com.cloudant.ziose.clouseau.${testing}" -> s"com.cloudant.ziose.clouseau.${production}").inAll
}}.toSeq

/*
Return `true` for all files related to given `base`. Might return false positive if
specified `baseName` is not unique.
*/
def isRelatedClassFile(baseName: String, classFileName: String): Boolean = {
  classFileName.startsWith(baseName) && classFileName.endsWith(".class")
}

def handleOverride(classFileName: String) = {
  val overrideClassFile = s"${getOverride(classFileName).get}.class"
  def shouldOverride(dependency: Dependency) =
    dependency.source.endsWith(overrideClassFile)
  CustomMergeStrategy("test-override") { conflicts =>
    if (isTestJar) {
      val entry = conflicts.find(shouldOverride(_)).getOrElse(conflicts.head)
      Right(Vector(JarEntry(entry.target, entry.stream)))
    } else {
      val entry = conflicts.find(!shouldOverride(_)).getOrElse(conflicts.head)
      Right(Vector(JarEntry(entry.target, entry.stream)))
    }
  }
}

val commonMergeStrategy: String => sbtassembly.MergeStrategy = {
  case PathList(ps @ _*) if ps.last == "module-info.class" => MergeStrategy.discard
  case PathList("META-INF", "services", xs @ _*) if xs.last.contains("org.apache.lucene") =>
    MergeStrategy.preferProject
  case PathList("META-INF", "MANIFEST.MF")             => MergeStrategy.discard
  case PathList("META-INF", "LICENSE.txt")             => MergeStrategy.first
  case PathList("NOTICE", _*)                          => MergeStrategy.discard
  case PathList(ps @ _*) if Assembly.isReadme(ps.last) => MergeStrategy.discard
  case PathList("com", "cloudant", _, "clouseau", last) if isOverriden(last) => handleOverride(last)
  case ps                                              => MergeStrategy.deduplicate
}

val dcDataDir = sys.props.getOrElse("nvd_data_dir", "/usr/share/dependency-check/data/")

lazy val dependencyCheck = Seq(
  dependencyCheckDataDirectory := Some(new File(dcDataDir)),
  dependencyCheckFormats       := Seq(Format.XML, Format.HTML),
  dependencyCheckSuppressions  := SuppressionSettings(
    files = SuppressionFilesSettings.files()(new File("dependency-check-suppressions.xml"))
  ),
  dependencyCheckAutoUpdate := sys.props.getOrElse("nvd_update", "false").toBoolean
)

val jartestSettings = Seq(
  assembly / assemblyJarName := s"${name.value}_${scalaVersion.value}_${version.value}_test.jar",
  assembly / fullClasspath ++= (Test / fullClasspath).value,
  Compile / mainClass := Some("com.cloudant.ziose.clouseau.TestJarMain"),
  assembly / mainClass := Some("com.cloudant.ziose.clouseau.TestJarMain"),
)

val defaultSettings = Seq(
  assembly / assemblyJarName := s"${name.value}_${scalaVersion.value}_${version.value}.jar"
)

val isTestJar = sys.props.getOrElse("jartest", "false").toBoolean

val settingsToUse = if (isTestJar) { jartestSettings } else { defaultSettings }

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    // The single % is for java libraries
    // the %% appends the version of scala used, and should be used for scala libraries;
    // the %%% is for scala-js (and scala native).
    "dev.zio"       %% "zio"                               % versions("zio"),
    "dev.zio"       %% "zio-config"                        % versions("zio.config"),
    "dev.zio"       %% "zio-config-magnolia"               % versions("zio.config"),
    "dev.zio"       %% "zio-config-typesafe"               % versions("zio.config"),
    "dev.zio"       %% "zio-logging"                       % versions("zio.logging"),
    // This is needed because micrometer (see below) uses SLF4J
    "dev.zio"       %% "zio-logging-slf4j-bridge"          % versions("zio.logging"),
    "dev.zio"       %% "zio-metrics-connectors-micrometer" % versions("zio.metrics"),
    "dev.zio"       %% "zio-streams"                       % versions("zio"),
    "io.micrometer"  % "micrometer-registry-jmx"           % versions("jmx"),
    "org.scala-lang" % "scala-reflect"                     % versions("reflect"),
    "org.tinylog"    % "tinylog-api"                       % versions("tinylog"),
    "org.tinylog"    % "tinylog-impl"                      % versions("tinylog"),
    "dev.zio"       %% "zio-test"                          % versions("zio") % Test,
    "dev.zio"       %% "zio-test-junit"                    % versions("zio") % Test,
    "com.github.sbt" % "junit-interface"                   % "0.13.3"        % Test,
    "junit"          % "junit"                             % "4.13.2"        % Test
  ),
  assembly / assemblyMergeStrategy := commonMergeStrategy,
  ThisBuild / assemblyShadeRules := shadeRules,
  assemblyPackageScala / assembleArtifact := false,
  testFrameworks                          := Seq(new TestFramework("com.novocode.junit.JUnitFramework")),
  scalacOptions ++= Seq("-Ymacro-annotations", "-Ywarn-unused:imports")
) ++ dependencyCheck

lazy val vendor = (project in file("vendor"))
  .settings(commonSettings *)
  .settings(settingsToUse: _*)

lazy val core = (project in file("core"))
  .settings(commonSettings *)
  .settings(settingsToUse: _*)
  .settings(dependencyCheckSkip := false)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .settings(
    Seq(
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := "com.cloudant.ziose.core",
      buildInfoObject  := "BuildInfo"
    )
  )
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .dependsOn(macros)
  .dependsOn(vendor)

lazy val otp = (project in file("otp"))
  .settings(commonSettings *)
  .settings(settingsToUse: _*)
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .dependsOn(core)
  .dependsOn(macros)
  .dependsOn(test % "test->test")
lazy val scalang = (project in file("scalang"))
  .settings(commonSettings *)
  .settings(settingsToUse: _*)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .dependsOn(core)
  .dependsOn(macros)
  .dependsOn(test % "test->test")

lazy val cookie: Option[String] = Option(System.getProperty("cookie"))
lazy val env: Option[String]    = Option(System.getProperty("env"))
lazy val node: Option[String]   = Option(System.getProperty("node"))
lazy val composedOptions: Seq[String] = {
  lazy val options: Seq[String] = Seq(
    env.map("-Denv=" + _).getOrElse("-Denv=prod"),
    node.map("-Dnode=" + _).getOrElse("-Dnode=1")
  )
  if (cookie.isEmpty) options
  else options ++ cookie.map("-Dcookie=" + _).toSeq
}

lazy val clouseau = (project in file("clouseau"))
  .settings(commonSettings *)
  .settings(settingsToUse: _*)
  .settings(
    resolvers += "cloudant-repo" at "https://cloudant.github.io/maven/repo/",
    libraryDependencies ++= luceneComponents
  )
  .settings(
    assemblyPackageScala / assembleArtifact := true
  )
  .settings(
    console / initialCommands := """
      import com.cloudant.ziose._
      import org.apache.lucene
      import com.cloudant.ziose.scalang.Pid
      import com.cloudant.ziose.scalang.Reference
    """,
    Compile / console / scalacOptions ~= { _.filterNot(Set("-Ywarn-unused-import", "-Ywarn-unused:imports")) }
  )
  .settings(
    fork := true,
    javaOptions ++= composedOptions,
    (Compile / run / forkOptions) := (Compile / run / forkOptions).value.withWorkingDirectory(
      (ThisBuild / baseDirectory).value
    ),
    (Test / forkOptions) := (Test / forkOptions).value.withWorkingDirectory(baseDirectory.value),
    // parallelExecution causing a deadlock in scala-test in CI
    (Test / parallelExecution) := false,
    // (Test / logLevel) := Level.Debug,
    (Test / fork := true),
    outputStrategy       := Some(StdoutOutput)
  )
  .dependsOn(core)
  .dependsOn(macros)
  .dependsOn(otp)
  .dependsOn(scalang)
  // include test classes into _test.jar
  .dependsOn(test % "test->test")
  .dependsOn(core % "test->test")
  .dependsOn(otp % "test->test")
  .dependsOn(scalang % "test->test")

lazy val test = (project in file("test"))
  .settings(commonSettings *)
  .settings(settingsToUse: _*)
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .dependsOn(core)

lazy val macros = (project in file("macros"))
  .settings(commonSettings *)
  .settings(settingsToUse: _*)

lazy val root = (project in file("."))
  .aggregate(core, clouseau, macros, otp, test, scalang)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .settings(
    scalacOptions ++= Seq("-Ymacro-annotations", "-Ywarn-unused:imports"),
    inThisBuild(List(organization := "com.cloudant")),
    name := "ziose",
    assembly / assemblyMergeStrategy := commonMergeStrategy
  )
  .settings(
    Compile / console / scalacOptions -= "-Ywarn-unused:imports"
  )
  .settings(dependencyCheck)
  .dependsOn(core)

Global / onChangedBuildSource := ReloadOnSourceChanges
run                           := (clouseau / Compile / run).evaluated

addCommandAlias("repl", "clouseau / console")

Global / excludeLintKeys += dependencyCheckFormats
