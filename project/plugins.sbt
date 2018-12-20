// build
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.26")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.13.1")

// workflow
addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
// addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.0.0")
// addSbtPlugin("org.wartremover" % "sbt-wartremover-contrib" % "1.0.0")

// deployment
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.0.0")
addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.5.0")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.7")
/* addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1") */
/* addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.2.6") */

// android
// addSbtPlugin("org.scala-android" % "sbt-android" % "1.7.10")
// addSbtPlugin("org.scala-android" % "sbt-android-gms" % "0.4")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.1.0-M9")
