name := "pacman"
    
version := "1.0"

description := "The Scaled Package Manager"

licenses := Seq("New BSD" -> url("https://raw.githubusercontent.com/scaled/pacman/master/LICENSE"))

scalaVersion := "2.11.7"

autoScalaLibrary := false

libraryDependencies ++= Seq(
  "com.samskivert" % "mfetcher" % "1.0.5",
  "junit" % "junit" % "4.12" % "test",
  "org.scala-lang" % "scala-library" % scalaVersion.value % "test",
  "com.novocode" % "junit-interface" % "0.11" % "test"
)
