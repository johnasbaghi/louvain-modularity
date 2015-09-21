name := "Louvain Modularity"

version := "0.0.1"

scalaVersion := "2.11.6"

libraryDependencies += "org.apache.spark" %% "spark-core" % "1.5.0"

libraryDependencies += "org.apache.spark" %% "spark-graphx" % "1.5.0"

libraryDependencies += "net.sf.opencsv" % "opencsv" % "2.3"

resolvers += "Akka Repository" at "http://repo.akka.io/releases/"

// http://stackoverflow.com/questions/28612837/spark-classnotfoundexception-when-running-hello-world-example-in-scala-2-11
fork := true
