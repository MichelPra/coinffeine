name := "coinffeine"

version := "0.1-SNAPSHOT"

organization in ThisBuild := "com.coinffeine"

scalaVersion in ThisBuild := "2.10.3"

scalacOptions in ThisBuild ++= Seq("-deprecation", "-feature", "-language:postfixOps")

javacOptions in ThisBuild ++= Seq("-source", "1.7")

resolvers in ThisBuild ++= Seq(
  "nexus-releases" at "http://nexus.coinffeine.com:8086/nexus/content/repositories/releases",
  "bitcoinj" at "http://distribution.bitcoinj.googlecode.com/git/releases/",
  "sonatype-releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2/",
  "typesafe" at "http://repo.typesafe.com/typesafe/releases/"
)

libraryDependencies in ThisBuild ++= Seq(
  Dependencies.jodaTime,
  Dependencies.logbackClassic,
  Dependencies.logbackCore,
  Dependencies.mockito % "test",
  Dependencies.scalatest % "test",
  Dependencies.slf4j
)
