-- No default credential, no staff account, no runtime access to the verifier.
CREATE TABLE pos.developer_recovery (
 branch_id integer PRIMARY KEY REFERENCES pos.store_identity(branch_id),
 installation_id uuid NOT NULL,
 cluster_id uuid NOT NULL,
 verifier text NOT NULL,
 enabled boolean NOT NULL DEFAULT false,
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON pos.developer_recovery FROM PUBLIC,pos_runtime;
INSERT INTO pos.audit_event_type(action,category,optional) VALUES
 ('DEVELOPER_RECOVERY_ENROLLED','Security',false),
 ('DEVELOPER_RECOVERY_ROTATED','Security',false),
 ('DEVELOPER_RECOVERY_DISABLED','Security',false),
 ('DEVELOPER_RECOVERY_FAILED','Security',false),
 ('OWNER_PIN_RECOVERED','Security',false);

CREATE FUNCTION pos.developer_recovery_status(proof text,terminal text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer; result jsonb;
BEGIN
 PERFORM pos.require_admin_session(proof,terminal,'module.administration',true);
 SELECT branch_id INTO b FROM pos.module_session WHERE token_hash=proof;
 SELECT jsonb_build_object('configured',true,'enabled',r.enabled,'changedAt',r.changed_at) INTO result
 FROM pos.developer_recovery r JOIN pos.store_identity s USING(branch_id)
 WHERE r.branch_id=b AND r.installation_id=s.installation_id AND r.cluster_id=s.cluster_id;
 RETURN coalesce(result,jsonb_build_object('configured',false,'enabled',false));
END $$;
CREATE FUNCTION pos.disable_developer_recovery(proof text,terminal text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer; actor uuid; installation uuid;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 actor=pos.require_admin_session(proof,terminal,'module.administration',true);
 SELECT installation_id INTO installation FROM pos.store_identity WHERE branch_id=b;
 UPDATE pos.developer_recovery SET enabled=false,changed_at=clock_timestamp() WHERE branch_id=b;
 PERFORM pos.record_audit_event(gen_random_uuid(),b,actor,terminal,'DEVELOPER_RECOVERY_DISABLED',installation,NULL,
   '{"origin":"owner-system-access"}'::jsonb,NULL,NULL,NULL,'LOCAL_MASTER');
END $$;
REVOKE ALL ON FUNCTION pos.developer_recovery_status(text,text),pos.disable_developer_recovery(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.developer_recovery_status(text,text),pos.disable_developer_recovery(text,text) TO pos_runtime;
