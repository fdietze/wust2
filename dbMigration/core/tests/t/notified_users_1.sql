BEGIN;
SELECT plan(16);

-- suppress cascade notices from cleanup()
SET client_min_messages TO WARNING;

create or replace function user_to_uuid(id varchar(2)) returns uuid as $$
    select ('05e200' || id || '-0000-0000-0000-000000000000')::uuid;
$$ language sql IMMUTABLE;

create or replace function node_to_uuid(id varchar(2)) returns uuid as $$
    select ('90de00' || id || '-0000-0000-0000-000000000000')::uuid;
$$ language sql IMMUTABLE;

CREATE or replace FUNCTION insert_uuid_node(nid uuid, level accesslevel, data jsonb default '{}'::jsonb, role jsonb default '{"type": "Message"}'::jsonb) RETURNS void AS $$
    INSERT INTO node (id, data, role, accesslevel)
        VALUES (nid, data, role, level)
        on conflict(id) do update set accesslevel = excluded.accesslevel, data = excluded.data, role = excluded.data;
$$ language sql;

CREATE or replace FUNCTION node(nid varchar(2), level accesslevel default 'readwrite'::accesslevel, role jsonb default '{"type": "Message"}'::jsonb) RETURNS void AS $$
begin
    INSERT INTO node (id, data, role, accesslevel)
        VALUES (node_to_uuid(nid), jsonb_build_object('type', 'PlainText', 'content', node_to_uuid(nid)), role, level)
        on conflict(id) do update set accesslevel = excluded.accesslevel, data = excluded.data, role = excluded.role;
end
$$ language plpgsql;

CREATE or replace FUNCTION usernode(id varchar(6)) RETURNS void AS $$
begin
    perform insert_uuid_node(user_to_uuid(id), 'restricted', jsonb_build_object('type', 'User', 'name', id, 'isImplicit', false, 'revision', 0));
end
$$ language plpgsql;


CREATE or replace FUNCTION member(nodeid varchar(2), userid varchar(2), level accesslevel default 'readwrite') RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Member', 'level', level), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION member(nodeid uuid, userid varchar(2), level accesslevel default 'readwrite') RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( nodeid, jsonb_build_object('type', 'Member', 'level', level), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION invite(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Invite'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION author(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Author'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION expanded(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Expanded'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION child(parentid varchar(2), childid varchar(2), deletedAt timestamp default null) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(parentid), jsonb_build_object('type', 'Child', 'deletedAt', (EXTRACT(EPOCH FROM deletedAt) * 1000)::bigint), node_to_uuid(childid) )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION child(parentid uuid, childid uuid, deletedAt timestamp default null) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( parentid, jsonb_build_object('type', 'Child', 'deletedAt', (EXTRACT(EPOCH FROM deletedAt) * 1000)::bigint), childid )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION notify(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES (node_to_uuid(nodeid), jsonb_build_object('type', 'Notify'), user_to_uuid(userid))
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION pinned(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Pinned'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION assigned(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES (node_to_uuid(nodeid), jsonb_build_object('type', 'Assigned'), user_to_uuid(userid))
        ON CONFLICT(sourceid,(data->>'type'),coalesce(data->>'key', ''),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;


CREATE or replace FUNCTION cleanup() RETURNS void AS $$
begin
    truncate node cascade;
end
$$ language plpgsql;


-- for comparing arrays element-wise
CREATE or replace FUNCTION array_sort(anyarray) RETURNS anyarray AS $$
    SELECT array_agg(x order by x) FROM unnest($1) x;
$$ LANGUAGE 'sql';






-- request empty page
select cleanup();

-- test case 1
select usernode('11');
select node('11', 'readwrite');
select notify('11', '11');

SELECT set_eq( --1
    $$
        select * from notified_users_at_deepest_node(array[node_to_uuid('11')]::uuid[])
    $$,
    $$ values
        (user_to_uuid('11'), array[node_to_uuid('11')], node_to_uuid('11'))
    $$
);

-- test case 2
select cleanup();
select usernode('21');
select node('21', 'restricted');
select notify('21', '21');

SELECT is_empty( --2
    $$ select * from notified_users_at_deepest_node(array[node_to_uuid('21')]::uuid[]) $$
);

-- test case 3
select cleanup();
select usernode('31');
select node('31', 'restricted');
select member('31', '31', 'readwrite');
select notify('31', '31');

SELECT set_eq( --3
    $$
        select * from notified_users_at_deepest_node(array[node_to_uuid('31')]::uuid[])
    $$,
    $$ values
        (user_to_uuid('31'), array[node_to_uuid('31')], node_to_uuid('31'))
    $$
);

-- test case 4,5
select cleanup();
select usernode('41');
select node('41', 'restricted');
select node('42', 'restricted');
select child('42', '41');
select member('41', '41', 'readwrite');
select notify('41', '41');

SELECT is_empty( --4
    $$ select * from notified_users_at_deepest_node(array[node_to_uuid('42')]::uuid[]) $$
);
SELECT set_eq( --5
    $$
        select * from notified_users_at_deepest_node(array[node_to_uuid('41')]::uuid[])
    $$,
    $$ values
        (user_to_uuid('41'), array[node_to_uuid('41')], node_to_uuid('41'))
    $$
);

-- test case 6,7
select cleanup();
select usernode('51');
select node('51', 'restricted');
select node('52', 'restricted');
select child('52', '51');
select member('51', '51', 'readwrite');
select notify('52', '51');

SELECT is_empty( --6
    $$ select * from notified_users_at_deepest_node(array[node_to_uuid('52')]::uuid[]) $$
);
SELECT is_empty( --7
    $$ select * from notified_users_at_deepest_node(array[node_to_uuid('51')]::uuid[]) $$
);

-- test case 8,9
select cleanup();
select usernode('61');
select node('61');
select node('62', 'restricted');
select child('62', '61');
select notify('61', '61');

SELECT set_eq( --8
    $$
        select * from notified_users_at_deepest_node(array[node_to_uuid('61')]::uuid[])
    $$,
    $$ values
        (user_to_uuid('61'), array[node_to_uuid('61')], node_to_uuid('61'))
    $$
);
SELECT is_empty( --9
    $$ select * from notified_users_at_deepest_node(array[node_to_uuid('62')]::uuid[]) $$
);

-- test case 10
select cleanup();
select usernode('71');
select usernode('72');
select node('71', 'readwrite');
select node('72', 'restricted');
select node('73', 'readwrite');
select child('72', '71');
select child('73', '72');
select member('72', '72', 'readwrite');
select notify('73', '71');
select notify('73', '72');

SELECT set_eq( --10
    $$
        select * from notified_users_at_deepest_node(array[node_to_uuid('71')]::uuid[])
    $$,
    $$ values
        (user_to_uuid('72'), array[node_to_uuid('71')], node_to_uuid('73'))
    $$
);

-- test case 11
select cleanup();
select usernode('81');
select usernode('82');
select usernode('83');
select usernode('84');
select node('81');
select node('82', 'restricted');
select node('83');
select node('84', 'restricted');
select node('85');
select child('82', '81');
select child('83', '82');
select child('84', '83');
select child('85', '84');
select member('82', '81', 'readwrite');
select member('84', '81', 'readwrite');
select member('84', '82', 'readwrite');
select member('82', '84', 'readwrite');
select notify('85', '81');
select notify('85', '82');
select notify('85', '83');
select notify('83', '84');

SELECT set_eq( --11
    $$ select userid, array_sort(initial_nodes), subscribed_node from
            notified_users_at_deepest_node(array[
                node_to_uuid('81'),
                node_to_uuid('82'),
                node_to_uuid('83'),
                node_to_uuid('84'),
                node_to_uuid('85')
            ]::uuid[])
    $$
    ,
    $$ values
        (user_to_uuid('81'), array[node_to_uuid('81'), node_to_uuid('82'), node_to_uuid('83'), node_to_uuid('84'), node_to_uuid('85')], node_to_uuid('85')),
        (user_to_uuid('82'), array[node_to_uuid('83'), node_to_uuid('84'), node_to_uuid('85')], node_to_uuid('85')),
        (user_to_uuid('83'), array[node_to_uuid('85')], node_to_uuid('85')),
        (user_to_uuid('84'), array[node_to_uuid('81'), node_to_uuid('82'), node_to_uuid('83')], node_to_uuid('83'))
    $$
);

-- test case 12
select cleanup();
select usernode('91');
select usernode('92');
select node('91');
select node('92');
select node('93', 'restricted');
select node('94');
select node('95');
select child('95', '91');
select child('92', '91');
select child('93', '92');
select child('94', '93');
select member('93', '91', 'readwrite');
select notify('94', '91');
select notify('94', '92');
select notify('95', '92');

SELECT set_eq( --12
    $$ select userid, array_sort(initial_nodes), subscribed_node from
            notified_users_at_deepest_node(array[
                node_to_uuid('91'),
                node_to_uuid('92')
            ]::uuid[])
    $$
    ,
    $$ values
        (user_to_uuid('91'), array[node_to_uuid('91'), node_to_uuid('92')], node_to_uuid('94')),
        (user_to_uuid('92'), array[node_to_uuid('91')], node_to_uuid('95'))
    $$
);

-- test case 13
select cleanup();
select usernode('01');
select usernode('02');
select node('01');
select node('02');
select node('03', 'restricted');
select child('02', '01');
select child('03', '02');
select child('01', '03');
select member('03', '01');
select notify('01', '01');
select notify('01', '02');

SELECT set_eq( --13
    $$ select userid, array_sort(initial_nodes), subscribed_node from
            notified_users_at_deepest_node(array[
                node_to_uuid('01'),
                node_to_uuid('02'),
                node_to_uuid('03')
            ]::uuid[])
    $$
    ,
    $$ values
        (user_to_uuid('01'), array[node_to_uuid('01'), node_to_uuid('02'), node_to_uuid('03')], node_to_uuid('01')),
        (user_to_uuid('02'), array[node_to_uuid('01')], node_to_uuid('01'))
    $$
);

-- test case 14: respect deleted edges (ignore them)
select cleanup();
select usernode('01');
select usernode('02');
select node('01');
select node('02');
select node('03');
select child('02', '01');
select child('03', '01', (now_utc() - interval '1' hour)::timestamp);
select notify('02', '01');
select notify('03', '02');

SELECT set_eq( --13
    $$ select userid, array_sort(initial_nodes), subscribed_node from
            notified_users_at_deepest_node(array[
                node_to_uuid('01')
            ])
    $$
    ,
    $$ values
        (user_to_uuid('01'), array[node_to_uuid('01')], node_to_uuid('02'))
    $$
);

-- test case 14: avoid multiple notifications in multiple subscriptions single node
select cleanup();
select usernode('01');
select usernode('02');
select node('01');
select node('02');
select node('03');
select node('04');
select child('02', '01');
select child('03', '02');
select child('04', '03');
select notify('01', '01');
select notify('03', '01');
select notify('02', '02');
select notify('04', '02');

SELECT set_eq( --14
    $$ select userid, array_sort(initial_nodes), subscribed_node from
            notified_users_at_deepest_node(array[
                node_to_uuid('02')
            ]::uuid[])
    $$
    ,
    $$ values
        (user_to_uuid('01'), array[node_to_uuid('02')], node_to_uuid('03')),
        (user_to_uuid('02'), array[node_to_uuid('02')], node_to_uuid('02'))
    $$
);

-- test case 15: avoid multiple notifications in multiple subscriptions multiple nodes
select cleanup();
select usernode('01');
select usernode('02');
select node('01');
select node('02');
select node('03');
select node('04');
select child('02', '01');
select child('03', '02');
select child('04', '03');
select notify('01', '01');
select notify('03', '01');
select notify('02', '02');
select notify('04', '02');

SELECT set_eq( --15
    $$ select userid, array_sort(initial_nodes), subscribed_node from
            notified_users_at_deepest_node(array[
                node_to_uuid('01'),
                node_to_uuid('02'),
                node_to_uuid('03'),
                node_to_uuid('04')
            ]::uuid[])
    $$
    ,
    $$ values
        (user_to_uuid('01'), array[node_to_uuid('01')], node_to_uuid('01')),
        (user_to_uuid('01'), array[node_to_uuid('02'), node_to_uuid('03')], node_to_uuid('03')),
        (user_to_uuid('02'), array[node_to_uuid('01'), node_to_uuid('02')], node_to_uuid('02')),
        (user_to_uuid('02'), array[node_to_uuid('03'), node_to_uuid('04')], node_to_uuid('04'))
    $$
);


-- test case X
-- cleanup();
-- select usernode('A1');
-- select node('B1', 'restricted');
-- select node('B2', 'restricted');
-- select child('B2', 'B1');
-- select member('A1', 'B1', 'readwrite');
-- select before('B1', 'P1', 'N1');
-- select notify('B1', 'A1');


SELECT * FROM finish();
ROLLBACK;
