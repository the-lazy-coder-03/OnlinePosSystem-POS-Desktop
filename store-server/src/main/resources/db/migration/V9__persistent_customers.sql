-- Extend the existing UUID customer authority; preserve all legacy rows and draft references.
ALTER TABLE pos.local_customer ALTER COLUMN display_name TYPE varchar(511);
ALTER TABLE pos.local_customer ADD COLUMN profile jsonb NOT NULL DEFAULT '{}',
 ADD COLUMN field_times jsonb NOT NULL DEFAULT '{}', ADD COLUMN normalized_phone varchar(16),
 ADD COLUMN normalized_email varchar(255), ADD COLUMN preferred_branch integer,
 ADD COLUMN archived boolean NOT NULL DEFAULT false, ADD COLUMN banned boolean NOT NULL DEFAULT false,
 ADD COLUMN ban_reason varchar(2000) NOT NULL DEFAULT '', ADD COLUMN ban_changed_at timestamptz,
 ADD COLUMN global_id uuid, ADD COLUMN cloud_id bigint, ADD COLUMN canonical_customer_id uuid,
 ADD COLUMN sync_state varchar(32) NOT NULL DEFAULT 'PENDING', ADD COLUMN updated_at timestamptz;
UPDATE pos.local_customer SET profile=jsonb_build_object('first_name',display_name,'last_name','','phone1',coalesce(phone,''),'phone2','','email','','formatted_address',coalesce(address,''),'preferred_store',CASE branch_id WHEN 1 THEN 'Kenridge' ELSE 'Uitzicht' END),preferred_branch=branch_id,global_id=customer_id;
ALTER TABLE pos.local_customer ADD CONSTRAINT customer_alias_fk FOREIGN KEY(branch_id,canonical_customer_id) REFERENCES pos.local_customer(branch_id,customer_id);
CREATE UNIQUE INDEX customer_phone_unique ON pos.local_customer(branch_id,normalized_phone) WHERE normalized_phone IS NOT NULL AND canonical_customer_id IS NULL;
CREATE UNIQUE INDEX customer_email_unique ON pos.local_customer(branch_id,normalized_email) WHERE normalized_email IS NOT NULL AND canonical_customer_id IS NULL;
CREATE UNIQUE INDEX customer_cloud_unique ON pos.local_customer(branch_id,cloud_id) WHERE cloud_id IS NOT NULL AND canonical_customer_id IS NULL;
CREATE INDEX customer_directory ON pos.local_customer(branch_id,archived,preferred_branch,lower(display_name),customer_id);
ALTER TABLE pos.order_draft ADD COLUMN customer_snapshot jsonb;
CREATE INDEX customer_drafts ON pos.order_draft(branch_id,customer_id,created_at DESC,sync_id);
CREATE TABLE pos.customer_status_history(
 event_id uuid PRIMARY KEY,branch_id integer NOT NULL,customer_id uuid NOT NULL,
 action varchar(64) NOT NULL,actor_id uuid,terminal_identity varchar(512),reason varchar(2000) NOT NULL DEFAULT '',
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),command_id uuid NOT NULL UNIQUE,
 FOREIGN KEY(branch_id,customer_id) REFERENCES pos.local_customer(branch_id,customer_id)
);
CREATE TABLE pos.customer_alias(
 alias_id uuid PRIMARY KEY,branch_id integer NOT NULL,customer_id uuid NOT NULL,
 FOREIGN KEY(branch_id,customer_id) REFERENCES pos.local_customer(branch_id,customer_id)
);
CREATE TABLE pos.customer_conflict(
 conflict_id uuid PRIMARY KEY,branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
 source_event uuid NOT NULL UNIQUE,payload_hash char(64) NOT NULL,payload jsonb NOT NULL,
 reason varchar(128) NOT NULL,state varchar(16) NOT NULL DEFAULT 'OPEN',revision bigint NOT NULL DEFAULT 0,
 resolution jsonb,resolved_by uuid,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),resolved_at timestamptz
);
CREATE TABLE pos.customer_sync_pending(
 event_id uuid PRIMARY KEY REFERENCES pos.outbox_event(event_id),branch_id integer NOT NULL,
 customer_id uuid NOT NULL,state varchar(32) NOT NULL DEFAULT 'PENDING',acknowledged_at timestamptz,ack_reference text,ack_global uuid,ack_cloud bigint,
 FOREIGN KEY(branch_id,customer_id) REFERENCES pos.local_customer(branch_id,customer_id)
);
CREATE INDEX customer_conflicts_open ON pos.customer_conflict(branch_id,state,created_at,conflict_id);
-- Normalize only recognizable legacy contacts. Collisions retain every UUID and become
-- explicit conflicts; no historical timestamp or identity is invented.
DO $$ DECLARE r record; cleaned text; normal text; matches jsonb; BEGIN
 FOR r IN SELECT customer_id,branch_id,phone FROM pos.local_customer LOOP
  cleaned=regexp_replace(coalesce(r.phone,''),'[[:space:]().-]','','g');
  IF cleaned ~ '^00' THEN cleaned='+'||substring(cleaned FROM 3); END IF;
  IF cleaned ~ '^0[0-9]{9}$' THEN cleaned='+27'||substring(cleaned FROM 2); END IF;
  IF cleaned ~ '^\+[1-9][0-9]{7,14}$' THEN
   UPDATE pos.local_customer SET profile=jsonb_set(profile,'{phone1}',to_jsonb(cleaned)) WHERE customer_id=r.customer_id;
  END IF;
 END LOOP;
 FOR r IN SELECT branch_id,profile->>'phone1' AS phone,jsonb_agg(customer_id ORDER BY customer_id) AS ids,count(*) AS total FROM pos.local_customer WHERE profile->>'phone1' ~ '^\+[1-9][0-9]{7,14}$' GROUP BY branch_id,profile->>'phone1' LOOP
  IF r.total=1 THEN UPDATE pos.local_customer SET normalized_phone=r.phone WHERE customer_id=(r.ids->>0)::uuid;
  ELSE
   INSERT INTO pos.customer_conflict(conflict_id,branch_id,source_event,payload_hash,payload,reason)
   SELECT customer_id,branch_id,customer_id,repeat('0',64),jsonb_build_object('proposedId',customer_id,'proposedRevision',revision,'fields',profile,'matches',r.ids),'LEGACY_CONTACT_COLLISION' FROM pos.local_customer WHERE branch_id=r.branch_id AND profile->>'phone1'=r.phone;
   UPDATE pos.local_customer SET sync_state='CONFLICT' WHERE branch_id=r.branch_id AND profile->>'phone1'=r.phone;
  END IF;
 END LOOP;
END $$;
REVOKE INSERT,UPDATE ON pos.local_customer FROM pos_runtime;
GRANT SELECT ON pos.customer_status_history,pos.customer_alias,pos.customer_conflict,pos.customer_sync_pending TO pos_runtime;

-- Explicit action permissions, additive to existing module entry permissions.
INSERT INTO pos.role_permission SELECT branch_id,name,p FROM pos.rbac_role CROSS JOIN unnest(ARRAY['customers.view','customers.manage','customers.archive','customers.ban','customers.resolve']) p WHERE name='MANAGER' ON CONFLICT DO NOTHING;
-- Existing administration functions validate their allowlist. Extend it without changing prior migration checksums.
DO $$ DECLARE body text; BEGIN
 SELECT pg_get_functiondef('pos.apply_admin_change(text,text,text,jsonb)'::regprocedure) INTO body;
 body=replace(body,'''audit.view'',''catalogue.manage''','''audit.view'',''catalogue.manage'',''customers.view'',''customers.manage'',''customers.archive'',''customers.ban'',''customers.resolve''');
 EXECUTE body;
END $$;
CREATE FUNCTION pos.require_customer_session(proof text,terminal text,permission_value text) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE who uuid;b integer;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 SELECT t.staff_id INTO who FROM pos.module_session t JOIN pos.staff s ON s.branch_id=t.branch_id AND s.staff_id=t.staff_id
 WHERE t.token_hash=proof AND t.branch_id=b AND t.terminal_identity=terminal AND t.module='CUSTOMERS'
 AND t.expires_at>clock_timestamp() AND s.enabled AND s.credential_version=t.credential_version;
 IF who IS NULL THEN RAISE EXCEPTION 'Customers session required' USING ERRCODE='28000'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pos.staff_role r JOIN pos.role_permission p ON p.branch_id=r.branch_id AND p.role_name=r.role_name
 WHERE r.branch_id=b AND r.staff_id=who AND p.permission=permission_value) THEN
  RAISE EXCEPTION 'Customer permission required' USING ERRCODE='42501'; END IF;
 RETURN who;
END $$;
CREATE FUNCTION pos.write_customer(proof text,terminal text,id uuid,expected bigint,operation text,data jsonb,command uuid)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;previous pos.local_customer;next_revision bigint;stamp timestamptz;profile_value jsonb;name_value text;target pos.local_customer;k text;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 who=pos.require_customer_session(proof,terminal,CASE operation WHEN 'ARCHIVE' THEN 'customers.archive' WHEN 'BAN' THEN 'customers.ban' WHEN 'LINK' THEN 'customers.resolve' WHEN 'RESOLVE_SAVE' THEN 'customers.resolve' ELSE 'customers.manage' END);
 SELECT * INTO previous FROM pos.local_customer WHERE branch_id=b AND customer_id=id FOR UPDATE;
 IF operation IN ('SAVE','RESOLVE_SAVE') AND expected=-1 AND NOT FOUND THEN NULL;
 ELSIF NOT FOUND OR previous.revision<>expected OR previous.canonical_customer_id IS NOT NULL THEN
  RAISE EXCEPTION 'Customer changed; reload' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM pos.customer_alias WHERE alias_id=id AND customer_id<>id) THEN RAISE EXCEPTION 'Customer identity is already linked' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM pos.local_customer WHERE customer_id=id AND branch_id<>b) THEN RAISE EXCEPTION 'Foreign customer' USING ERRCODE='42501'; END IF;
 stamp=clock_timestamp();next_revision=coalesce(previous.revision+1,0);
 IF operation IN ('SAVE','RESOLVE_SAVE') THEN
  profile_value=data;
  IF jsonb_typeof(data)<>'object' OR EXISTS(SELECT 1 FROM jsonb_each(data) v WHERE jsonb_typeof(value)<>'string' OR key NOT IN
   ('first_name','last_name','phone1','phone2','email','house_number','street','area','complex_name','preferred_store','city','postal_code','google_place_id','formatted_address','latitude','longitude','province','country')) THEN
   RAISE EXCEPTION 'Invalid profile fields' USING ERRCODE='23514'; END IF;
  name_value=btrim(coalesce(data->>'first_name','')||' '||coalesce(data->>'last_name',''));
  IF name_value='' OR coalesce(data->>'phone1','') !~ '^\+[1-9][0-9]{7,14}$' OR length(data->>'phone1')>20
   OR length(coalesce(data->>'email',''))>255 THEN RAISE EXCEPTION 'Invalid name or phone' USING ERRCODE='23514'; END IF;
  IF data->>'preferred_store' IS DISTINCT FROM coalesce(previous.profile->>'preferred_store',CASE b WHEN 1 THEN 'Kenridge' ELSE 'Uitzicht' END) THEN
   RAISE EXCEPTION 'Only the customer changes preferred store' USING ERRCODE='42501'; END IF;
  IF EXISTS(SELECT 1 FROM pos.local_customer WHERE branch_id=b AND customer_id<>id AND canonical_customer_id IS NULL AND profile->>'phone1'=data->>'phone1') THEN RAISE EXCEPTION 'Contact collision' USING ERRCODE='23505'; END IF;
  INSERT INTO pos.local_customer(customer_id,branch_id,display_name,phone,address,profile,normalized_phone,normalized_email,preferred_branch,global_id,revision,updated_at)
  VALUES(id,b,name_value,data->>'phone1',data->>'formatted_address',data,data->>'phone1',nullif(lower(btrim(data->>'email')),''),b,id,0,stamp)
  ON CONFLICT(customer_id) DO UPDATE SET display_name=excluded.display_name,phone=excluded.phone,address=excluded.address,
   profile=excluded.profile,normalized_phone=excluded.normalized_phone,normalized_email=excluded.normalized_email,revision=next_revision,updated_at=stamp,sync_state='PENDING';
  IF previous.profile IS NULL THEN UPDATE pos.local_customer SET field_times=jsonb_set(field_times,'{preferred_store}',to_jsonb(stamp)) WHERE customer_id=id; END IF;
  -- Address is one atomic timestamped group; other contact fields are individually versioned.
  FOR k IN SELECT key FROM jsonb_each(data) WHERE key NOT IN ('preferred_store','house_number','street','area','complex_name','city','postal_code','google_place_id','formatted_address','latitude','longitude','province','country') LOOP
   IF previous.profile->k IS DISTINCT FROM data->k THEN UPDATE pos.local_customer SET field_times=jsonb_set(field_times,ARRAY[k],to_jsonb(stamp)) WHERE customer_id=id; END IF;
  END LOOP;
  IF previous.profile IS NULL OR (previous.profile-ARRAY['first_name','last_name','phone1','phone2','email','preferred_store']) IS DISTINCT FROM (data-ARRAY['first_name','last_name','phone1','phone2','email','preferred_store']) THEN
   UPDATE pos.local_customer SET field_times=jsonb_set(field_times,'{address}',to_jsonb(stamp)) WHERE customer_id=id;
  END IF;
 ELSIF operation='ARCHIVE' THEN
  UPDATE pos.local_customer SET archived=(data->>'value')::boolean,revision=next_revision,updated_at=stamp WHERE customer_id=id;
 ELSIF operation='BAN' THEN
  IF coalesce((data->>'value')::boolean,true) AND length(btrim(coalesce(data->>'reason','')))=0 THEN RAISE EXCEPTION 'Ban reason required' USING ERRCODE='23514'; END IF;
  IF length(coalesce(data->>'reason',''))>2000 THEN RAISE EXCEPTION 'Reason too long' USING ERRCODE='23514'; END IF;
  UPDATE pos.local_customer SET banned=(data->>'value')::boolean,ban_reason=CASE WHEN (data->>'value')::boolean THEN data->>'reason' ELSE '' END,
    ban_changed_at=stamp,field_times=jsonb_set(field_times,'{ban}',to_jsonb(stamp)),revision=next_revision,updated_at=stamp,sync_state='PENDING' WHERE customer_id=id;
 ELSIF operation='LINK' THEN
  SELECT * INTO target FROM pos.local_customer WHERE branch_id=b AND customer_id=(data->>'target')::uuid AND canonical_customer_id IS NULL FOR UPDATE;
  IF NOT FOUND OR target.customer_id=id THEN RAISE EXCEPTION 'Invalid link target' USING ERRCODE='23514'; END IF;
  UPDATE pos.local_customer SET canonical_customer_id=target.customer_id,normalized_phone=NULL,normalized_email=NULL,revision=next_revision,updated_at=stamp WHERE customer_id=id;
  INSERT INTO pos.customer_alias VALUES(id,b,target.customer_id) ON CONFLICT(alias_id) DO UPDATE SET customer_id=excluded.customer_id;
  UPDATE pos.customer_alias SET customer_id=target.customer_id WHERE branch_id=b AND customer_id=id;
  UPDATE pos.local_customer SET canonical_customer_id=target.customer_id WHERE branch_id=b AND canonical_customer_id=id;
  IF previous.banned AND NOT target.banned THEN UPDATE pos.local_customer SET banned=true,ban_reason=previous.ban_reason,ban_changed_at=stamp,field_times=jsonb_set(field_times,'{ban}',to_jsonb(stamp)),revision=revision+1,sync_state='PENDING' WHERE customer_id=target.customer_id; END IF;
 ELSE RAISE EXCEPTION 'Unsupported customer operation' USING ERRCODE='23514';
 END IF;
 INSERT INTO pos.customer_status_history VALUES(command,b,id,operation,who,terminal,CASE WHEN operation='BAN' THEN coalesce(data->>'reason','') ELSE '' END,stamp,command);
 RETURN next_revision;
END $$;
CREATE FUNCTION pos.queue_customer_sync(event uuid,b integer,id uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pos.outbox_event WHERE event_id=event AND branch_id=b AND event_type LIKE 'CUSTOMER_%') THEN RAISE EXCEPTION 'Customer outbox required' USING ERRCODE='23514'; END IF;
 INSERT INTO pos.customer_sync_pending(event_id,branch_id,customer_id) VALUES(event,b,id) ON CONFLICT DO NOTHING;
END $$;
CREATE FUNCTION pos.customer_conflict_write(proof text,terminal text,id uuid,source uuid,digest text,payload_value jsonb,reason_value text,expected bigint,resolution_value jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 who=pos.require_customer_session(proof,terminal,CASE WHEN expected<0 THEN 'customers.manage' ELSE 'customers.resolve' END);
 IF expected<0 THEN
  INSERT INTO pos.customer_conflict(conflict_id,branch_id,source_event,payload_hash,payload,reason) VALUES(id,b,source,digest,payload_value,reason_value)
  ON CONFLICT(source_event) DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM pos.customer_conflict WHERE source_event=source AND payload_hash=digest) THEN RAISE EXCEPTION 'Conflict source changed' USING ERRCODE='23514'; END IF;
 ELSE
  UPDATE pos.customer_conflict SET state='RESOLVED',revision=revision+1,resolution=resolution_value,resolved_by=who,resolved_at=clock_timestamp()
  WHERE conflict_id=id AND branch_id=b AND revision=expected AND state='OPEN';
  IF NOT FOUND THEN RAISE EXCEPTION 'Conflict changed' USING ERRCODE='23514'; END IF;
 END IF;
END $$;
REVOKE ALL ON FUNCTION pos.require_customer_session(text,text,text),pos.write_customer(text,text,uuid,bigint,text,jsonb,uuid),pos.queue_customer_sync(uuid,integer,uuid),pos.customer_conflict_write(text,text,uuid,uuid,text,jsonb,text,bigint,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.require_customer_session(text,text,text),pos.write_customer(text,text,uuid,bigint,text,jsonb,uuid),pos.queue_customer_sync(uuid,integer,uuid),pos.customer_conflict_write(text,text,uuid,uuid,text,jsonb,text,bigint,jsonb) TO pos_runtime;
-- Customer lifecycle tracking is optional; bans, unbans, links and remote identity application are mandatory.
INSERT INTO pos.audit_event_type VALUES
 ('CUSTOMER_CREATED','Customers',true),('CUSTOMER_UPDATED','Customers',true),('CUSTOMER_ARCHIVED','Customers',true),('CUSTOMER_RESTORED','Customers',true),
 ('CUSTOMER_BANNED','Security',false),('CUSTOMER_UNBANNED','Security',false),('CUSTOMER_CONFLICT_RESOLVED','Security',false),('CUSTOMER_SYNC_APPLIED','Customers',false);
ALTER TABLE pos.audit_tracking_settings DROP CONSTRAINT optional_audit_types_only;
ALTER TABLE pos.audit_tracking_settings ADD CONSTRAINT optional_audit_types_only CHECK(event_type IN ('DRAFT_CREATED','DRAFT_CANCELLED','PRICE_CHANGED','CATALOGUE_CHANGED','STAFF_LOGIN','STAFF_LOGOUT','CUSTOMER_CREATED','CUSTOMER_UPDATED','CUSTOMER_ARCHIVED','CUSTOMER_RESTORED'));
INSERT INTO pos.audit_tracking_settings SELECT branch_id,action,true FROM pos.audit_policy CROSS JOIN pos.audit_event_type WHERE optional ON CONFLICT DO NOTHING;
CREATE FUNCTION pos.resolve_customer_identity(proof text,terminal text,source uuid,target_id uuid,command uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;existing pos.local_customer;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 who=pos.require_customer_session(proof,terminal,'customers.resolve');
 IF NOT EXISTS(SELECT 1 FROM pos.local_customer WHERE branch_id=b AND customer_id=target_id AND canonical_customer_id IS NULL) OR source=target_id THEN RAISE EXCEPTION 'Invalid link target' USING ERRCODE='23514'; END IF;
 SELECT * INTO existing FROM pos.local_customer WHERE branch_id=b AND customer_id=source;
 IF FOUND THEN PERFORM pos.write_customer(proof,terminal,source,existing.revision,'LINK',jsonb_build_object('target',target_id),command);
 ELSE
  IF EXISTS(SELECT 1 FROM pos.customer_alias WHERE alias_id=source AND (branch_id<>b OR customer_id<>target_id)) THEN RAISE EXCEPTION 'Alias conflict' USING ERRCODE='23514'; END IF;
  INSERT INTO pos.customer_alias VALUES(source,b,target_id) ON CONFLICT DO NOTHING;
  INSERT INTO pos.customer_status_history VALUES(command,b,target_id,'LINK',who,terminal,'',clock_timestamp(),command);
 END IF;
 UPDATE pos.local_customer SET sync_state='PENDING' WHERE branch_id=b AND customer_id=target_id;
END $$;
CREATE FUNCTION pos.guard_customer_draft() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE customer pos.local_customer;
BEGIN
 IF TG_OP='UPDATE' AND OLD.customer_id IS NOT NULL AND NEW.customer_id IS DISTINCT FROM OLD.customer_id THEN RAISE EXCEPTION 'Historical customer linkage is immutable' USING ERRCODE='42501'; END IF;
 IF NEW.customer_id IS NULL THEN
  IF NEW.customer_snapshot IS NOT NULL THEN RAISE EXCEPTION 'Anonymous draft has no customer snapshot' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='UPDATE' AND NEW.customer_id IS NOT DISTINCT FROM OLD.customer_id THEN
  IF NEW.customer_snapshot IS DISTINCT FROM OLD.customer_snapshot THEN RAISE EXCEPTION 'Historical customer snapshot is immutable' USING ERRCODE='42501'; END IF;
  RETURN NEW;
 END IF;
 PERFORM position FROM pos.stream_head WHERE branch_id=NEW.branch_id FOR UPDATE;
 SELECT * INTO customer FROM pos.local_customer WHERE branch_id=NEW.branch_id AND customer_id=coalesce((SELECT customer_id FROM pos.customer_alias WHERE branch_id=NEW.branch_id AND alias_id=NEW.customer_id),NEW.customer_id);
 IF NOT FOUND OR customer.banned OR customer.archived OR customer.canonical_customer_id IS NOT NULL THEN RAISE EXCEPTION 'Customer unavailable for new orders' USING ERRCODE='23514'; END IF;
 NEW.customer_id=customer.customer_id;
 NEW.customer_snapshot=jsonb_build_object('customerId',customer.customer_id,'name',customer.display_name,'profile',customer.profile);
 RETURN NEW;
END $$;
CREATE TRIGGER customer_draft_guard BEFORE INSERT OR UPDATE OF customer_id,customer_snapshot ON pos.order_draft FOR EACH ROW EXECUTE FUNCTION pos.guard_customer_draft();
CREATE FUNCTION pos.customer_role_defaults() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN
 IF NEW.name='MANAGER' THEN INSERT INTO pos.role_permission SELECT NEW.branch_id,NEW.name,p FROM unnest(ARRAY['customers.view','customers.manage','customers.archive','customers.ban','customers.resolve']) p ON CONFLICT DO NOTHING; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customer_role_defaults AFTER INSERT ON pos.rbac_role FOR EACH ROW EXECUTE FUNCTION pos.customer_role_defaults();
REVOKE ALL ON FUNCTION pos.resolve_customer_identity(text,text,uuid,uuid,uuid),pos.guard_customer_draft(),pos.customer_role_defaults() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.resolve_customer_identity(text,text,uuid,uuid,uuid) TO pos_runtime;
-- Extend the central safe-detail allowlist, retaining all existing recorder protections.
DO $$ DECLARE body text; BEGIN
 SELECT pg_get_functiondef('pos.record_audit_event(uuid,integer,uuid,text,text,uuid,uuid,jsonb,uuid,uuid,timestamptz,text)'::regprocedure) INTO body;
 body=replace(body,'''sourceDigest''','''sourceDigest'',''customerId'',''fields''');EXECUTE body;
END $$;
CREATE UNIQUE INDEX customer_global_unique ON pos.local_customer(branch_id,global_id) WHERE canonical_customer_id IS NULL;
-- Staged sync ports require an authorised resolver session. No HTTP route or worker invokes them.
CREATE FUNCTION pos.apply_customer_remote(proof text,terminal text,id uuid,data jsonb,event uuid,digest text,result_value jsonb) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;previous pos.local_customer;normalized text;email_value text;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 who=pos.require_customer_session(proof,terminal,'customers.resolve');
 IF EXISTS(SELECT 1 FROM pos.inbox_receipt WHERE event_id=event AND payload_hash<>digest) THEN RAISE EXCEPTION 'Remote event conflict' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM pos.inbox_receipt WHERE event_id=event) THEN RETURN; END IF;
 IF data IS NOT NULL THEN
  normalized=data->'fields'->>'phone1';email_value=nullif(lower(data->'fields'->>'email'),'');
  IF normalized !~ '^\+[1-9][0-9]{7,14}$' OR (data->>'preferredBranch')::integer NOT IN (1,2) OR NOT data ? 'fieldTimes' THEN RAISE EXCEPTION 'Invalid remote profile' USING ERRCODE='23514'; END IF;
  SELECT * INTO previous FROM pos.local_customer WHERE customer_id=id AND branch_id=b;
  IF EXISTS(SELECT 1 FROM pos.local_customer WHERE customer_id=id AND branch_id<>b) THEN RAISE EXCEPTION 'Foreign customer' USING ERRCODE='42501'; END IF;
  INSERT INTO pos.local_customer(customer_id,branch_id,display_name,phone,address,profile,field_times,normalized_phone,normalized_email,preferred_branch,banned,ban_reason,ban_changed_at,global_id,cloud_id,revision,updated_at,sync_state)
  VALUES(id,b,btrim((data->'fields'->>'first_name')||' '||(data->'fields'->>'last_name')),normalized,data->'fields'->>'formatted_address',data->'fields',data->'fieldTimes',normalized,email_value,(data->>'preferredBranch')::integer,
   (data->>'banned')::boolean,coalesce(data->>'banReason',''),(data->>'banChangedAt')::timestamptz,(data->>'globalId')::uuid,(data->>'cloudId')::bigint,0,clock_timestamp(),'SYNCED')
  ON CONFLICT(customer_id) DO UPDATE SET display_name=excluded.display_name,phone=excluded.phone,address=excluded.address,profile=excluded.profile,field_times=excluded.field_times,
   normalized_phone=excluded.normalized_phone,normalized_email=excluded.normalized_email,preferred_branch=excluded.preferred_branch,banned=excluded.banned,ban_reason=excluded.ban_reason,ban_changed_at=excluded.ban_changed_at,
   global_id=excluded.global_id,cloud_id=excluded.cloud_id,revision=pos.local_customer.revision+1,updated_at=excluded.updated_at,
   sync_state=CASE WHEN EXISTS(SELECT 1 FROM pos.customer_sync_pending WHERE customer_id=id AND state='PENDING') THEN 'PENDING' ELSE 'SYNCED' END;
  IF (data->>'globalId')::uuid<>id THEN INSERT INTO pos.customer_alias VALUES((data->>'globalId')::uuid,b,id) ON CONFLICT(alias_id) DO NOTHING; END IF;
  IF (previous.customer_id IS NOT NULL AND (previous.banned IS DISTINCT FROM (data->>'banned')::boolean OR previous.ban_reason IS DISTINCT FROM coalesce(data->>'banReason',''))) OR (previous.customer_id IS NULL AND (data->>'banned')::boolean) THEN
   INSERT INTO pos.customer_status_history VALUES(event,b,id,CASE WHEN (data->>'banned')::boolean THEN 'REMOTE_BAN' ELSE 'REMOTE_UNBAN' END,NULL,NULL,coalesce(data->>'banReason',''),(data->>'banChangedAt')::timestamptz,event);
  END IF;
  PERFORM pos.record_audit_event(event,b,who,terminal,'CUSTOMER_SYNC_APPLIED',id,NULL,jsonb_build_object('customerId',id),NULL,NULL,NULL,'CLOUD');
 END IF;
 INSERT INTO pos.inbox_receipt(event_id,branch_id,payload_hash,result) VALUES(event,b,digest,result_value);
END $$;
CREATE FUNCTION pos.ack_customer_sync(proof text,terminal text,event uuid,digest text,ack text,global_value uuid,cloud_value bigint) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;target uuid;existing pos.local_customer;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;who=pos.require_customer_session(proof,terminal,'customers.resolve');
 IF length(coalesce(ack,''))=0 OR NOT EXISTS(SELECT 1 FROM pos.outbox_event WHERE event_id=event AND branch_id=b AND encode(sha256(convert_to(payload::text,'UTF8')),'hex')=digest) THEN RAISE EXCEPTION 'Acknowledgement mismatch' USING ERRCODE='23514'; END IF;
 SELECT customer_id INTO target FROM pos.customer_sync_pending WHERE event_id=event AND branch_id=b AND state='PENDING' FOR UPDATE;
 IF NOT FOUND THEN
  IF NOT EXISTS(SELECT 1 FROM pos.customer_sync_pending WHERE event_id=event AND branch_id=b AND ack_reference=ack AND ack_global IS NOT DISTINCT FROM global_value AND ack_cloud IS NOT DISTINCT FROM cloud_value) THEN RAISE EXCEPTION 'Acknowledgement replay conflict' USING ERRCODE='23514'; END IF;
  RETURN;
 END IF;
 SELECT * INTO existing FROM pos.local_customer WHERE branch_id=b AND customer_id=coalesce((SELECT customer_id FROM pos.customer_alias WHERE alias_id=target AND branch_id=b),target);
 IF existing.global_id IS DISTINCT FROM coalesce(global_value,existing.global_id) OR existing.cloud_id IS DISTINCT FROM coalesce(cloud_value,existing.cloud_id) THEN
  PERFORM pos.record_audit_event(md5(event::text||':ack')::uuid,b,who,terminal,'CUSTOMER_SYNC_APPLIED',existing.customer_id,NULL,jsonb_build_object('customerId',existing.customer_id),NULL,NULL,NULL,'CLOUD');
 END IF;
 UPDATE pos.customer_sync_pending SET state='ACKNOWLEDGED',acknowledged_at=clock_timestamp(),ack_reference=ack,ack_global=global_value,ack_cloud=cloud_value WHERE event_id=event;
 UPDATE pos.local_customer SET global_id=coalesce(global_value,global_id),cloud_id=coalesce(cloud_value,cloud_id),
 sync_state=CASE WHEN EXISTS(SELECT 1 FROM pos.customer_sync_pending p WHERE p.branch_id=b AND p.state='PENDING' AND (p.customer_id=existing.customer_id OR p.customer_id IN (SELECT alias_id FROM pos.customer_alias WHERE customer_id=existing.customer_id AND branch_id=b))) THEN 'PENDING' ELSE 'SYNCED' END WHERE customer_id=existing.customer_id;
END $$;
REVOKE ALL ON FUNCTION pos.apply_customer_remote(text,text,uuid,jsonb,uuid,text,jsonb),pos.ack_customer_sync(text,text,uuid,text,text,uuid,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.apply_customer_remote(text,text,uuid,jsonb,uuid,text,jsonb),pos.ack_customer_sync(text,text,uuid,text,text,uuid,bigint) TO pos_runtime;
