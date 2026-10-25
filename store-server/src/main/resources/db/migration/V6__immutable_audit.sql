ALTER TABLE pos.module_session ADD COLUMN owner_verified_at timestamptz;

-- Additive audit storage. Legacy unknown metadata remains NULL.
ALTER TABLE pos.audit_record
 ADD COLUMN terminal_identity varchar(512), ADD COLUMN username_snapshot varchar(64),
 ADD COLUMN authorised_by_user_id uuid, ADD COLUMN entity_type varchar(64),
 ADD COLUMN transaction_id uuid, ADD COLUMN recorded_at timestamptz,
 ADD COLUMN source_system varchar(64), ADD COLUMN correlation_id uuid,
 ADD COLUMN branch_sequence bigint;
ALTER TABLE pos.audit_record ALTER COLUMN actor_id DROP NOT NULL;
CREATE INDEX audit_branch_time ON pos.audit_record(branch_id,occurred_at DESC,audit_id DESC);
CREATE INDEX audit_transaction ON pos.audit_record(branch_id,transaction_id,occurred_at DESC,audit_id DESC);
CREATE INDEX audit_actor ON pos.audit_record(branch_id,actor_id,occurred_at DESC,audit_id DESC);
CREATE INDEX audit_action ON pos.audit_record(branch_id,action,occurred_at DESC,audit_id DESC);
CREATE INDEX audit_terminal ON pos.audit_record(branch_id,terminal_identity,occurred_at DESC,audit_id DESC);
CREATE UNIQUE INDEX audit_branch_sequence ON pos.audit_record(branch_id,branch_sequence);

CREATE TABLE pos.audit_event_type(action varchar(64) PRIMARY KEY,category varchar(32) NOT NULL,optional boolean NOT NULL);
INSERT INTO pos.audit_event_type VALUES
 ('DRAFT_CREATED','Orders',true),('DRAFT_CANCELLED','Orders',true),
 ('PRICE_CHANGED','Catalogue',true),('CATALOGUE_CHANGED','Catalogue',true),
 ('STAFF_LOGIN','Staff',true),('STAFF_LOGOUT','Staff',true),
 ('CATALOGUE_BOOTSTRAPPED','System',false),('STAFF_BOOTSTRAPPED','Security',false),
 ('STAFF_CHANGED','Security',false),('ADMIN_STAFF_CHANGED','Security',false),
 ('ADMIN_ROLE_CHANGED','Security',false),('ADMIN_PIN_CHANGED','Security',false),
 ('RBAC_SET_ROLE','Security',false),('RBAC_ASSIGN_ROLES','Security',false),
 ('RBAC_SET_PIN','Security',false),('RBAC_SET_OWNER','Security',false),
 ('AUDIT_SETTING_CHANGED','Security',false),('OWNER_PIN_SUCCEEDED','Security',false),
 ('OWNER_PIN_FAILED','Security',false),('AUDIT_ACCESS_REFUSED','Security',false),('AUDIT_INTEGRITY_FAILED','Security',false);
CREATE TABLE pos.audit_policy (
 branch_id integer PRIMARY KEY REFERENCES pos.store_identity(branch_id),
 revision bigint NOT NULL DEFAULT 0, updated_at timestamptz, updated_by_owner_id uuid,
 sequence bigint NOT NULL DEFAULT 0
);
CREATE TABLE pos.audit_tracking_settings (
 branch_id integer NOT NULL REFERENCES pos.audit_policy(branch_id),
 event_type varchar(64) NOT NULL REFERENCES pos.audit_event_type(action), enabled boolean NOT NULL DEFAULT true,
 PRIMARY KEY(branch_id,event_type)
);
CREATE TABLE pos.audit_approval (
 approval_id uuid PRIMARY KEY, branch_id integer NOT NULL, initiator_hash char(64) NOT NULL,
 owner_hash char(64) NOT NULL, owner_id uuid NOT NULL, terminal_identity varchar(512) NOT NULL,
 settings jsonb NOT NULL, expected_revision bigint NOT NULL, expires_at timestamptz NOT NULL,
 consumed boolean NOT NULL DEFAULT false
);
CREATE FUNCTION pos.seed_audit_policy() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN
 INSERT INTO pos.audit_policy(branch_id) VALUES(NEW.branch_id);
 INSERT INTO pos.audit_tracking_settings SELECT NEW.branch_id,action,true FROM pos.audit_event_type WHERE optional;
 RETURN NEW;
END $$;
CREATE TRIGGER seed_audit_policy AFTER INSERT ON pos.store_identity FOR EACH ROW EXECUTE FUNCTION pos.seed_audit_policy();
INSERT INTO pos.audit_policy(branch_id) SELECT branch_id FROM pos.store_identity;
INSERT INTO pos.audit_tracking_settings SELECT branch_id,action,true FROM pos.store_identity CROSS JOIN pos.audit_event_type WHERE optional;

CREATE FUNCTION pos.guard_audit_history() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN RAISE EXCEPTION 'Audit history is append-only' USING ERRCODE='42501'; END $$;
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON pos.audit_record FOR EACH STATEMENT EXECUTE FUNCTION pos.guard_audit_history();
CREATE TRIGGER immutable_audit_truncate BEFORE TRUNCATE ON pos.audit_record FOR EACH STATEMENT EXECUTE FUNCTION pos.guard_audit_history();
REVOKE INSERT,UPDATE,DELETE,TRUNCATE ON pos.audit_record FROM pos_runtime;
GRANT SELECT ON pos.audit_event_type,pos.audit_policy,pos.audit_tracking_settings TO pos_runtime;

-- One shared ordering/policy lock with existing command processing. Outbox generation is independent.
CREATE FUNCTION pos.record_audit_event(e uuid,b integer,actor uuid,terminal text,action_value text,
 entity uuid,command uuid,details_value jsonb,approver uuid,transaction_value uuid,occurred timestamptz,source text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE n bigint; previous pos.audit_record; filtered jsonb;
BEGIN
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 IF NOT EXISTS(SELECT 1 FROM pos.audit_event_type WHERE action=action_value) THEN
  RAISE EXCEPTION 'Unsupported audit action' USING ERRCODE='23514';
 END IF;
 -- Last-line defence: only explicitly safe structural fields can enter audit JSON.
 SELECT coalesce(jsonb_object_agg(key,value),'{}') INTO filtered FROM jsonb_each(details_value)
 WHERE key IN ('orderId','state','revision','priceId','amount','available','staffId','username','enabled',
 'role','roles','name','permissions','pinConfigured','table','operation','keys','changes','subject','origin',
 'configurationRevision','oldSettings','newSettings','oldValues','newValues','tables','recordsChanged','reason','module','count','sourceDigest');
 SELECT * INTO previous FROM pos.audit_record WHERE audit_id=e;
 IF FOUND THEN
  IF previous.branch_id<>b OR previous.actor_id IS DISTINCT FROM actor OR previous.action<>action_value
    OR previous.command_id IS DISTINCT FROM command OR previous.aggregate_id<>entity
    OR previous.details<>filtered OR previous.terminal_identity IS DISTINCT FROM terminal THEN
   RAISE EXCEPTION 'Audit identity conflict' USING ERRCODE='23514';
  END IF;
  RETURN;
 END IF;
 IF EXISTS(SELECT 1 FROM pos.audit_tracking_settings WHERE branch_id=b AND event_type=action_value AND NOT enabled) THEN RETURN; END IF;
 UPDATE pos.audit_policy SET sequence=sequence+1 WHERE branch_id=b RETURNING sequence INTO n;
 INSERT INTO pos.audit_record(audit_id,branch_id,actor_id,action,aggregate_id,command_id,details,
 terminal_identity,username_snapshot,authorised_by_user_id,entity_type,transaction_id,recorded_at,source_system,correlation_id,branch_sequence,occurred_at)
 VALUES(e,b,actor,action_value,entity,command,filtered,terminal,
 (SELECT username FROM pos.staff WHERE branch_id=b AND staff_id=actor),approver,
 CASE WHEN action_value LIKE 'DRAFT_%' THEN 'ORDER' ELSE 'CONFIGURATION' END,
 transaction_value,clock_timestamp(),source,command,n,coalesce(occurred,clock_timestamp()));
END $$;

CREATE FUNCTION pos.issue_audit_approval(id uuid,initiator text,owner_proof text,terminal text,requested jsonb,expected bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE owner_id uuid; b integer;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 PERFORM pos.require_admin_session(initiator,terminal,'module.administration',false);
 owner_id=pos.require_admin_session(owner_proof,terminal,'module.administration',true);
 IF NOT EXISTS(SELECT 1 FROM pos.module_session WHERE token_hash=owner_proof AND owner_verified_at>clock_timestamp()-interval '60 seconds') THEN
  RAISE EXCEPTION 'Fresh owner PIN required' USING ERRCODE='42501';
 END IF;
 IF jsonb_typeof(requested)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(requested))<>(SELECT count(*) FROM pos.audit_event_type WHERE optional)
 OR EXISTS(SELECT 1 FROM jsonb_each(requested) v LEFT JOIN pos.audit_event_type t ON t.action=v.key
   WHERE t.action IS NULL OR NOT t.optional OR jsonb_typeof(v.value)<>'boolean') THEN
  RAISE EXCEPTION 'Invalid policy' USING ERRCODE='23514';
 END IF;
 INSERT INTO pos.audit_approval VALUES(id,b,initiator,owner_proof,owner_id,terminal,requested,expected,clock_timestamp()+interval '60 seconds',false);
END $$;

CREATE FUNCTION pos.apply_audit_policy(id uuid,initiator text,owner_proof text,terminal text,requested jsonb,expected bigint,command uuid)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE approval pos.audit_approval; actor uuid; b integer; previous jsonb; revision_value bigint; owner_id uuid;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 actor=pos.require_admin_session(initiator,terminal,'module.administration',false);
 owner_id=pos.require_admin_session(owner_proof,terminal,'module.administration',true);
 IF NOT EXISTS(SELECT 1 FROM pos.module_session WHERE token_hash=owner_proof AND owner_verified_at>clock_timestamp()-interval '60 seconds') THEN
  RAISE EXCEPTION 'Fresh owner PIN required' USING ERRCODE='42501';
 END IF;
 SELECT * INTO approval FROM pos.audit_approval WHERE approval_id=id FOR UPDATE;
 IF NOT FOUND OR approval.consumed OR approval.expires_at<=clock_timestamp()
 OR approval.branch_id<>b OR approval.initiator_hash<>initiator OR approval.owner_hash<>owner_proof
 OR approval.owner_id<>owner_id OR approval.terminal_identity<>terminal
 OR approval.settings<>requested OR approval.expected_revision<>expected THEN
  RAISE EXCEPTION 'Owner approval required' USING ERRCODE='42501';
 END IF;
 IF (SELECT revision FROM pos.audit_policy WHERE branch_id=b)<>expected THEN
  RAISE EXCEPTION 'Policy changed; reload' USING ERRCODE='23514';
 END IF;
 SELECT jsonb_object_agg(event_type,enabled) INTO previous FROM pos.audit_tracking_settings WHERE branch_id=b;
 UPDATE pos.audit_tracking_settings SET enabled=(requested->>event_type)::boolean WHERE branch_id=b;
 UPDATE pos.audit_policy SET revision=revision+1,updated_at=clock_timestamp(),updated_by_owner_id=owner_id WHERE branch_id=b RETURNING revision INTO revision_value;
 UPDATE pos.audit_approval SET consumed=true WHERE approval_id=id;
 UPDATE pos.module_session SET owner_verified_at=NULL WHERE token_hash=owner_proof;
 PERFORM pos.record_audit_event(id,b,actor,terminal,'OWNER_PIN_SUCCEEDED',command,command,
 jsonb_build_object('configurationRevision',revision_value),owner_id,NULL,NULL,'LOCAL_MASTER');
 PERFORM pos.record_audit_event(command,b,actor,terminal,'AUDIT_SETTING_CHANGED',command,command,
 jsonb_build_object('oldSettings',previous,'newSettings',requested,'configurationRevision',revision_value),owner_id,NULL,NULL,'LOCAL_MASTER');
 RETURN revision_value;
END $$;
REVOKE ALL ON FUNCTION pos.seed_audit_policy(),pos.guard_audit_history(),pos.record_audit_event(uuid,integer,uuid,text,text,uuid,uuid,jsonb,uuid,uuid,timestamptz,text),
 pos.issue_audit_approval(uuid,text,text,text,jsonb,bigint),pos.apply_audit_policy(uuid,text,text,text,jsonb,bigint,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.record_audit_event(uuid,integer,uuid,text,text,uuid,uuid,jsonb,uuid,uuid,timestamptz,text),
 pos.issue_audit_approval(uuid,text,text,text,jsonb,bigint),pos.apply_audit_policy(uuid,text,text,text,jsonb,bigint,uuid) TO pos_runtime;
