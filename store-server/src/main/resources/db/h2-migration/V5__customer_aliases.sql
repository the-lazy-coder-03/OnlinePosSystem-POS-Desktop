CREATE TABLE dev_meta.customer_aliases (alias_id UUID PRIMARY KEY, customer_id UUID NOT NULL REFERENCES dev_meta.customer_identity(customer_id));
ALTER TABLE dev_meta.customer_identity ADD COLUMN field_times CHARACTER LARGE OBJECT NOT NULL DEFAULT '{}';
