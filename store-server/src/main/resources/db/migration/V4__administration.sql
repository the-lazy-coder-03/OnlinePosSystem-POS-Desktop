-- Local administration only. No implicit owner and no default staff PINs.
ALTER TABLE pos.staff ADD COLUMN password_login_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE pos.rbac_role ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0);
CREATE TABLE pos.store_owner (
 branch_id integer PRIMARY KEY REFERENCES pos.store_identity(branch_id),
 staff_id uuid NOT NULL,
 FOREIGN KEY(branch_id,staff_id) REFERENCES pos.staff(branch_id,staff_id)
);
GRANT SELECT ON pos.store_owner TO pos_runtime;
INSERT INTO pos.role_permission SELECT branch_id,name,'audit.view' FROM pos.rbac_role WHERE name='MANAGER' ON CONFLICT DO NOTHING;
CREATE OR REPLACE FUNCTION pos.seed_rbac() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN
 INSERT INTO pos.rbac_role(branch_id,name) VALUES(NEW.branch_id,'MANAGER'),(NEW.branch_id,'CASHIER');
 INSERT INTO pos.role_permission SELECT NEW.branch_id,'MANAGER',p FROM unnest(ARRAY[
 'module.sales_orders','module.customers','module.catalogue','module.cash','module.reports','module.administration',
 'staff.manage','rbac.manage','audit.view']) p;
 INSERT INTO pos.role_permission VALUES(NEW.branch_id,'CASHIER','module.sales_orders');
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION pos.guard_rbac_administrator() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;
BEGIN
 IF TG_TABLE_NAME='staff' AND TG_OP='UPDATE' THEN
  IF OLD.enabled=NEW.enabled AND OLD.role=NEW.role THEN RETURN NULL; END IF;
 END IF;
 b=COALESCE(NEW.branch_id,OLD.branch_id);
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 IF (EXISTS(SELECT 1 FROM pos.staff WHERE branch_id=b) OR (TG_TABLE_NAME='staff' AND TG_OP='DELETE')) AND NOT EXISTS(
 SELECT 1 FROM pos.staff s WHERE s.branch_id=b AND s.enabled AND (
 EXISTS(SELECT 1 FROM pos.store_owner o WHERE o.branch_id=b AND o.staff_id=s.staff_id) OR
 EXISTS(SELECT 1 FROM pos.staff_role r JOIN pos.role_permission p ON p.branch_id=r.branch_id AND p.role_name=r.role_name
 WHERE r.staff_id=s.staff_id AND r.branch_id=b AND p.permission='rbac.manage'))) THEN
 RAISE EXCEPTION 'The final enabled RBAC administrator cannot be removed' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE FUNCTION pos.protect_store_owner() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM pos.store_owner WHERE branch_id=OLD.branch_id AND staff_id=OLD.staff_id) THEN
   IF TG_OP='DELETE' OR NOT NEW.enabled THEN
     RAISE EXCEPTION 'Transfer ownership before disabling the owner' USING ERRCODE='23514';
   END IF;
   IF current_user<>'pos_owner' THEN
     RAISE EXCEPTION 'Owner account requires protected administration' USING ERRCODE='42501';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_store_owner BEFORE UPDATE OF username,password_hash,password_login_enabled,enabled,role,credential_version OR DELETE ON pos.staff FOR EACH ROW EXECUTE FUNCTION pos.protect_store_owner();
CREATE FUNCTION pos.owner_changed() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pos.staff WHERE branch_id=NEW.branch_id AND staff_id=NEW.staff_id AND enabled) THEN
  RAISE EXCEPTION 'Owner must be enabled' USING ERRCODE='23514';
 END IF;
 UPDATE pos.staff SET credential_version=credential_version+1 WHERE branch_id=NEW.branch_id AND staff_id IN (NEW.staff_id,OLD.staff_id);
 RETURN NULL;
END $$;
CREATE TRIGGER owner_changed AFTER INSERT OR UPDATE ON pos.store_owner FOR EACH ROW EXECUTE FUNCTION pos.owner_changed();
DROP TRIGGER invalidate_staff ON pos.staff;
CREATE TRIGGER invalidate_staff AFTER UPDATE OF password_hash,password_login_enabled,credential_version,enabled,role,username OR DELETE ON pos.staff FOR EACH ROW EXECUTE FUNCTION pos.invalidate_module_access();
CREATE FUNCTION pos.admin_permissions(b integer,who uuid) RETURNS SETOF text LANGUAGE sql STABLE SET search_path=pg_catalog,pos AS $$
 SELECT p.permission::text FROM pos.staff_role r JOIN pos.role_permission p ON p.branch_id=r.branch_id AND p.role_name=r.role_name WHERE r.branch_id=b AND r.staff_id=who
 UNION SELECT p FROM unnest(ARRAY['module.administration','staff.manage','rbac.manage','audit.view']) p WHERE EXISTS(SELECT 1 FROM pos.store_owner WHERE branch_id=b AND staff_id=who)
$$;
-- Functions accept a hashed, live terminal-bound session, never a caller-supplied actor identity.
CREATE FUNCTION pos.require_admin_session(proof text,terminal text,required_permission text,owner_only boolean) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE who uuid; b integer;
BEGIN
 SELECT t.staff_id,t.branch_id INTO who,b FROM pos.module_session t JOIN pos.staff s ON s.staff_id=t.staff_id AND s.branch_id=t.branch_id
 WHERE t.token_hash=proof AND t.terminal_identity=terminal AND t.module='ADMINISTRATION' AND t.expires_at>clock_timestamp() AND s.enabled AND t.credential_version=s.credential_version;
 IF who IS NULL THEN RAISE EXCEPTION 'Administration session required' USING ERRCODE='28000'; END IF;
 IF required_permission NOT IN (SELECT pos.admin_permissions(b,who)) OR
 (owner_only AND NOT EXISTS(SELECT 1 FROM pos.store_owner WHERE branch_id=b AND staff_id=who)) THEN
  RAISE EXCEPTION 'Administration permission required' USING ERRCODE='42501';
 END IF;
 RETURN who;
END $$;
CREATE FUNCTION pos.apply_admin_change(proof text,terminal text,kind text,data jsonb) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE who uuid; b integer; target uuid; old_revision bigint; next_revision bigint; owner_actor boolean; requested text[]; r text;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 who=pos.require_admin_session(proof,terminal,CASE kind WHEN 'role' THEN 'rbac.manage' ELSE 'staff.manage' END,kind='pin');
 owner_actor=EXISTS(SELECT 1 FROM pos.store_owner WHERE branch_id=b AND staff_id=who);
 IF kind='role' THEN
  IF (data->>'name') !~ '^[A-Z][A-Z0-9_]{0,63}$' THEN RAISE EXCEPTION 'Invalid role' USING ERRCODE='23514'; END IF;
  SELECT array_agg(v) INTO requested FROM jsonb_array_elements_text(data->'permissions') v;
  requested=coalesce(requested,ARRAY[]::text[]);
  IF EXISTS(SELECT 1 FROM unnest(requested) p WHERE p NOT IN ('module.sales_orders','module.customers','module.catalogue','module.cash','module.reports','module.administration','staff.manage','rbac.manage','audit.view')) THEN
   RAISE EXCEPTION 'Unsupported permission' USING ERRCODE='23514';
  END IF;
  IF NOT owner_actor AND EXISTS(SELECT 1 FROM unnest(requested) p WHERE p NOT IN (SELECT pos.admin_permissions(b,who))) THEN
   RAISE EXCEPTION 'Cannot delegate an unheld permission' USING ERRCODE='42501';
  END IF;
  SELECT revision INTO old_revision FROM pos.rbac_role WHERE branch_id=b AND name=data->>'name';
  IF (old_revision IS NULL AND (data->>'expectedRevision')::bigint<>-1) OR
     (old_revision IS NOT NULL AND old_revision<>(data->>'expectedRevision')::bigint) THEN
    RAISE EXCEPTION 'Role changed; reload' USING ERRCODE='23514';
  END IF;
  -- Non-owners cannot strip permissions from a more privileged existing role either.
  IF NOT owner_actor AND EXISTS(SELECT 1 FROM pos.role_permission WHERE branch_id=b AND role_name=data->>'name' AND permission NOT IN (SELECT pos.admin_permissions(b,who))) THEN
   RAISE EXCEPTION 'Cannot modify a more privileged role' USING ERRCODE='42501';
  END IF;
  next_revision=coalesce(old_revision+1,0);
  INSERT INTO pos.rbac_role(branch_id,name,revision) VALUES(b,data->>'name',next_revision) ON CONFLICT(branch_id,name) DO UPDATE SET revision=excluded.revision;
  DELETE FROM pos.role_permission WHERE branch_id=b AND role_name=data->>'name';
  INSERT INTO pos.role_permission SELECT DISTINCT b,data->>'name',p FROM unnest(requested) p;
  RETURN next_revision;
 END IF;
 target=(data->>'staffId')::uuid;
 SELECT revision INTO old_revision FROM pos.staff WHERE branch_id=b AND staff_id=target;
 IF old_revision IS NOT NULL AND old_revision<>(data->>'expectedRevision')::bigint THEN
  RAISE EXCEPTION 'Staff changed; reload' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM pos.store_owner WHERE branch_id=b AND staff_id=target) AND (NOT owner_actor OR NOT (data->>'enabled')::boolean AND kind='staff') THEN
  RAISE EXCEPTION 'Owner account is protected' USING ERRCODE='42501';
 END IF;
 IF kind='pin' THEN
  IF old_revision IS NULL THEN RAISE EXCEPTION 'Unknown staff' USING ERRCODE='23514'; END IF;
  IF (data->>'lookup') !~ '^[a-f0-9]{64}$' OR (data->>'hash') NOT LIKE '600000:%' THEN RAISE EXCEPTION 'Invalid PIN credential' USING ERRCODE='23514'; END IF;
  INSERT INTO pos.staff_pin VALUES(b,target,data->>'lookup',data->>'hash') ON CONFLICT(branch_id,staff_id) DO UPDATE SET lookup=excluded.lookup,hash=excluded.hash;
 ELSE
  IF kind<>'staff' OR (data->>'username') !~ '^[a-z0-9._-]{3,64}$' OR (old_revision IS NULL AND (data->>'expectedRevision')::bigint<>-1) THEN RAISE EXCEPTION 'Invalid staff' USING ERRCODE='23514'; END IF;
  SELECT array_agg(v) INTO requested FROM jsonb_array_elements_text(data->'roles') v;
  IF coalesce(array_length(requested,1),0)=0 OR EXISTS(SELECT 1 FROM unnest(requested) n WHERE NOT EXISTS(SELECT 1 FROM pos.rbac_role WHERE branch_id=b AND name=n)) THEN
   RAISE EXCEPTION 'Valid roles required' USING ERRCODE='23514';
  END IF;
  IF NOT owner_actor AND EXISTS(SELECT 1 FROM pos.role_permission WHERE branch_id=b AND role_name=ANY(requested) AND permission NOT IN (SELECT pos.admin_permissions(b,who))) THEN
   RAISE EXCEPTION 'Cannot assign a more privileged role' USING ERRCODE='42501';
  END IF;
  IF NOT owner_actor AND EXISTS(SELECT 1 FROM pos.staff_role sr JOIN pos.role_permission rp ON rp.branch_id=sr.branch_id AND rp.role_name=sr.role_name WHERE sr.branch_id=b AND sr.staff_id=target AND rp.permission NOT IN (SELECT pos.admin_permissions(b,who))) THEN
   RAISE EXCEPTION 'Cannot modify more privileged staff' USING ERRCODE='42501';
  END IF;
  IF old_revision IS NULL THEN
   INSERT INTO pos.staff(staff_id,branch_id,username,password_hash,password_login_enabled,role,enabled) VALUES(target,b,data->>'username',data->>'unusablePasswordHash',false,'CASHIER',(data->>'enabled')::boolean);
  ELSE
   UPDATE pos.staff SET username=data->>'username',enabled=(data->>'enabled')::boolean WHERE branch_id=b AND staff_id=target;
  END IF;
  DELETE FROM pos.staff_role WHERE branch_id=b AND staff_id=target;
  INSERT INTO pos.staff_role SELECT DISTINCT b,target,n FROM unnest(requested) n;
 END IF;
 next_revision=coalesce(old_revision+1,0);
 UPDATE pos.staff SET credential_version=credential_version+1,revision=next_revision WHERE branch_id=b AND staff_id=target;
 RETURN next_revision;
END $$;
REVOKE ALL ON FUNCTION pos.admin_permissions(integer,uuid),pos.require_admin_session(text,text,text,boolean),pos.apply_admin_change(text,text,text,jsonb),pos.protect_store_owner(),pos.owner_changed() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.admin_permissions(integer,uuid),pos.apply_admin_change(text,text,text,jsonb) TO pos_runtime;
