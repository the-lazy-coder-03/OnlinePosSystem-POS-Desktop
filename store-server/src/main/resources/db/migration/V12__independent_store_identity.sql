-- Local POS database only. Never run against the cloud database.
ALTER TABLE pos.store_identity DROP CONSTRAINT store_identity_branch_id_check;
ALTER TABLE pos.store_identity ADD CONSTRAINT store_identity_positive_branch CHECK(branch_id>0);
ALTER TABLE pos.store_identity ADD COLUMN business_id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE pos.store_identity ADD COLUMN store_uuid uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE pos.store_identity ADD COLUMN business_name varchar(200);
ALTER TABLE pos.store_identity ADD COLUMN store_name varchar(200);
ALTER TABLE pos.store_identity ADD COLUMN store_code varchar(40);
ALTER TABLE pos.store_identity ADD COLUMN address text NOT NULL DEFAULT '';
ALTER TABLE pos.store_identity ADD COLUMN phone varchar(64) NOT NULL DEFAULT '';
ALTER TABLE pos.store_identity ADD COLUMN email varchar(254) NOT NULL DEFAULT '';
ALTER TABLE pos.store_identity ADD COLUMN configuration jsonb NOT NULL DEFAULT '{}';
ALTER TABLE pos.store_identity ADD COLUMN opening_hours jsonb NOT NULL DEFAULT '{}';
ALTER TABLE pos.store_identity ADD COLUMN revision bigint NOT NULL DEFAULT 0;
ALTER TABLE pos.store_identity ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE pos.store_identity ADD COLUMN archived boolean NOT NULL DEFAULT false;
-- No invented historical name/timestamps. Existing numeric store identity is preserved.
CREATE TABLE pos.configuration_change (
 change_id uuid PRIMARY KEY, business_id uuid NOT NULL, store_id uuid, entity_type varchar(40) NOT NULL,
 entity_id uuid NOT NULL, operation varchar(10) NOT NULL CHECK(operation IN ('CREATE','UPDATE','DELETE')),
 fields jsonb NOT NULL, versions jsonb NOT NULL, state varchar(30) NOT NULL DEFAULT 'LOCAL_ONLY',
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pos.integration_binding (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), enabled boolean NOT NULL DEFAULT false,
 endpoint text, cloud_business_id uuid, cloud_store_id uuid, terminal_id uuid,
 state varchar(30) NOT NULL DEFAULT 'DISABLED', configuration_cursor bigint NOT NULL DEFAULT 0,
 security_cursor bigint NOT NULL DEFAULT 0, security_confirmed_at timestamptz, next_retry timestamptz
);
INSERT INTO pos.integration_binding(singleton) VALUES(true);
GRANT SELECT ON pos.configuration_change,pos.integration_binding TO pos_runtime;
