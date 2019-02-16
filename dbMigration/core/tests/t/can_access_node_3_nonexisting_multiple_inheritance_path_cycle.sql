BEGIN;
SELECT plan(18);

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
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION member(nodeid uuid, userid varchar(2), level accesslevel default 'readwrite') RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( nodeid, jsonb_build_object('type', 'Member', 'level', level), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION invite(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Invite'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION author(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Author'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION expanded(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Expanded'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION child(parentid varchar(2), childid varchar(2), deletedAt timestamp default null) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(parentid), jsonb_build_object('type', 'Child', 'deletedAt', (EXTRACT(EPOCH FROM deletedAt) * 1000)::bigint), node_to_uuid(childid) )
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE (data->>'type')::text <> 'Author'::text DO UPDATE SET data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION child(parentid uuid, childid uuid, deletedAt timestamp default null) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( parentid, jsonb_build_object('type', 'Child', 'deletedAt', (EXTRACT(EPOCH FROM deletedAt) * 1000)::bigint), childid )
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE (data->>'type')::text <> 'Author'::text DO UPDATE SET data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION notify(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES (node_to_uuid(nodeid), jsonb_build_object('type', 'Notify'), user_to_uuid(userid))
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE (data->>'type')::text <> 'Author'::text DO UPDATE SET data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION pinned(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES ( node_to_uuid(nodeid), jsonb_build_object('type', 'Pinned'), user_to_uuid(userid) )
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE (data->>'type')::text <> 'Author'::text DO UPDATE SET data = EXCLUDED.data;
end
$$ language plpgsql;

CREATE or replace FUNCTION assigned(nodeid varchar(2), userid varchar(2)) RETURNS void AS $$
begin
    INSERT INTO edge (sourceid, data, targetid)
        VALUES (node_to_uuid(nodeid), jsonb_build_object('type', 'Assigned'), user_to_uuid(userid))
        ON CONFLICT(sourceid,(data->>'type'),targetid) WHERE data->>'type' <> 'Author' DO UPDATE set data = EXCLUDED.data;
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



-- IMPORTANT:
-- exactly the same test cases as in GraphSpec
-- when changing things, make sure to change them for the Graph as well.


-- multiple inheritance: max wins
select cleanup();
select usernode('A1'); -- user
select node('B1', 'restricted'); -- node without permission
select node('C1', 'readwrite'); -- node with permission
select node('D1', NULL); -- node with permission multiple inheritance
select child('B1', 'D1'); -- inheritance happens via this child edge
select child('C1', 'D1'); -- inheritance happens via this child edge

SELECT cmp_ok(can_access_node(user_to_uuid('A1'), node_to_uuid('D1')), '=', true);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A1'), array[node_to_uuid('D1')]), '=', array[]::uuid[]);


-- long inheritance chain: readwrite
select cleanup();
select usernode('A2');
select node('B2', 'readwrite');
select node('C2', NULL);
select node('D2', NULL);
select child('B2', 'C2');
select child('C2', 'D2');

SELECT cmp_ok(can_access_node(user_to_uuid('A2'), node_to_uuid('D2')), '=', true);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A2'), array[node_to_uuid('D2')]), '=', array[]::uuid[]);

-- long inheritance chain: restricted
select cleanup();
select usernode('A3');
select node('B3', 'restricted');
select node('C3', NULL);
select node('D3', NULL);
select child('B3', 'C3');
select child('C3', 'D3');

SELECT cmp_ok(can_access_node(user_to_uuid('A3'), node_to_uuid('D3')), '=', false);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A3'), array[node_to_uuid('D3')]), '=', array[node_to_uuid('D3')]::uuid[]);

-- inheritance cycle: readwrite
select cleanup();
select usernode('A4');
select node('B4', 'readwrite');
select node('C4', NULL);
select node('D4', NULL);
select child('B4', 'C4');
select child('C4', 'D4');
select child('D4', 'C4');

SELECT cmp_ok(can_access_node(user_to_uuid('A4'), node_to_uuid('D4')), '=', true);
SELECT cmp_ok(can_access_node(user_to_uuid('A4'), node_to_uuid('C4')), '=', true);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A4'), array[node_to_uuid('D4')]), '=', array[]::uuid[]);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A4'), array[node_to_uuid('C4')]), '=', array[]::uuid[]);

-- inheritance cycle: restricted
select cleanup();
select usernode('A5');
select node('B5', 'restricted');
select node('C5', NULL);
select node('D5', NULL);
select child('B5', 'C5');
select child('C5', 'D5');
select child('D5', 'C5');

SELECT cmp_ok(can_access_node(user_to_uuid('A5'), node_to_uuid('D5')), '=', false);
SELECT cmp_ok(can_access_node(user_to_uuid('A5'), node_to_uuid('C5')), '=', false);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A5'), array[node_to_uuid('D5')]), '=', array[node_to_uuid('D5')]::uuid[]);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A5'), array[node_to_uuid('C5')]), '=', array[node_to_uuid('C5')]::uuid[]);


-- non-existing nodes
select cleanup();
select usernode('A6');

SELECT cmp_ok(can_access_node(user_to_uuid('A6'), node_to_uuid('D6')), '=', true);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A6'), array[node_to_uuid('D6')]), '=', array[]::uuid[]);


-- non-existing user
-- we assume that a user exist in any cases and therefore we do not handle this explicitly


-- inherit without any child
select cleanup();
select usernode('A7'); -- user
select node('B7', NULL); -- node with permission multiple inheritance

SELECT cmp_ok(can_access_node(user_to_uuid('A7'), node_to_uuid('B7')), '=', false);
SELECT cmp_ok(inaccessible_nodes(user_to_uuid('A7'), array[node_to_uuid('B7')]), '=', array[node_to_uuid('B7')]::uuid[]);



SELECT * FROM finish();
ROLLBACK;
