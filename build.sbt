ThisBuild / scalaVersion := "2.13.16"

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
  "zio"         -> "2.0.21",
  "zio.config"  -> "4.0.2",
  "zio.logging" -> "2.3.1",
  "zio.metrics" -> "2.3.1",
  "jmx"         -> "1.12.3",
  "reflect"     -> "2.13.14",
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

val commonMergeStrategy: String => sbtassembly.MergeStrategy = {
  case PathList(ps @ _*) if ps.last == "module-info.class" => MergeStrategy.discard
  case PathList("META-INF", "services", xs @ _*) if xs.last.contains("org.apache.lucene") =>
    MergeStrategy.preferProject
  case PathList("META-INF", "MANIFEST.MF")             => MergeStrategy.discard
  case PathList("META-INF", "LICENSE.txt")             => MergeStrategy.first
  case PathList("NOTICE", _*)                          => MergeStrategy.discard
  case PathList(ps @ _*) if Assembly.isReadme(ps.last) => MergeStrategy.discard
  case _                                               => MergeStrategy.deduplicate
}

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
  assembly / fullClasspath ++= (
    if (sys.props.getOrElse("jartest", "false").toBoolean) (Test / fullClasspath).value else Seq()
  ),
  assemblyPackageScala / assembleArtifact := false,
  testFrameworks                          := Seq(new TestFramework("com.novocode.junit.JUnitFramework")),
  dependencyCheckAssemblyAnalyzerEnabled  := Some(false),
  dependencyCheckFormats                  := Seq("XML", "JSON"),
  dependencyCheckSuppressionFiles         := Seq(new File("dependency-check-suppressions.xml")),
  scalacOptions ++= Seq("-Ymacro-annotations", "-Ywarn-unused:imports")
)

lazy val vendor = (project in file("vendor"))
  .settings(commonSettings *)

lazy val core = (project in file("core"))
  .settings(commonSettings *)
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
    dependencyCheckAssemblyAnalyzerEnabled := Some(false),
    dependencyCheckFormats                 := Seq("XML", "JSON")
  )
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .dependsOn(macros)
  .dependsOn(vendor)

lazy val otp = (project in file("otp"))
  .settings(commonSettings *)
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .dependsOn(core)
  .dependsOn(macros)
  .dependsOn(test % "test->test")
lazy val scalang = (project in file("scalang"))
  .settings(commonSettings *)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .dependsOn(core)
  .dependsOn(macros)

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
  .settings(
    resolvers += "cloudant-repo" at "https://cloudant.github.io/maven/repo/",
    libraryDependencies ++= luceneComponents
  )
  .settings(
    assembly / assemblyJarName := {
      if (sys.props.getOrElse("jartest", "false").toBoolean) {
        s"${name.value}_${scalaVersion.value}_${version.value}_test.jar"
      } else {
        s"${name.value}_${scalaVersion.value}_${version.value}.jar"
      }
    },
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
    outputStrategy       := Some(StdoutOutput)
  )
  .dependsOn(core)
  .dependsOn(macros)
  .dependsOn(otp)
  .dependsOn(scalang)
  .dependsOn(test % "test->test")

lazy val test = (project in file("test"))
  .settings(commonSettings *)
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .dependsOn(core)

lazy val macros = (project in file("macros"))
  .settings(commonSettings *)

lazy val root = (project in file("."))
  .aggregate(core, clouseau, macros, otp, test)
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
  .dependsOn(core)

Global / onChangedBuildSource := ReloadOnSourceChanges
run                           := (clouseau / Compile / run).evaluated

addCommandAlias("repl", "clouseau / console")
