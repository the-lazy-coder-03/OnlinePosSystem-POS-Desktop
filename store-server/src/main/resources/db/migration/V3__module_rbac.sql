-- Additive local-only RBAC. No default staff PINs.
CREATE TABLE pos.rbac_role (
 branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
 name varchar(64) NOT NULL, PRIMARY KEY(branch_id,name)
);
CREATE TABLE pos.role_permission (
 branch_id integer NOT NULL, role_name varchar(64) NOT NULL,
 permission varchar(80) NOT NULL, PRIMARY KEY(branch_id,role_name,permission),
 FOREIGN KEY(branch_id,role_name) REFERENCES pos.rbac_role(branch_id,name) ON DELETE CASCADE
);
CREATE TABLE pos.staff_role (
 branch_id integer NOT NULL, staff_id uuid NOT NULL, role_name varchar(64) NOT NULL,
 PRIMARY KEY(branch_id,staff_id,role_name),
 FOREIGN KEY(branch_id,staff_id) REFERENCES pos.staff(branch_id,staff_id) ON DELETE CASCADE,
 FOREIGN KEY(branch_id,role_name) REFERENCES pos.rbac_role(branch_id,name)
);
CREATE TABLE pos.staff_pin (
 branch_id integer NOT NULL, staff_id uuid NOT NULL, lookup char(64) NOT NULL, hash text NOT NULL,
 PRIMARY KEY(branch_id,staff_id), UNIQUE(branch_id,lookup),
 FOREIGN KEY(branch_id,staff_id) REFERENCES pos.staff(branch_id,staff_id) ON DELETE CASCADE
);
CREATE TABLE pos.module_session (
 token_hash char(64) PRIMARY KEY, branch_id integer NOT NULL, staff_id uuid NOT NULL,
 terminal_identity varchar(512) NOT NULL, module varchar(32) NOT NULL,
 credential_version bigint NOT NULL, expires_at timestamptz NOT NULL,
 FOREIGN KEY(branch_id,staff_id) REFERENCES pos.staff(branch_id,staff_id) ON DELETE CASCADE
);
CREATE TABLE pos.pin_throttle (
 branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
 scope varchar(520) NOT NULL, failures integer NOT NULL DEFAULT 0,
 window_start timestamptz NOT NULL DEFAULT clock_timestamp(), locked_until timestamptz,
 PRIMARY KEY(branch_id,scope)
);
-- Also seed branches created after this migration, and preserve legacy staff defaults.
CREATE FUNCTION pos.seed_rbac() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN
 INSERT INTO pos.rbac_role VALUES (NEW.branch_id,'MANAGER'),(NEW.branch_id,'CASHIER');
 INSERT INTO pos.role_permission SELECT NEW.branch_id,'MANAGER',p FROM unnest(ARRAY[
 'module.sales_orders','module.customers','module.catalogue','module.cash','module.reports','module.administration',
 'staff.manage','rbac.manage']) p;
 INSERT INTO pos.role_permission VALUES (NEW.branch_id,'CASHIER','module.sales_orders');
 RETURN NEW;
END $$;
CREATE TRIGGER seed_rbac AFTER INSERT ON pos.store_identity FOR EACH ROW EXECUTE FUNCTION pos.seed_rbac();
INSERT INTO pos.rbac_role SELECT branch_id,r FROM pos.store_identity CROSS JOIN unnest(ARRAY['MANAGER','CASHIER']) r;
INSERT INTO pos.role_permission SELECT branch_id,'MANAGER',p FROM pos.store_identity CROSS JOIN unnest(ARRAY[
 'module.sales_orders','module.customers','module.catalogue','module.cash','module.reports','module.administration',
 'staff.manage','rbac.manage']) p;
INSERT INTO pos.role_permission SELECT branch_id,'CASHIER','module.sales_orders' FROM pos.store_identity;
INSERT INTO pos.staff_role SELECT branch_id,staff_id,role FROM pos.staff;
CREATE FUNCTION pos.staff_default_role() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
BEGIN
 IF TG_OP='UPDATE' AND OLD.role=NEW.role THEN RETURN NEW; END IF;
 IF TG_OP='UPDATE' THEN DELETE FROM pos.staff_role WHERE staff_id=NEW.staff_id AND role_name=OLD.role; END IF;
 INSERT INTO pos.staff_role VALUES(NEW.branch_id,NEW.staff_id,NEW.role) ON CONFLICT DO NOTHING;
 RETURN NEW;
END $$;
CREATE TRIGGER staff_default_role AFTER INSERT OR UPDATE OF role ON pos.staff FOR EACH ROW EXECUTE FUNCTION pos.staff_default_role();
GRANT SELECT ON pos.rbac_role,pos.role_permission,pos.staff_role,pos.staff_pin TO pos_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON pos.module_session,pos.pin_throttle TO pos_runtime;
REVOKE ALL ON FUNCTION pos.staff_default_role() FROM PUBLIC;
REVOKE ALL ON FUNCTION pos.seed_rbac() FROM PUBLIC;

CREATE FUNCTION pos.invalidate_module_access() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer; target_staff uuid; target_role varchar(64);
BEGIN
 b=COALESCE(NEW.branch_id,OLD.branch_id);
 IF TG_TABLE_NAME='role_permission' THEN
   target_role=COALESCE(NEW.role_name,OLD.role_name);
   DELETE FROM pos.module_session WHERE branch_id=b AND staff_id IN (SELECT staff_id FROM pos.staff_role WHERE branch_id=b AND role_name=target_role);
   DELETE FROM pos.staff_session WHERE branch_id=b AND staff_id IN (SELECT staff_id FROM pos.staff_role WHERE branch_id=b AND role_name=target_role);
 ELSE
   target_staff=COALESCE(NEW.staff_id,OLD.staff_id);
   DELETE FROM pos.module_session WHERE branch_id=b AND staff_id=target_staff;
   DELETE FROM pos.staff_session WHERE branch_id=b AND staff_id=target_staff;
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER invalidate_roles AFTER INSERT OR UPDATE OR DELETE ON pos.staff_role FOR EACH ROW EXECUTE FUNCTION pos.invalidate_module_access();
CREATE TRIGGER invalidate_permissions AFTER INSERT OR UPDATE OR DELETE ON pos.role_permission FOR EACH ROW EXECUTE FUNCTION pos.invalidate_module_access();
CREATE TRIGGER invalidate_pins AFTER INSERT OR UPDATE OR DELETE ON pos.staff_pin FOR EACH ROW EXECUTE FUNCTION pos.invalidate_module_access();
CREATE TRIGGER invalidate_staff AFTER UPDATE OF password_hash,credential_version,enabled,role,username OR DELETE ON pos.staff FOR EACH ROW EXECUTE FUNCTION pos.invalidate_module_access();
CREATE FUNCTION pos.guard_rbac_administrator() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;
BEGIN
 b=COALESCE(NEW.branch_id,OLD.branch_id);
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 IF (EXISTS(SELECT 1 FROM pos.staff WHERE branch_id=b) OR (TG_TABLE_NAME='staff' AND TG_OP='DELETE')) AND NOT EXISTS(
 SELECT 1 FROM pos.staff s JOIN pos.staff_role r ON r.staff_id=s.staff_id AND r.branch_id=s.branch_id
 JOIN pos.role_permission p ON p.branch_id=r.branch_id AND p.role_name=r.role_name
 WHERE s.branch_id=b AND s.enabled AND p.permission='rbac.manage') THEN
 RAISE EXCEPTION 'The final enabled RBAC administrator cannot be removed' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER guard_staff_rbac AFTER INSERT OR UPDATE OR DELETE ON pos.staff DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pos.guard_rbac_administrator();
CREATE CONSTRAINT TRIGGER guard_assignments_rbac AFTER INSERT OR UPDATE OR DELETE ON pos.staff_role DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pos.guard_rbac_administrator();
CREATE CONSTRAINT TRIGGER guard_permissions_rbac AFTER INSERT OR UPDATE OR DELETE ON pos.role_permission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pos.guard_rbac_administrator();
REVOKE ALL ON FUNCTION pos.invalidate_module_access(),pos.guard_rbac_administrator() FROM PUBLIC;
