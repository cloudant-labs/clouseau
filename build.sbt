ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "2.13.8"

updateOptions := updateOptions.value.withCachedResolution(true)

val versions: Map[String, String] = Map(
  "zio" -> "2.0.10",
  "zio.nio" -> "2.0.1",
  "zio.config" -> "4.0.0-RC12",
  "zio.logging" -> "2.1.10",
  "zio.metrics" -> "2.0.1",
)

def jinterface = Def.setting {
  sys.env.getOrElse("JINTERFACE_VSN", sys.error("Please define JINTERFACE_VSN"))
}

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    // The single % is for java libraries
    // the %% appends the version of scala used, and should be used for scala libraries;
    // the %%% is for scala-js (and scala native).
    "dev.zio"                       %% "zio"                             % versions("zio"),
    "dev.zio"                       %% "zio-nio"                         % versions("zio.nio"),
    "dev.zio"                       %% "zio-metrics-dropwizard"          % versions("zio.metrics"),
    "dev.zio"                       %% "zio-streams"                     % versions("zio"),
    "dev.zio"                       %% "zio-logging"                     % versions("zio.logging"),
    "dev.zio"                       %% "zio-logging-slf4j"               % versions("zio.logging"),
    "dev.zio"                       %% "zio-config"                      % versions("zio.config"),
    "dev.zio"                       %% "zio-config-magnolia"             % versions("zio.config"),
    "dev.zio"                       %% "zio-config-refined"              % versions("zio.config"),
    "dev.zio"                       %% "zio-config-typesafe"             % versions("zio.config"),
    "dev.zio"                       %% "zio-config-yaml"                 % versions("zio.config"),
    "dev.zio"                       %% "zio-test"                        % versions("zio") % Test,
    "dev.zio"                       %% "zio-test-junit"                  % versions("zio") % Test,
    "org.slf4j"                      % "slf4j-nop"                       % "2.0.6",
    "junit"                          % "junit"                           % "4.13.2",
    "com.github.sbt"                 % "junit-interface"                 % "0.13.2" % Test,
  ),
  testFrameworks := Seq(new TestFramework("com.novocode.junit.JUnitFramework")),
  dependencyCheckAssemblyAnalyzerEnabled := Some(false),
  dependencyCheckFormats := Seq("XML", "JSON")
)

lazy val domain = (project in file("domain"))
  .settings(commonSettings: _*)
  .dependsOn(actors)
lazy val experiments = (project in file("experiments"))
  .settings(commonSettings: _*)
  .settings(dependencyCheckSkip := false)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(actors)
lazy val benchmarks = (project in file("benchmarks"))
  .settings(commonSettings: _*)
  .dependsOn(actors)
lazy val actors = (project in file("actors"))
  .settings(commonSettings: _*)
  .settings(dependencyCheckSkip := false)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .settings(
    Seq(
      buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := "zio.actors",
      buildInfoObject := "BuildInfo",
    )
  )
  .settings(
    Compile / unmanagedJars ++=
      (file("deps/") * s"jinterface-${jinterface.value}.jar").classpath
  )
  .settings(
    dependencyCheckAssemblyAnalyzerEnabled := Some(false),
    dependencyCheckFormats := Seq("XML", "JSON")
  )

val root = (project in file("."))
  .aggregate(actors, domain, experiments, benchmarks)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .settings(
    scalacOptions ++= Seq("-Ymacro-annotations"),
    inThisBuild(
      List(
        organization := "com.cloudant",
      )
    ),
    name := "ziose",
    unmanagedBase := baseDirectory.value / "deps"
  )
