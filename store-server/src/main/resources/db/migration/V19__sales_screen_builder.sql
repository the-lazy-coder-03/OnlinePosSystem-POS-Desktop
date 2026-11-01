-- Store-wide sales-screen configuration. Reporting V19 under scripts/ is an unapplied reference.
CREATE UNIQUE INDEX store_identity_screen_scope ON pos.store_identity(branch_id,store_uuid);
CREATE TABLE pos.screen_definition (
 screen_id uuid PRIMARY KEY, branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
 name varchar(160) NOT NULL CHECK(length(trim(name))>0), template boolean NOT NULL, revision bigint NOT NULL DEFAULT 0
);
CREATE TABLE pos.screen_revision (
 screen_id uuid NOT NULL REFERENCES pos.screen_definition(screen_id), revision bigint NOT NULL CHECK(revision>0),
 document_text text NOT NULL CHECK(jsonb_typeof(document_text::jsonb)='object' AND document_text::jsonb->>'schemaVersion'='1'),
 document_hash char(64) NOT NULL, actor_id uuid NOT NULL REFERENCES pos.staff(staff_id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(screen_id,revision)
);
CREATE TABLE pos.store_screen_publication (
 branch_id integer PRIMARY KEY, store_id uuid NOT NULL UNIQUE, sequence bigint NOT NULL DEFAULT 0 CHECK(sequence>=0),
 screen_id uuid, revision bigint,
 FOREIGN KEY(branch_id,store_id) REFERENCES pos.store_identity(branch_id,store_uuid),
 FOREIGN KEY(screen_id,revision) REFERENCES pos.screen_revision(screen_id,revision)
);
CREATE TABLE pos.screen_publication_event (
 event_id uuid PRIMARY KEY, branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
 store_id uuid NOT NULL, sequence bigint NOT NULL CHECK(sequence>0), screen_id uuid NOT NULL, revision bigint NOT NULL,
 document_hash char(64) NOT NULL, published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(branch_id,sequence), FOREIGN KEY(screen_id,revision) REFERENCES pos.screen_revision(screen_id,revision)
);
CREATE TABLE pos.screen_command (
 command_id uuid PRIMARY KEY, branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id), actor_id uuid NOT NULL,
 terminal_identity varchar(512) NOT NULL, payload_hash char(64) NOT NULL, result jsonb NOT NULL
);
CREATE TABLE pos.screen_asset (
 asset_hash char(64) PRIMARY KEY, branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
 media_type varchar(30) NOT NULL CHECK(media_type IN ('image/png','image/jpeg')), content bytea NOT NULL CHECK(octet_length(content)<=524288)
);
CREATE TRIGGER screen_revision_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON pos.screen_revision FOR EACH STATEMENT EXECUTE FUNCTION pos.guard_audit_history();
CREATE TRIGGER screen_event_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON pos.screen_publication_event FOR EACH STATEMENT EXECUTE FUNCTION pos.guard_audit_history();
CREATE TRIGGER screen_asset_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON pos.screen_asset FOR EACH STATEMENT EXECUTE FUNCTION pos.guard_audit_history();
INSERT INTO pos.audit_event_type VALUES ('SALES_SCREEN_CHANGED','Configuration',false),('SALES_SCREEN_PUBLISHED','Configuration',false);
CREATE FUNCTION pos.seed_screen_permissions() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN
 IF NEW.name='MANAGER' THEN
 INSERT INTO pos.role_permission SELECT NEW.branch_id,NEW.name,p FROM unnest(ARRAY['module.sales_screen_builder','sales_screen.edit','sales_screen.publish','sales_screen.manage_templates']) p ON CONFLICT DO NOTHING;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER screen_permissions AFTER INSERT ON pos.rbac_role FOR EACH ROW EXECUTE FUNCTION pos.seed_screen_permissions();
INSERT INTO pos.role_permission SELECT branch_id,name,p FROM pos.rbac_role CROSS JOIN unnest(ARRAY['module.sales_screen_builder','sales_screen.edit','sales_screen.publish','sales_screen.manage_templates']) p WHERE name='MANAGER' ON CONFLICT DO NOTHING;

CREATE FUNCTION pos.write_sales_screen(proof text, terminal text, command uuid, operation text, value jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer; store uuid; who uuid; owner_user boolean; permitted text; payload_hash text; old pos.screen_command;
 sid uuid; wanted bigint; current_revision bigint; n bigint; doc text; hash text; result_value jsonb; event uuid;
BEGIN
 SELECT branch_id,store_uuid INTO b,store FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 SELECT t.staff_id INTO who FROM pos.module_session t JOIN pos.staff s USING(branch_id,staff_id)
 WHERE t.branch_id=b AND t.token_hash=proof AND t.terminal_identity=terminal AND t.module='ADMINISTRATION'
 AND t.expires_at>clock_timestamp() AND s.enabled AND s.credential_version=t.credential_version
 AND (s.role='MANAGER' OR EXISTS(SELECT 1 FROM pos.store_owner o WHERE o.branch_id=b AND o.staff_id=s.staff_id));
 IF who IS NULL THEN RAISE EXCEPTION 'Qualified Administration session required' USING ERRCODE='42501'; END IF;
 SELECT EXISTS(SELECT 1 FROM pos.store_owner WHERE branch_id=b AND staff_id=who) INTO owner_user;
 permitted=CASE WHEN operation IN ('PUBLISH','ROLLBACK') THEN 'sales_screen.publish' ELSE 'sales_screen.edit' END;
 IF NOT owner_user AND (NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p=permitted)
 OR NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p='module.sales_screen_builder')) THEN
 RAISE EXCEPTION 'Screen permission required' USING ERRCODE='42501'; END IF;
 IF command IS NULL OR command='00000000-0000-0000-0000-000000000000'::uuid OR operation IS NULL OR operation NOT IN ('SAVE','PUBLISH','ROLLBACK','ASSET') THEN RAISE EXCEPTION 'Invalid screen command' USING ERRCODE='23514'; END IF;
 payload_hash=encode(sha256(convert_to(operation||value::text,'UTF8')),'hex');
 SELECT * INTO old FROM pos.screen_command WHERE command_id=command;
 IF FOUND THEN
 IF old.branch_id<>b OR old.actor_id<>who OR old.terminal_identity<>terminal OR old.payload_hash<>payload_hash THEN RAISE EXCEPTION 'Command reused' USING ERRCODE='40001'; END IF;
 RETURN old.result; END IF;
 sid=(value->>'screenId')::uuid; wanted=(value->>'expectedRevision')::bigint;
 IF sid IS NULL OR sid='00000000-0000-0000-0000-000000000000'::uuid OR (operation<>'ASSET' AND (wanted IS NULL OR wanted<0)) THEN RAISE EXCEPTION 'Identity and expected revision required' USING ERRCODE='23514'; END IF;
 IF operation='ASSET' THEN
 hash=value->>'hash';
 IF hash IS NULL OR hash !~ '^[a-f0-9]{64}$' OR value->>'mediaType' NOT IN ('image/png','image/jpeg')
 OR octet_length(decode(value->>'content','base64')) NOT BETWEEN 1 AND 524288
 OR encode(sha256(decode(value->>'content','base64')),'hex')<>hash THEN RAISE EXCEPTION 'Invalid asset' USING ERRCODE='23514'; END IF;
 INSERT INTO pos.screen_asset VALUES(hash,b,value->>'mediaType',decode(value->>'content','base64')) ON CONFLICT DO NOTHING;
 IF NOT EXISTS(SELECT 1 FROM pos.screen_asset WHERE asset_hash=hash AND branch_id=b) THEN RAISE EXCEPTION 'Asset store mismatch' USING ERRCODE='23514'; END IF;
 result_value=jsonb_build_object('hash',hash);
 PERFORM pos.record_audit_event(gen_random_uuid(),b,who,terminal,'SALES_SCREEN_CHANGED',sid,command,jsonb_build_object('operation',operation,'sourceDigest',hash),NULL,NULL,NULL,'LOCAL_MASTER');
 ELSIF operation='SAVE' THEN
 doc=value->>'document';
 IF doc IS NULL OR octet_length(doc)>4194304 OR jsonb_typeof(doc::jsonb) IS DISTINCT FROM 'object' OR (doc::jsonb->>'schemaVersion') IS DISTINCT FROM '1'
 OR (doc::jsonb->>'screenId') IS DISTINCT FROM sid::text OR (doc::jsonb->>'storeId') IS DISTINCT FROM store::text
 OR jsonb_typeof(doc::jsonb->'pages')<>'array' OR jsonb_array_length(doc::jsonb->'pages')<1
 OR coalesce(length(trim(value->>'name')),0) NOT BETWEEN 1 AND 160 THEN RAISE EXCEPTION 'Invalid screen document' USING ERRCODE='23514'; END IF;
 IF (value->>'template')::boolean AND NOT owner_user AND NOT EXISTS(SELECT 1 FROM pos.admin_permissions(b,who) p WHERE p='sales_screen.manage_templates') THEN RAISE EXCEPTION 'Template permission required' USING ERRCODE='42501'; END IF;
 INSERT INTO pos.screen_definition VALUES(sid,b,value->>'name',(value->>'template')::boolean,0) ON CONFLICT DO NOTHING;
 SELECT revision INTO current_revision FROM pos.screen_definition WHERE screen_id=sid AND branch_id=b FOR UPDATE;
 IF current_revision IS NULL OR wanted IS NULL OR current_revision<>wanted OR (SELECT template FROM pos.screen_definition WHERE screen_id=sid) IS DISTINCT FROM (value->>'template')::boolean THEN RAISE EXCEPTION 'Screen changed' USING ERRCODE='40001'; END IF;
 n=current_revision+1;hash=encode(sha256(convert_to(doc,'UTF8')),'hex');
 INSERT INTO pos.screen_revision VALUES(sid,n,doc,hash,who,clock_timestamp());
 UPDATE pos.screen_definition SET revision=n,name=value->>'name' WHERE screen_id=sid;
 result_value=jsonb_build_object('screenId',sid,'revision',n,'hash',hash);
 PERFORM pos.record_audit_event(gen_random_uuid(),b,who,terminal,'SALES_SCREEN_CHANGED',sid,command,jsonb_build_object('revision',n,'operation',operation,'sourceDigest',hash),NULL,NULL,NULL,'LOCAL_MASTER');
 ELSE
 SELECT document_text,document_hash INTO doc,hash FROM pos.screen_revision r JOIN pos.screen_definition d USING(screen_id)
 WHERE r.screen_id=sid AND r.revision=(value->>'revision')::bigint AND d.branch_id=b AND NOT d.template;
 IF doc IS NULL THEN RAISE EXCEPTION 'Unknown screen revision' USING ERRCODE='23514'; END IF;
 INSERT INTO pos.store_screen_publication(branch_id,store_id) VALUES(b,store) ON CONFLICT DO NOTHING;
 SELECT sequence INTO current_revision FROM pos.store_screen_publication WHERE branch_id=b FOR UPDATE;
 IF current_revision<>wanted THEN RAISE EXCEPTION 'Publication changed' USING ERRCODE='40001'; END IF;
 n=current_revision+1;event=gen_random_uuid();
 UPDATE pos.store_screen_publication SET sequence=n,screen_id=sid,revision=(value->>'revision')::bigint WHERE branch_id=b;
 INSERT INTO pos.screen_publication_event VALUES(event,b,store,n,sid,(value->>'revision')::bigint,hash,clock_timestamp());
 result_value=jsonb_build_object('eventId',event,'screenId',sid,'revision',(value->>'revision')::bigint,'sequence',n,'hash',hash);
 PERFORM pos.record_audit_event(gen_random_uuid(),b,who,terminal,'SALES_SCREEN_PUBLISHED',sid,command,jsonb_build_object('revision',(value->>'revision')::bigint,'operation',operation,'sourceDigest',hash),NULL,NULL,NULL,'LOCAL_MASTER');
 END IF;
 INSERT INTO pos.screen_command VALUES(command,b,who,terminal,payload_hash,result_value);
 RETURN result_value;
END $$;
REVOKE ALL ON FUNCTION pos.write_sales_screen(text,text,uuid,text,jsonb) FROM PUBLIC;
REVOKE ALL ON pos.screen_definition,pos.screen_revision,pos.store_screen_publication,pos.screen_publication_event,pos.screen_command,pos.screen_asset FROM PUBLIC,pos_runtime;
GRANT SELECT ON pos.screen_command,pos.screen_definition,pos.screen_revision,pos.store_screen_publication,pos.screen_publication_event,pos.screen_asset TO pos_runtime;
GRANT EXECUTE ON FUNCTION pos.write_sales_screen(text,text,uuid,text,jsonb) TO pos_runtime;

INSERT INTO pos.audit_event_type VALUES ('DRAFT_EDITED','Orders',false);

-- Narrow authenticated edit port; runtime keeps no direct DELETE privilege on draft/history rows.
CREATE FUNCTION pos.prepare_draft_edit(proof text,terminal text,order_value uuid,expected bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer; who uuid;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 SELECT staff_id INTO who FROM (
  SELECT t.staff_id FROM pos.module_session t JOIN pos.staff s USING(branch_id,staff_id)
  WHERE t.branch_id=b AND t.token_hash=proof AND t.terminal_identity=terminal AND t.module='SALES_ORDERS'
   AND t.expires_at>clock_timestamp() AND s.enabled AND s.credential_version=t.credential_version
  UNION ALL
  SELECT t.staff_id FROM pos.staff_session t JOIN pos.staff s USING(branch_id,staff_id)
  WHERE t.branch_id=b AND t.token_hash=proof AND t.terminal_identity=terminal
   AND t.expires_at>clock_timestamp() AND s.enabled AND s.credential_version=t.credential_version
 ) sessions LIMIT 1;
 IF who IS NULL OR NOT EXISTS(SELECT 1 FROM pos.staff_role r JOIN pos.role_permission p USING(branch_id,role_name)
  WHERE r.branch_id=b AND r.staff_id=who AND p.permission='module.sales_orders') THEN RAISE EXCEPTION 'Sales session required' USING ERRCODE='42501'; END IF;
 PERFORM sync_id FROM pos.order_draft WHERE branch_id=b AND sync_id=order_value AND state='DRAFT' AND revision=expected FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Draft changed or cannot be edited' USING ERRCODE='40001'; END IF;
 DELETE FROM pos.order_line_extra WHERE branch_id=b AND line_id IN(SELECT line_id FROM pos.order_line WHERE branch_id=b AND order_id=order_value);
 DELETE FROM pos.order_line WHERE branch_id=b AND order_id=order_value;
 UPDATE pos.order_draft SET revision=revision+1,customer_id=NULL WHERE branch_id=b AND sync_id=order_value;
END $$;
REVOKE ALL ON FUNCTION pos.prepare_draft_edit(text,text,uuid,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.prepare_draft_edit(text,text,uuid,bigint) TO pos_runtime;
