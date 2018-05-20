BEGIN;
SELECT plan(1);

create temporary table visited (id varchar(36) NOT NULL) on commit drop;
create unique index on visited (id);

INSERT INTO post (id, content, author, created, modified) VALUES ('bla', '{}'::jsonb, 1, NOW(), NOW());
COPY "user" (id, name, channelpostid) FROM stdin;
U1	U1	bla
\.

COPY rawpost (id, content, isdeleted, author) FROM stdin;
1	{}	f	U1
2	{}	f	U1
\.

COPY rawconnection (sourceid, targetid, label) FROM stdin;
2	1	2
\.

COPY membership (userid, postid) FROM stdin;
U1	1
\.

-- no membership exists, therefore not allowed to see anything
SELECT cmp_ok(readable_posts('U1', array['1', '2']), '=', array['2', '1']::varchar(36)[]);

SELECT * FROM finish();
ROLLBACK;
