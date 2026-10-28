-- Preserve V6 checksums; strengthen replay identity and mandatory-event guarantees additively.
ALTER TABLE pos.audit_tracking_settings ADD CONSTRAINT optional_audit_types_only
 CHECK (event_type IN ('DRAFT_CREATED','DRAFT_CANCELLED','PRICE_CHANGED','CATALOGUE_CHANGED','STAFF_LOGIN','STAFF_LOGOUT'));

CREATE OR REPLACE FUNCTION pos.record_audit_event(e uuid,b integer,actor uuid,terminal text,action_value text,
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
    OR previous.details<>filtered OR previous.terminal_identity IS DISTINCT FROM terminal
    OR previous.authorised_by_user_id IS DISTINCT FROM approver OR previous.transaction_id IS DISTINCT FROM transaction_value
    OR previous.source_system IS DISTINCT FROM source
    OR (occurred IS NOT NULL AND previous.occurred_at IS DISTINCT FROM occurred) THEN
   RAISE EXCEPTION 'Audit identity conflict' USING ERRCODE='23514';
  END IF;
  RETURN;
 END IF;
 IF EXISTS(SELECT 1 FROM pos.audit_tracking_settings s JOIN pos.audit_event_type t ON t.action=s.event_type WHERE s.branch_id=b AND s.event_type=action_value AND t.optional AND NOT s.enabled) THEN RETURN; END IF;
 UPDATE pos.audit_policy SET sequence=sequence+1 WHERE branch_id=b RETURNING sequence INTO n;
 INSERT INTO pos.audit_record(audit_id,branch_id,actor_id,action,aggregate_id,command_id,details,
 terminal_identity,username_snapshot,authorised_by_user_id,entity_type,transaction_id,recorded_at,source_system,correlation_id,branch_sequence,occurred_at)
 VALUES(e,b,actor,action_value,entity,command,filtered,terminal,
 (SELECT username FROM pos.staff WHERE branch_id=b AND staff_id=actor),approver,
 CASE WHEN action_value LIKE 'DRAFT_%' THEN 'ORDER' ELSE 'CONFIGURATION' END,
 transaction_value,clock_timestamp(),source,command,n,coalesce(occurred,clock_timestamp()));
END $$;

