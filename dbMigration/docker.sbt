
def dockerDbMigration(name: String) = Seq(
  dockerfile in docker := {
    val postgresHost = s"$name-postgres"
    new Dockerfile {
      from("dhoer/flyway:4.2.0-alpine")
      run("adduser", "user", "-D", "-u", "1000")
      run("chown", "-R", "user:user", "/flyway")
      user("user")
      copy(baseDirectory(_ / "sql").value, "/flyway/sql")
      copy(baseDirectory(_ / "../flyway-await-postgres.sh").value, s"/flyway/flyway-await-postgres.sh")
      entryPoint("/flyway/flyway-await-postgres.sh", postgresHost)
    }
  },

  imageNames in docker :=
    ImageName(namespace = Some("woost"), repository = s"db-migration", tag = Some(name)) ::
    ImageName(namespace = Some("woost"), repository = s"db-migration", tag = Some(version.value + "-" + name)) ::
    Nil
)

lazy val dbMigration = project.in(file("."))
  .aggregate(dbMigrationWust)//, dbMigrationGithub)
lazy val dbMigrationWust = project.in(file("wust"))
  .enablePlugins(DockerPlugin)
  .settings(dockerDbMigration("wust"))
//lazy val dbMigrationGithub = project.in(file("github"))
//  .enablePlugins(DockerPlugin)
//  .settings(dockerDbMigration("github"))
