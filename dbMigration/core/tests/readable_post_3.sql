BEGIN;
SELECT plan(1);

create temporary table visited (id varchar(36) NOT NULL) on commit drop;
create unique index on visited (id);

INSERT INTO post (id, content, author, created, modified) VALUES ('bla', '{}'::jsonb, 1, NOW(), NOW());
INSERT INTO post (id, content, author, created, modified) VALUES ('upid', '{}'::jsonb, 1, NOW(), NOW());
insert into "user" (id, name, revision, isimplicit, channelpostid, userpostid) values ('U1', 'U1', 0, false, 'bla', 'upid');
insert into "user" (id, name, revision, isimplicit, channelpostid, userpostid) values ('U2', 'U2', 0, false, 'bla', 'upid');

INSERT INTO post (id, content, author, created, modified)
        VALUES ('1', '{}'::jsonb, 'U1', NOW(), NOW())
        RETURNING (id, content, author, created, modified);

insert into membership(userid, postid) values ('U2', '1');

-- no membership exists, therefore not allowed to see anything
SELECT cmp_ok(readable_posts('U1', array['1']), '=', array[]::varchar(36)[]);

SELECT * FROM finish();
ROLLBACK;
