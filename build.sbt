ThisBuild / version := "1.0.0"
ThisBuild / scalaVersion := "3.5.1"
ThisBuild / organization := "com.aiplatform"

lazy val root = (project in file("."))
  .settings(
    name := "AI-Platform",
    libraryDependencies ++= Seq(
      // ScalaFX
      "org.scalafx" %% "scalafx" % "20.0.0-R31",
//      "org.slf4j" % "slf4j-simple" % "2.0.16",
      "ch.qos.logback" % "logback-classic" % "1.5.11",
      // Pekko
      "org.apache.pekko" %% "pekko-actor-typed" % "1.1.0",
      "org.apache.pekko" %% "pekko-slf4j" % "1.1.0",
      "org.apache.pekko" %% "pekko-http" % "1.1.0",
      "org.apache.pekko" %% "pekko-stream" % "1.1.0",


      // uPickle для JSON
      "com.lihaoyi" %% "upickle" % "4.0.2",
      // STTP Client v3
      "com.softwaremill.sttp.client3" %% "core" % "3.10.1",
      "com.softwaremill.sttp.client3" %% "pekko-http-backend" % "3.10.1",
      "com.softwaremill.sttp.client3" %% "circe" % "3.10.1",
      //scalatest
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
      // circe
      "io.circe" %% "circe-core" % "0.14.7",
      "io.circe" %% "circe-generic" % "0.14.7",
      "io.circe" %% "circe-parser" % "0.14.10",
      // JavaFX ARM dependencies
//      "org.openjfx" % "javafx-controls" % "23.0.1" classifier "linux",
//      "org.openjfx" % "javafx-fxml" % "23.0.1" classifier "linux",
//      "org.openjfx" % "javafx-media" % "23.0.1" classifier "linux",
//      "org.openjfx" % "javafx-base" % "23.0.1" classifier "linux",
//      "org.openjfx" % "javafx-graphics" % "23.0.1" classifier "linux"
      "org.openjfx" % "javafx-controls" % "20.0.2" classifier "linux",
      "org.openjfx" % "javafx-fxml" % "20.0.2" classifier "linux",
      "org.openjfx" % "javafx-media" % "20.0.2" classifier "linux",
      "org.openjfx" % "javafx-base" % "20.0.2" classifier "linux",
      "org.openjfx" % "javafx-graphics" % "20.0.2" classifier "linux"
    ),

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    Compile / mainClass := Some("com.aiplatform.app.MainApp"),
    fork := true,
    javaOptions ++= Seq(
//      "--module-path", "/path/to/javafx-sdk-23.0.1/lib",
      "--module-path", "/opt/javafx-sdk-20.0.2/lib",
      "--add-modules=javafx.controls,javafx.fxml,javafx.media,javafx.base,javafx.graphics",
      "--add-opens=javafx.graphics/com.sun.javafx.css=ALL-UNNAMED",
      "-XX:+UseZGC",
      "-Xmx512m"
    )
  )