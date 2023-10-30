ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "2.13.12"

updateOptions := updateOptions.value.withCachedResolution(true)

val versions: Map[String, String] = Map(
  "zio"         -> "2.0.18",
  "zio.nio"     -> "2.0.2",
  "zio.config"  -> "4.0.0-RC16",
  "zio.logging" -> "2.1.14",
  "zio.metrics" -> "2.0.1",
  "lucene"      -> "4.6.1-cloudant1"
)

def jinterface = Def.setting {
  sys.env.getOrElse("JINTERFACE_VSN", sys.error("Please define JINTERFACE_VSN"))
}

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

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    // The single % is for java libraries
    // the %% appends the version of scala used, and should be used for scala libraries;
    // the %%% is for scala-js (and scala native).
    "dev.zio"       %% "zio"                    % versions("zio"),
    "dev.zio"       %% "zio-macros"             % versions("zio"),
    "dev.zio"       %% "zio-nio"                % versions("zio.nio"),
    "dev.zio"       %% "zio-metrics-dropwizard" % versions("zio.metrics"),
    "dev.zio"       %% "zio-streams"            % versions("zio"),
    "dev.zio"       %% "zio-logging"            % versions("zio.logging"),
    "dev.zio"       %% "zio-logging-slf4j"      % versions("zio.logging"),
    "dev.zio"       %% "zio-config"             % versions("zio.config"),
    "dev.zio"       %% "zio-config-magnolia"    % versions("zio.config"),
    "dev.zio"       %% "zio-config-refined"     % versions("zio.config"),
    "dev.zio"       %% "zio-config-typesafe"    % versions("zio.config"),
    "dev.zio"       %% "zio-config-yaml"        % versions("zio.config"),
    "dev.zio"       %% "zio-test"               % versions("zio") % Test,
    "dev.zio"       %% "zio-test-junit"         % versions("zio") % Test,
    "org.slf4j"      % "slf4j-nop"              % "2.0.9",
    "junit"          % "junit"                  % "4.13.2",
    "com.github.sbt" % "junit-interface"        % "0.13.3"        % Test
  ),
  coverageEnabled                        := true,
  testFrameworks                         := Seq(new TestFramework("com.novocode.junit.JUnitFramework")),
  dependencyCheckAssemblyAnalyzerEnabled := Some(false),
  dependencyCheckFormats                 := Seq("XML", "JSON"),
  scalacOptions ++= Seq("-Ywarn-unused:imports")
)

lazy val core = (project in file("core"))
  .settings(commonSettings *)
  .settings(dependencyCheckSkip := false)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .settings(
    Seq(
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := "ziose.core",
      buildInfoObject  := "BuildInfo"
    )
  )
  .settings(
    Compile / unmanagedJars ++=
      (file("deps/") * s"jinterface-${jinterface.value}.jar").classpath
  )
  .settings(
    dependencyCheckAssemblyAnalyzerEnabled := Some(false),
    dependencyCheckFormats                 := Seq("XML", "JSON")
  )
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )

lazy val benchmarks = (project in file("benchmarks"))
  .settings(commonSettings *)
  .dependsOn(core)
lazy val otp = (project in file("otp"))
  .settings(commonSettings *)
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .dependsOn(core)
  .dependsOn(test % "test->test")
lazy val scalang = (project in file("scalang"))
  .settings(commonSettings *)
  .dependsOn(core)
lazy val clouseau = (project in file("clouseau"))
  .settings(commonSettings *)
  .settings(
    resolvers += "cloudant-repo" at "https://maven.cloudant.com/repo/",
    libraryDependencies ++= luceneComponents
  )
  .dependsOn(core)
  .dependsOn(scalang)
  .dependsOn(otp)
  .dependsOn(test % "test->test")

lazy val test = (project in file("test"))
  .settings(commonSettings *)
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature")
  )
  .dependsOn(core)

lazy val root = (project in file("."))
  .aggregate(benchmarks, core, clouseau, test, otp)
  .enablePlugins(plugins.JUnitXmlReportPlugin)
  .settings(
    scalacOptions ++= Seq("-Ymacro-annotations", "-Ywarn-unused:imports"),
    inThisBuild(List(organization := "com.cloudant")),
    name          := "ziose",
    unmanagedBase := baseDirectory.value / "deps"
  )
  .settings(
    Compile / console / scalacOptions -= "-Ywarn-unused:imports"
  )
  .dependsOn(core)
