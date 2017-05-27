name := "wust"

enablePlugins(GitVersioning)
git.useGitDescribe := true
git.baseVersion := "0.1.0"
git.uncommittedSignifier := None // TODO: appends SNAPSHOT to version, but is always(!) active.

// scala.tools.asm.tree.analysis.AnalyzerException: While processing backend/Server$$anonfun$$nestedInanonfun$router$1$1.$anonfun$applyOrElse$3
scalaVersion in ThisBuild := "2.11.11" //TODO: migrate to 2.12 when this PR is merged: https://github.com/getquill/quill/pull/617

lazy val commonSettings = Seq(
  resolvers ++= (
    ("Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots") ::
    Nil),

  // do not run tests in assembly command
  test in assembly := {},

  // watch managed library dependencies (only works with scala 2.11 currently)
  watchSources ++= (managedClasspath in Compile).map(_.files).value,
  scalacOptions ++=
    "-encoding" :: "UTF-8" ::
    "-unchecked" ::
    "-deprecation" ::
    "-explaintypes" ::
    "-feature" ::
    "-language:_" ::
    "-Ywarn-unused" ::
    Nil

// wartremoverErrors ++= (
//   // http://www.wartremover.org/doc/warts.html
//   // Wart.Equals :: // TODO: rather have a compiler plugin to transform == to ===
//   // Wart.FinalCaseClass :: //TODO: rather have a compiler plugin to add "final"
//   // Wart.LeakingSealed ::
//   ContribWart.SomeApply :: //TODO: rather have a compiler plugin to transform Some(..) to Option(..) ?
//   // Wart.OldTime ::
//   // Wart.AsInstanceOf ::
//   Wart.Null ::
//   Nil
// ),
// wartremoverExcluded ++= (
//   //TODO: these files are ignored because scribe uses Some
//   baseDirectory.value / "src" / "main" / "scala" / "Dispatcher.scala" ::
//   baseDirectory.value / "src" / "main" / "scala" / "Server.scala" ::
//   Nil
// )
)

lazy val isCI = sys.env.get("CI").isDefined // set by travis

lazy val config = file("config")
lazy val configSettings = Seq(
  unmanagedResourceDirectories in Runtime += config,
  unmanagedResourceDirectories in Compile += config)

lazy val root = project.in(file("."))
  .aggregate(apiJS, apiJVM, database, backend, frameworkJS, frameworkJVM, frontend, graphJS, graphJVM, utilJS, utilJVM, systemTest, nginx, dbMigration)
  .settings(
    publish := {},
    publishLocal := {},

    addCommandAlias("clean", "; root/clean; assets/clean; workbench/clean"),

    addCommandAlias("devwatch", "~; backend/re-start; workbench/assets"),
    addCommandAlias("dev", "; project root; devwatch"),
    addCommandAlias("devfwatch", "~workbench/assets"),
    addCommandAlias("devf", "; project root; backend/re-start; devfwatch"),

    addCommandAlias("testJS", "; utilJS/test; graphJS/test; frameworkJS/test; apiJS/test; frontend/test"),
    addCommandAlias("testJSOpt", "; set scalaJSStage in Global := FullOptStage; testJS"), // TODO: also run optimized tests in productionMode. https://gitter.im/scala-js/scala-js?at=58ef8672ad849bcf427e96ab
    addCommandAlias("testJVM", "; utilJVM/test; graphJVM/test; frameworkJVM/test; apiJVM/test; database/test; backend/test"),

    watchSources ++= (watchSources in workbench).value)

val akkaVersion = "2.4.17"
val akkaHttpVersion = "10.0.5"
val specs2Version = "3.8.9"
val scalaTestVersion = "3.0.3"
val mockitoVersion = "2.7.22"
val paradiseVersion = "3.0.0-M8"
val scalazVersion = "7.2.13"
val boopickleVersion = "1.2.6"

lazy val util = crossProject
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= (
      "org.scalatest" %%% "scalatest" % scalaTestVersion % "test" ::
      Nil))
  .jsSettings(
    libraryDependencies ++= (
      "com.lihaoyi" %%% "scalatags" % "0.6.5" ::
      Nil))
lazy val utilJS = util.js
lazy val utilJVM = util.jvm

lazy val framework = crossProject
  .dependsOn(util)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= (
      "com.lihaoyi" %%% "autowire" % "0.2.6" ::
      "io.suzaku" %%% "boopickle" % boopickleVersion ::
      "org.scalatest" %%% "scalatest" % scalaTestVersion % "test" ::
      Nil))
  .jvmSettings(
    libraryDependencies ++= (
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion ::
      "com.typesafe.akka" %% "akka-actor" % akkaVersion ::
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test" ::
      "com.outr" %% "scribe" % "1.4.1" ::
      // "com.typesafe.akka" %% "akka-slf4j" % akkaVersion ::
      // "com.outr" %% "scribe-slf4j" % "1.3.2" :: //TODO
      Nil))
  .jsSettings(
    libraryDependencies ++= (
      "org.scala-js" %%% "scalajs-dom" % "0.9.1" ::
      Nil))

lazy val frameworkJS = framework.js
lazy val frameworkJVM = framework.jvm

lazy val ids = crossProject
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= (
      "org.scalaz" %%% "scalaz-core" % scalazVersion ::
      Nil))
lazy val idsJS = ids.js
lazy val idsJVM = ids.jvm

lazy val graph = crossProject
  .settings(commonSettings)
  .dependsOn(ids)
  .settings(
    libraryDependencies ++= (
      "org.scalatest" %%% "scalatest" % scalaTestVersion % "test" ::
      Nil))
  .dependsOn(util)
lazy val graphJS = graph.js
lazy val graphJVM = graph.jvm

lazy val api = crossProject.crossType(CrossType.Pure)
  .dependsOn(graph)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= (
      "io.suzaku" %%% "boopickle" % boopickleVersion ::
      Nil))
lazy val apiJS = api.js
lazy val apiJVM = api.jvm

lazy val database = project
  .settings(commonSettings)
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .dependsOn(idsJVM)
  .settings(
    libraryDependencies ++=
      "io.getquill" %% "quill-async-postgres" % "1.2.1" ::
      "org.scalatest" %%% "scalatest" % scalaTestVersion % "test,it" ::
      "com.outr" %% "scribe" % "1.4.1" ::
      Nil
  // parallelExecution in IntegrationTest := false
  )

lazy val backend = project
  .settings(commonSettings)
  .dependsOn(frameworkJVM, apiJVM, database)
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(configSettings)
  .settings(
    addCompilerPlugin("org.scalameta" % "paradise" % paradiseVersion cross CrossVersion.full),
    libraryDependencies ++=
      "org.typelevel" %% "cats" % "0.9.0" ::
      "com.roundeights" %% "hasher" % "1.2.0" ::
      "org.mindrot" % "jbcrypt" % "0.4" ::
      "io.igl" %% "jwt" % "1.2.0" ::
      "javax.mail" % "javax.mail-api" % "1.5.6" ::
      "com.sun.mail" % "javax.mail" % "1.5.6" ::
      "com.roundeights" %% "hasher" % "1.2.0" ::
      "org.mindrot" % "jbcrypt" % "0.4" ::
      "com.github.cornerman" %% "derive" % "0.1.0-SNAPSHOT" ::
      "com.github.cornerman" %% "delegert" % "0.1.0-SNAPSHOT" ::
      "com.github.cornerman" %% "autoconfig" % "0.1.0-SNAPSHOT" ::
      "org.mockito" % "mockito-core" % mockitoVersion % "test" ::
      "org.scalatest" %% "scalatest" % scalaTestVersion % "test,it" ::
      Nil)

lazy val frontend = project
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)
  .dependsOn(frameworkJS, apiJS, utilJS)
  .settings(commonSettings)
  .settings(
    addCompilerPlugin("org.scalameta" % "paradise" % paradiseVersion cross CrossVersion.full),
    libraryDependencies ++= (
      ("com.timushev" %%% "scalatags-rx" % "0.3.0" excludeAll (ExclusionRule(artifact = "scalarx"), ExclusionRule(artifact = "scalatags"))) ::
      "com.lihaoyi" %%% "scalatags" % "0.6.5" ::
      "com.github.fdietze" %%% "scalarx" % "0.3.3-SNAPSHOT" ::
      "com.github.fdietze" %%% "vectory" % "0.1.0" ::
      "com.github.fdietze" %%% "scala-js-d3v4" % "0.1.0-SNAPSHOT" ::
      "org.scalameta" %%% "scalameta" % "1.7.0" ::
      "com.github.cornerman" %% "derive" % "0.1.0-SNAPSHOT" ::
      "com.github.cornerman" %% "delegert" % "0.1.0-SNAPSHOT" ::
      "org.scalatest" %%% "scalatest" % scalaTestVersion % "test" ::
      Nil),
    jsDependencies += RuntimeDOM,
    scalaJSOptimizerOptions in fastOptJS ~= { _.withDisableOptimizer(true) }, // disable optimizations for better debugging experience
    scalaJSOptimizerOptions in (Compile, fullOptJS) ~= { _.withUseClosureCompiler(false) }, // TODO: issue with fullOpt: https://github.com/scala-js/scala-js/issues/2786
    useYarn := true, // instead of npm
    enableReloadWorkflow := true, // https://scalacenter.github.io/scalajs-bundler/reference.html#reload-workflow
    emitSourceMaps := true,
    emitSourceMaps in fullOptJS := false,
    npmDevDependencies in Compile ++= (
      "compression-webpack-plugin" -> "0.3.1" ::
      "brotli-webpack-plugin" -> "0.2.0" ::
      "webpack-closure-compiler" -> "2.1.4" ::
      Nil),
    webpackConfigFile in fullOptJS := Some(baseDirectory.value / "scalajsbundler.config.js") // renamed due to https://github.com/scalacenter/scalajs-bundler/issues/123
  )

lazy val DevWorkbenchPlugins = if (isCI) Seq.empty else Seq(WorkbenchPlugin)
lazy val DevWorkbenchSettings = if (isCI) Seq.empty else Seq(
  //TODO: deprecation-warning: https://github.com/sbt/sbt/issues/1444
  refreshBrowsers <<= refreshBrowsers.triggeredBy(WebKeys.assets in Assets) //TODO: do not refresh if compilation failed
)

lazy val workbench = project
  .enablePlugins(SbtWeb, ScalaJSWeb, WebScalaJSBundlerPlugin)
  .enablePlugins(DevWorkbenchPlugins: _*)
  .settings(DevWorkbenchSettings: _*)
  .settings(
    // we have a symbolic link from src -> ../frontend/src
    // to correct the paths in the source-map
    scalaSource := baseDirectory.value / "src-not-found",

    devCommands in scalaJSPipeline ++= Seq("assets"), // build assets in dev mode
    unmanagedResourceDirectories in Assets += (baseDirectory in assets).value / "public", // include other assets

    scalaJSProjects := Seq(frontend),
    pipelineStages in Assets := Seq(scalaJSPipeline),

    watchSources += baseDirectory.value / "index.html",
    watchSources ++= (watchSources in assets).value)

lazy val assets = project
  .enablePlugins(SbtWeb, ScalaJSWeb, WebScalaJSBundlerPlugin)
  .settings(
    resourceGenerators in Assets += Def.task {
      val file = (resourceManaged in Assets).value / "version.txt"
      IO.write(file, version.value)
      Seq(file)
    },
    unmanagedResourceDirectories in Assets += baseDirectory.value / "public",
    scalaJSProjects := Seq(frontend),
    npmAssets ++= {
      // without dependsOn, the file list is generated before webpack does its thing.
      // Which would mean that generated files by webpack do not land in the pipeline.
      val assets = ((npmUpdate in Compile in frontend).dependsOn(webpack in fullOptJS in Compile in frontend).value ** "*.gz") +++ ((npmUpdate in Compile in frontend).dependsOn(webpack in fullOptJS in Compile in frontend).value ** "*.br")
      val nodeModules = (npmUpdate in (frontend, Compile)).value
      assets.pair(relativeTo(nodeModules))
    },
    pipelineStages in Assets := Seq(scalaJSPipeline)
  //TODO: minify html
  )

lazy val systemTest = project
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++=
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion % "it" ::
      "com.typesafe.akka" %% "akka-actor" % akkaVersion % "it" ::
      "org.specs2" %% "specs2-core" % specs2Version % "it" ::
      "org.seleniumhq.selenium" % "selenium-java" % "3.3.1" % "it" ::
      Nil,
    scalacOptions in Test ++= Seq("-Yrangepos") // specs2
  )

lazy val nginx = project
lazy val dbMigration = project
