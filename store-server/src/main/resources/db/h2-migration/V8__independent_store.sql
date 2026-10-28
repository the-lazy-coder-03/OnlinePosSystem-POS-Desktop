CREATE TABLE dev_meta.business (
 singleton INT PRIMARY KEY CHECK(singleton=1), business_id UUID NOT NULL UNIQUE,
 name VARCHAR(200), revision BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE
);
ALTER TABLE public.branch ADD COLUMN store_uuid UUID DEFAULT RANDOM_UUID() NOT NULL;
ALTER TABLE public.branch ADD CONSTRAINT branch_store_uuid_unique UNIQUE(store_uuid);
ALTER TABLE public.branch ADD COLUMN code VARCHAR(40);
ALTER TABLE public.branch ADD COLUMN address VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE public.branch ADD COLUMN phone VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE public.branch ADD COLUMN email VARCHAR(254) NOT NULL DEFAULT '';
ALTER TABLE public.branch ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.branch ADD COLUMN configuration CHARACTER LARGE OBJECT NOT NULL DEFAULT '{}';
ALTER TABLE public.branch ADD COLUMN opening_hours CHARACTER LARGE OBJECT NOT NULL DEFAULT '{}';
ALTER TABLE public.branch ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.branch ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.branch ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
CREATE TABLE dev_meta.local_scope (
 singleton INT PRIMARY KEY CHECK(singleton=1), branch_id INT REFERENCES public.branch(branch_id),
 source_id UUID NOT NULL, clock_millis BIGINT NOT NULL DEFAULT 0, clock_counter BIGINT NOT NULL DEFAULT 0
);
INSERT INTO dev_meta.local_scope(singleton,source_id) VALUES(1,RANDOM_UUID());
CREATE TABLE dev_meta.store_ids(singleton INT PRIMARY KEY CHECK(singleton=1),next_id BIGINT NOT NULL);
INSERT INTO dev_meta.store_ids SELECT 1,COALESCE(MAX(branch_id),0)+1 FROM public.branch;
CREATE TABLE dev_meta.integration_binding (
 singleton INT PRIMARY KEY CHECK(singleton=1), enabled BOOLEAN NOT NULL DEFAULT FALSE,
 endpoint VARCHAR(2048), cloud_business_id UUID, cloud_store_id UUID, terminal_id UUID,
 state VARCHAR(30) NOT NULL DEFAULT 'DISABLED', configuration_cursor BIGINT NOT NULL DEFAULT 0,
 security_cursor BIGINT NOT NULL DEFAULT 0, next_retry TIMESTAMP WITH TIME ZONE,
 security_confirmed_at TIMESTAMP WITH TIME ZONE
);
INSERT INTO dev_meta.integration_binding(singleton) VALUES(1);
CREATE TABLE dev_meta.configuration_change (
 change_id UUID PRIMARY KEY, business_id UUID NOT NULL, store_id UUID, entity_type VARCHAR(40) NOT NULL,
 entity_id UUID NOT NULL, operation VARCHAR(10) NOT NULL CHECK(operation IN ('CREATE','UPDATE','DELETE')),
 fields CHARACTER LARGE OBJECT NOT NULL, versions CHARACTER LARGE OBJECT NOT NULL,
 state VARCHAR(30) NOT NULL DEFAULT 'LOCAL_ONLY', recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE dev_meta.configuration_inbox (
 change_id UUID PRIMARY KEY, content_hash VARCHAR(64) NOT NULL, cursor_value BIGINT NOT NULL,
 state VARCHAR(30) NOT NULL, payload CHARACTER LARGE OBJECT NOT NULL
);
CREATE TABLE dev_meta.catalogue_reset (
 reset_id UUID PRIMARY KEY, backup_hash VARCHAR(64) NOT NULL, manifest CHARACTER LARGE OBJECT NOT NULL,
 recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE dev_meta.customer_identity ADD COLUMN preferred_branch INT REFERENCES public.branch(branch_id);
CREATE UNIQUE INDEX branch_code_unique ON public.branch(code);
