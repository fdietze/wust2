// build
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.29")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.15.0-0.6")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.19")

// scalablytyped
resolvers += Resolver.bintrayRepo("oyvindberg", "ScalablyTyped")
addSbtPlugin("org.scalablytyped" % "sbt-scalablytyped" % "201910230530")

// workflow
addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
/* addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.2") */
/* addSbtPlugin("org.wartremover" % "sbt-wartremover-contrib" % "1.3.1") */

// deployment
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.0.0")
addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.5.0")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.7")
/* addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1") */
/* addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.2.6") */

// android
// addSbtPlugin("org.scala-android" % "sbt-android" % "1.7.10")
// addSbtPlugin("org.scala-android" % "sbt-android-gms" % "0.4")
