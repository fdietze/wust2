# Woost
[![Build Status](https://codebuild.eu-central-1.amazonaws.com/badges?uuid=eyJlbmNyeXB0ZWREYXRhIjoicDNUWjI3S0tjTlkrNTVmZnVEd0Q2eDhXUGxubER6dVQveEJNMjZxNFlGMS8wMTBScEF0UGptVlVJdlV6YWpZYnJUMi9DZ29Sd1h2NnEwdzkvaHh0Y3owPSIsIml2UGFyYW1ldGVyU3BlYyI6ImM5c2w4NnBMVmxBR00xdzgiLCJtYXRlcmlhbFNldFNlcmlhbCI6MX0%3D&branch=master)](https://eu-central-1.console.aws.amazon.com/codesuite/codebuild/projects/Woost/history?region=eu-central-1)
[![Coverage Status](https://coveralls.io/repos/github/woost/wust2/badge.svg)](https://coveralls.io/github/woost/wust2)
[![Join the chat at https://gitter.im/wust2/Lobby](https://badges.gitter.im/wust2/Lobby.svg)](https://gitter.im/wust2/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Goal: Scale communication and collaboration within large groups.
The core idea can be imagined as a mix of the expressiveness of mind-mapping, Wiki and realtime collaborative editing.

This project is in an early stage of development. You can already play around with the online version: https://wust.space. Current master is deployed automatically to https://wust.space:8443.

Contributions very welcome. Please ask questions and share ideas.

# Rough Architecture
A directed graph stored in postgres accessed via rpc-calls over websockets and binary serialization, visualized using reactive programming and a force-directed graph layout.

# Building blocks
* [scala](https://github.com/scala/scala)/[scala-js](https://github.com/scala-js/scala-js) (scala for backend, scala-js is scala compiled to javascript for the webApp. Allows to share code between both.)
* [nginx](https://github.com/nginx/nginx) (serving static assets, https, forwarding websocket connections to backend)
* [postgres](https://github.com/postgres/postgres) (relational database with views and stored procedures for graph traversal)
* [flyway](https://github.com/flyway/flyway) (database migrations)
* [quill](https://github.com/getquill/quill) (compile-time language integrated database queries for Scala)
* [akka](https://github.com/akka/akka) (message passing in backend, websocket server)
* [sloth](https://github.com/cornerman/sloth) (type safe rpc calls for implementing webApp-to-backend api)
* [mycelium](https://github.com/cornerman/mycelium) (request, response and events over websockets)
* [boopickle](https://github.com/suzaku-io/boopickle) (fast and boilerplate-free binary serialization, similar to protobuf. Used for webApp/backend communication)
* [outwatch](https://github.com/outwatch/outwatch) (UI-library for reactive programming bindings to DOM-Nodes)
* [d3](https://github.com/d3/d3) (visualization library, used for graph visualization and drag&drop)

# Development
Requirements:
* sbt
* docker, docker-compose
* node, yarn
* phantomjs
* gcc and make

_Note:_ `gcc/make` is not a direct requierement of this project, but some `npm` packages requiere a C compiler. You will most probably notice that if you get a runtime exception from `npm`.

Starting all needed services in docker (e.g. postgres with initialization) and run sbt with corresponding environment variables:
```
$ ./start sbt
```

In the sbt prompt, you can then start watching sources and recompile while developing:
```
> dev
```

If you are only developing the webApp, you can also skip recompilation of the backend:
```
> devf
```

Access wust via http://localhost:12345

The start script is the central script for developers.
From here, you can also run db migrations, access psql, run tests or start a production stack with test settings:
```
start < sbt [app], migrate [app], psql <options>, pgcli [app], pgdump, pgrestore <file>, pgclean, prod, prod.http, prod.slack, test, test.postgres, test.integration >
```

## Developing database migrations

Test migration in transaction, then rollback.
```sql
begin;

alter table ...;
drop table ...;

\d+ yourtable;
\dt public.*;

rollback;
```

Run sql from vim:
```vimscript
:w !docker exec -i devcore_postgres_1 psql -h localhost -U wust -p 5432
```

## Developing database tests
```bash
find dbMigration/core/{sql,tests} | entr -ncs 'sbt dbMigration/docker && ./start test.postgres'
```

## Watching and running particular tests
```sbt
~[project]/testOnly *[Suite or Pattern]* -- -z [name]
```
e.g.
```sbt
~graphJVM/testOnly *GraphSpec* -- -z "topological before"
```

## Database performance measurement
function stats (only activated in dev, requires `track_functions=all`)
```sql
select funcname, calls, total_time, total_time/calls as total_avg, self_time, self_time/calls as self_avg from pg_stat_user_functions order by self_time DESC;
```

to clean the stats:
```sql
select pg_stat_reset()
```

function explanation via auto explainer:
```sql
LOAD 'auto_explain';
SET auto_explain.log_min_duration = 0; -- if too many small queries are explained, raise this value
SET auto_explain.log_nested_statements = ON;
-- helpful for the visualizer
set auto_explain.log_format = json;
set auto_explain.log_verbose = true;
set auto_explain.log_buffers = true;
```
The explanations will appear in the log.

Explanation visualizer: http://tatiyants.com/pev

## watching database content
```bash
watch 'echo "SELECT * from node; select * from edge;" | docker exec -i devcore_postgres_1 psql -h localhost -U wust -p 5432'
```

## git bisect
```bash
git checkout master -- start; sed -i 's/5433/5432/' start; docker-compose -p devcore -f core/docker-compose.yml -f core/docker-compose.dev.yml up -d db-migration; echo "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" | docker exec -i devcore_postgres_1 psql -h localhost -U wust -p 5432; for m in $(ls dbMigration/core/sql -1v --color=none); do echo $m; cat dbMigration/core/sql/$m | docker exec -i devcore_postgres_1 psql -h localhost -U wust -p 5432; done; SOURCEMAPS=true EXTRASBTARGS="webApp/clean dev" ./start nsbt
```

Fully automated bisect if the error can be reproduced by a command with an exit code:
```
git bisect start [bad-commit] [good-commit]
git bisect run [command]
```

# Docker images
Build all docker images in project:
```
$ sbt docker
```

# Access/debug devserver from android
`build.sbt`:
```scala
webpackDevServerExtraArgs := Seq("--public")
```
and open firewall port in ```configuration.nix```:
```
networking.firewall.allowedTCPPorts = [ 12345 ];
```

The images are automatically published to docker.woost.space once a day from `master`.

# Deployment
Requirements:
* docker
* docker-compose

All used docker services are defined in `docker/services.yml` and can be configured with the following environment variables:
* **POSTGRES_PASSWORD**: a password for the postgres application user 'wust'
* **WUST_AUTH_SECRET**: a secret for signing JWT tokens
* **WUST_EMAIL_ADDRESS**: from address for sent email (optional)
* **WUST_SMTP_ENDPOINT**: smtp endpoint (optional)
* **WUST_SMTP_USER**: smtp username (optional)
* **WUST_SMTP_PASS**: smtp password (optional)
* **WUST_PUSH_SUBJECT**: subject (email) for sending push notifications to push service (optional)
* **WUST_PUSH_PUBLIC_KEY**: vapid public key (optional)
* **WUST_PUSH_PRIVATE_KEY**: vapid private key (optional)

The compose stack `docker/compose-prod.yml` is an example how to run wust in docker. Start the whole stack with docker-compose:
```
$ docker-compose --file <project>/compose-prod.yml up
```
# Deploy-Script
```bash
DOCKER_USERNAME=woost DOCKER_PASSWORD=xxx DOCKER_REGISTRY=docker.woost.space STAGING_URL=https://xxx ./manual-deploy
```
This will build all docker images and push them to `:latest`.

# App and Favicons
* https://realfavicongenerator.net
* in `webApp/assets`: `optipng -o7 strip all *.png`
* [`svgomg`](https://jakearchibald.github.io/svgomg)

# Example Usage of json Api

## login
```bash
$ curl localhost:8901/api/Auth/loginReturnToken -d '{"email":"a@a", "password": "hans"}'
"eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ3dXN0Iiwic3ViIjoiNVVEdVFjcERRQmk2YUVnOVFxdUxLTiIsImF1ZCI6WyJ3dXN0Il0sImV4cCI6MTU4NDU2NDU4MywibmJmIjoxNTUzMDI4NTgzLCJpYXQiOjE1NTMwMjg1ODMsInVzZXIiOnsiaWQiOiIyNDMxOTZhYS03ZDdlLTc2MDAtMmMyNi1lYzBmNjdjODQwZDUiLCJuYW1lIjoiaGFucyIsInJldmlzaW9uIjoxLCJ0eXBlIjoiUmVhbCJ9LCJ0eXBlIjoiVXNlckF1dGgifQ.M8I7LsfIITm-P4S3zhrdDe8qEzkKoCJzpmfhPMl9hho"
```bash

## getTasks
``` bash
$ curl -H "Authorization: eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ3dXN0Iiwic3ViIjoiNVVEdVFjcERRQmk2YUVnOVFxdUxLTiIsImF1ZCI6WyJ3dXN0Il0sImV4cCI6MTU4NDU2NDU4MywibmJmIjoxNTUzMDI4NTgzLCJpYXQiOjE1NTMwMjg1ODMsInVzZXIiOnsiaWQiOiIyNDMxOTZhYS03ZDdlLTc2MDAtMmMyNi1lYzBmNjdjODQwZDUiLCJuYW1lIjoiaGFucyIsInJldmlzaW9uIjoxLCJ0eXBlIjoiUmVhbCJ9LCJ0eXBlIjoiVXNlckF1dGgifQ.M8I7LsfIITm-P4S3zhrdDe8qEzkKoCJzpmfhPMl9hho" localhost:8901/api/Api/getTasks -d '{"parentId":"243196b9-f7dc-f101-40c6-8a827f5e7a7d"}'
```
