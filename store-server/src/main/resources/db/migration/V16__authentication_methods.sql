-- Credential authority remains the existing restricted provisioning paths. These triggers
-- preserve hashes verbatim while presenting an extensible method structure to readers.
ALTER TABLE pos.staff ADD COLUMN account_type varchar(10) NOT NULL DEFAULT 'LOCAL'
 CHECK(account_type IN ('LOCAL','CLOUD'));
CREATE TABLE pos.staff_auth_method (
 credential_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 branch_id integer NOT NULL, staff_id uuid NOT NULL,
 account_type varchar(10) NOT NULL CHECK(account_type IN ('LOCAL','CLOUD')),
 method varchar(20) NOT NULL CHECK(method IN ('PIN','PASSWORD','FINGERPRINT','RFID_NFC')),
 verifier text NOT NULL, lookup char(64), credential_version bigint NOT NULL,
 enabled boolean NOT NULL,
 FOREIGN KEY(branch_id,staff_id) REFERENCES pos.staff(branch_id,staff_id) ON DELETE CASCADE,
 UNIQUE(branch_id,staff_id,method),
 CHECK(method<>'PIN' OR lookup IS NOT NULL),
 CHECK(method IN ('PIN','PASSWORD') OR NOT enabled)
);
CREATE UNIQUE INDEX local_pin_unique ON pos.staff_auth_method(branch_id,lookup)
 WHERE method='PIN' AND account_type='LOCAL';
INSERT INTO pos.staff_auth_method(branch_id,staff_id,account_type,method,verifier,credential_version,enabled)
 SELECT branch_id,staff_id,account_type,'PASSWORD',password_hash,credential_version,enabled AND password_login_enabled FROM pos.staff;
INSERT INTO pos.staff_auth_method(branch_id,staff_id,account_type,method,verifier,lookup,credential_version,enabled)
 SELECT s.branch_id,s.staff_id,s.account_type,'PIN',p.hash,p.lookup,s.credential_version,s.enabled
 FROM pos.staff s JOIN pos.staff_pin p USING(branch_id,staff_id);
CREATE FUNCTION pos.refresh_auth_methods() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer; who uuid; s pos.staff;
BEGIN
 b=COALESCE(NEW.branch_id,OLD.branch_id); who=COALESCE(NEW.staff_id,OLD.staff_id);
 SELECT * INTO s FROM pos.staff WHERE branch_id=b AND staff_id=who;
 IF NOT FOUND THEN RETURN NULL; END IF;
 INSERT INTO pos.staff_auth_method(branch_id,staff_id,account_type,method,verifier,credential_version,enabled)
 VALUES(b,who,s.account_type,'PASSWORD',s.password_hash,s.credential_version,s.enabled AND s.password_login_enabled)
 ON CONFLICT(branch_id,staff_id,method) DO UPDATE SET account_type=excluded.account_type,verifier=excluded.verifier,credential_version=excluded.credential_version,enabled=excluded.enabled;
 IF EXISTS(SELECT 1 FROM pos.staff_pin WHERE branch_id=b AND staff_id=who) THEN
  INSERT INTO pos.staff_auth_method(branch_id,staff_id,account_type,method,verifier,lookup,credential_version,enabled)
  SELECT b,who,s.account_type,'PIN',hash,lookup,s.credential_version,s.enabled FROM pos.staff_pin WHERE branch_id=b AND staff_id=who
  ON CONFLICT(branch_id,staff_id,method) DO UPDATE SET account_type=excluded.account_type,verifier=excluded.verifier,lookup=excluded.lookup,credential_version=excluded.credential_version,enabled=excluded.enabled;
 ELSE
  DELETE FROM pos.staff_auth_method WHERE branch_id=b AND staff_id=who AND method='PIN';
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER staff_auth_methods AFTER INSERT OR UPDATE OF password_hash,password_login_enabled,credential_version,enabled,account_type ON pos.staff FOR EACH ROW EXECUTE FUNCTION pos.refresh_auth_methods();
CREATE TRIGGER pin_auth_methods AFTER INSERT OR UPDATE OR DELETE ON pos.staff_pin FOR EACH ROW EXECUTE FUNCTION pos.refresh_auth_methods();
CREATE TRIGGER invalidate_auth_methods AFTER INSERT OR UPDATE OR DELETE ON pos.staff_auth_method FOR EACH ROW EXECUTE FUNCTION pos.invalidate_module_access();
REVOKE ALL ON FUNCTION pos.refresh_auth_methods() FROM PUBLIC;
REVOKE ALL ON pos.staff_auth_method FROM PUBLIC,pos_runtime;
GRANT SELECT ON pos.staff_auth_method TO pos_runtime;
