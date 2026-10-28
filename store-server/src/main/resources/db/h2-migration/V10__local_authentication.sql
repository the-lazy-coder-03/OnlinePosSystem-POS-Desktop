-- Local identities are independent of website customers. No staff or default PIN is seeded.
CREATE TABLE dev_meta.local_staff (
 staff_id UUID PRIMARY KEY, branch_id INT NOT NULL REFERENCES public.branch(branch_id),
 username VARCHAR(64) NOT NULL, account_type VARCHAR(10) NOT NULL DEFAULT 'LOCAL' CHECK(account_type='LOCAL'),
 role_name VARCHAR(16) NOT NULL CHECK(role_name IN ('MANAGER','CASHIER')),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, credential_version BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0, UNIQUE(branch_id,username), UNIQUE(branch_id,staff_id)
);
CREATE TABLE dev_meta.local_auth_method (
 credential_id UUID PRIMARY KEY, branch_id INT NOT NULL, staff_id UUID NOT NULL,
 method VARCHAR(20) NOT NULL CHECK(method IN ('PIN','PASSWORD','FINGERPRINT','RFID_NFC')),
 verifier VARCHAR(1000) NOT NULL, lookup VARCHAR(64), enabled BOOLEAN NOT NULL DEFAULT TRUE,
 credential_version BIGINT NOT NULL, UNIQUE(branch_id,staff_id,method),
 FOREIGN KEY(branch_id,staff_id) REFERENCES dev_meta.local_staff(branch_id,staff_id),
 UNIQUE(branch_id,lookup), CHECK(method<>'PIN' OR lookup IS NOT NULL),
 CHECK(method IN ('PIN','PASSWORD') OR NOT enabled)
);
CREATE TABLE dev_meta.local_owner (
 branch_id INT PRIMARY KEY, staff_id UUID NOT NULL,
 FOREIGN KEY(branch_id,staff_id) REFERENCES dev_meta.local_staff(branch_id,staff_id)
);
CREATE TABLE dev_meta.local_module_session (
 token_hash VARCHAR(64) PRIMARY KEY, branch_id INT NOT NULL, staff_id UUID NOT NULL,
 terminal_identity VARCHAR(512) NOT NULL, module VARCHAR(32) NOT NULL,
 credential_version BIGINT NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 owner_verified BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(branch_id,staff_id) REFERENCES dev_meta.local_staff(branch_id,staff_id)
);
CREATE TABLE dev_meta.local_pin_throttle (
 branch_id INT NOT NULL REFERENCES public.branch(branch_id), scope VARCHAR(520) NOT NULL,
 failures INT NOT NULL DEFAULT 0, cooldown_attempts BIGINT NOT NULL DEFAULT 0, window_start TIMESTAMP WITH TIME ZONE NOT NULL,
 locked_until TIMESTAMP WITH TIME ZONE, PRIMARY KEY(branch_id,scope)
);
