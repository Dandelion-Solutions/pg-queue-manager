import sbtrelease.ReleaseStateTransformations._

val ds = "solutions.dandelion"
val groupId = s"$ds.pqm"

lazy val pqm = (project in file("."))
  .settings(
    name := "pg-queue-manager",
    description := "Postgres Queue Manager",

    scalaVersion := "2.13.7",
    scalacOptions ++= Seq(
      "-feature", "-unchecked", "-deprecation", "-explaintypes", "-encoding", "UTF8",
      "-Xlint",
      //"-Xfatal-warnings", // Disabled because of https://github.com/scala/bug/issues/10134
      "-Xlint:-unused",
      "-language:implicitConversions",
      "-language:reflectiveCalls",
      "-language:higherKinds",
      "-language:postfixOps",
      "-language:existentials"
    ),

    libraryDependencies ++= Seq(
      "com.mysterria.lioqu"           %% "lioqu-utils-commons"          % "1.4",
      "com.mysterria.lioqu"           %% "lioqu-db-postgres"            % "1.4",
      "joda-time"                     %  "joda-time"                    % "2.10.10", // Here we use the same version as used in slick-pg (https://github.com/tminglei/slick-pg/blob/v0.19.7/build.sbt)

      "com.typesafe.scala-logging"    %% "scala-logging"          % "3.9.5",
      "ch.qos.logback"                %  "logback-classic"        % "1.1.11",
      "ch.qos.logback"                %  "logback-core"           % "1.1.11",
      "org.codehaus.janino"           %  "janino"                 % "2.7.8",
      "com.github.maricn"             %  "logback-slack-appender" % "1.6.1",

      "org.postgresql"                %  "postgresql"             % "42.7.1",
      "com.typesafe"                  %  "config"                 % "1.4.3",
      "org.apache.commons"            %  "commons-lang3"          % "3.14.0",
      "org.apache.commons"            %  "commons-dbcp2"          % "2.11.0",

      "org.playframework"             %% "play-json"              % "3.0.1",
      "org.quartz-scheduler"          %  "quartz"                 % "2.3.2",

      "org.scalatest"                 %% "scalatest"              % "3.2.17"   % Test,
      "org.mockito"                   % "mockito-core"            % "5.9.0"    % Test
    ),

    // Release related settings
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := groupId,

    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,              // : ReleaseStep
      inquireVersions,                        // : ReleaseStep
      runClean,                               // : ReleaseStep
      runTest,                                // : ReleaseStep
      setReleaseVersion,                      // : ReleaseStep
      commitReleaseVersion,                   // : ReleaseStep, performs the initial git checks
      tagRelease,                             // : ReleaseStep
      //    publishArtifacts,                       // : ReleaseStep, checks whether `publishTo` is properly set up
      releaseStepTask(packageBin in Rpm),
      setNextVersion,                         // : ReleaseStep
      commitNextVersion,                      // : ReleaseStep
      pushChanges                             // : ReleaseStep, also checks that an upstream branch is properly configured
    ),

    {
      val confDirectory = baseDirectory(_ / "src" / "main" / "resources")
      mappings in Universal ++= {
        val confDirLen = confDirectory.value.getCanonicalPath.length
        (confDirectory.value ** "application.conf").get.map(f => f -> ("conf/" + f.getCanonicalPath.substring(confDirLen) + ".example")) ++
          (confDirectory.value ** "logback.xml").get.map(f => f -> ("conf/" + f.getCanonicalPath.substring(confDirLen) + ".example"))
      }
    },

    rpmVendor := ds,
    rpmLicense := Some("MIT"),

    bashScriptExtraDefines ++= Seq(
      // Memory
      """addJava -Xmx$JMEM_MAX -Xms$JMEM_START""",
      """addJava -XX:+UseG1GC -XX:MaxGCPauseMillis=100""",
      """addJava -Xlog:gc*=info:file=${app_home}/../logs/gc.log:tags,time,uptime,level""",
      // Errors
      """addJava -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${app_home}/../logs""",
      """addJava -XX:ErrorFile=${app_home}/../logs/java_error%p.log""",
      // Configs
      """addJava -Dconfig.file=${app_home}/../conf/application.conf""",
      """addJava -Dlogback.configurationFile=${app_home}/../conf/logback.xml""",
    )
  )
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(LauncherJarPlugin)
  .enablePlugins(JavaServerAppPackaging)
  .enablePlugins(SystemdPlugin)
  .enablePlugins(RpmPlugin)
//  .addCommandAlias("dist", "universal:package-xz-tarball")
