-- New LOCAL schema only. Never run against the operational cloud database.
CREATE SCHEMA pos;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA pos FROM PUBLIC;
CREATE TABLE pos.store_identity (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  branch_id integer NOT NULL UNIQUE CHECK(branch_id IN (1,2)),
  cluster_id uuid NOT NULL UNIQUE,
  installation_id uuid NOT NULL UNIQUE
);
CREATE TABLE pos.staff (
  staff_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  username varchar(64) NOT NULL CHECK(username = lower(username)),
  password_hash text NOT NULL,
  role varchar(16) NOT NULL CHECK(role IN ('MANAGER','CASHIER')),
  enabled boolean NOT NULL DEFAULT true,
  credential_version bigint NOT NULL DEFAULT 0,
  failed_attempts integer NOT NULL DEFAULT 0,
  locked_until timestamptz,
  revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
  UNIQUE(branch_id, username), UNIQUE(branch_id,staff_id)
);
CREATE TABLE pos.staff_session (
  token_hash char(64) PRIMARY KEY,
  branch_id integer NOT NULL,
  staff_id uuid NOT NULL,
  terminal_identity varchar(512) NOT NULL,
  credential_version bigint NOT NULL,
  expires_at timestamptz NOT NULL,
  FOREIGN KEY(branch_id,staff_id) REFERENCES pos.staff(branch_id,staff_id)
);
CREATE TABLE pos.catalog_item (
  item_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  source_type varchar(40) NOT NULL,
  source_id varchar(128) NOT NULL,
  description varchar(256) NOT NULL,
  definition jsonb NOT NULL CHECK(jsonb_typeof(definition)='object'),
  definition_revision bigint NOT NULL CHECK(definition_revision>=0),
  UNIQUE(branch_id,source_type,source_id), UNIQUE(branch_id,item_id)
);
CREATE TABLE pos.branch_price (
  price_id uuid PRIMARY KEY,
  branch_id integer NOT NULL,
  item_id uuid NOT NULL,
  variant_key varchar(128) NOT NULL,
  amount numeric(19,2) NOT NULL CHECK(amount>=0),
  available boolean NOT NULL,
  revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
  FOREIGN KEY(branch_id,item_id) REFERENCES pos.catalog_item(branch_id,item_id),
  UNIQUE(branch_id,item_id,variant_key), UNIQUE(branch_id,price_id)
);
CREATE TABLE pos.local_customer (
  customer_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  display_name varchar(128) NOT NULL,
  phone varchar(64), address text,
  address_verified boolean NOT NULL DEFAULT false,
  revision bigint NOT NULL DEFAULT 0,
  UNIQUE(branch_id,customer_id)
);
-- Drafts are not accepted sales. Fulfilment/payment acceptance is Stage 5.
CREATE TABLE pos.order_draft (
  sync_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  internal_id bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
  customer_id uuid,
  state varchar(16) NOT NULL CHECK(state IN ('DRAFT','CANCELLED')),
  revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
  provisional_reference varchar(128) NOT NULL UNIQUE,
  final_reference varchar(128),
  total numeric(19,2) NOT NULL CHECK(total>=0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(branch_id,customer_id) REFERENCES pos.local_customer(branch_id,customer_id),
  UNIQUE(branch_id,sync_id)
);
CREATE TABLE pos.order_line (
  line_id uuid PRIMARY KEY,
  branch_id integer NOT NULL,
  order_id uuid NOT NULL,
  price_id uuid NOT NULL,
  description varchar(256) NOT NULL,
  quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 999),
  unit_price numeric(19,2) NOT NULL CHECK(unit_price>=0),
  price_revision bigint NOT NULL,
  snapshot jsonb NOT NULL,
  FOREIGN KEY(branch_id,order_id) REFERENCES pos.order_draft(branch_id,sync_id),
  FOREIGN KEY(branch_id,price_id) REFERENCES pos.branch_price(branch_id,price_id),
  UNIQUE(branch_id,line_id)
);
CREATE TABLE pos.order_line_extra (
  extra_id uuid PRIMARY KEY,
  branch_id integer NOT NULL,
  line_id uuid NOT NULL,
  snapshot jsonb NOT NULL,
  amount numeric(19,2) NOT NULL CHECK(amount>=0),
  FOREIGN KEY(branch_id,line_id) REFERENCES pos.order_line(branch_id,line_id)
);
CREATE TABLE pos.payment_metadata (
  payment_id uuid PRIMARY KEY,
  branch_id integer NOT NULL,
  order_id uuid NOT NULL,
  method varchar(24) NOT NULL CHECK(method IN ('CASH','EXTERNAL_CARD')),
  amount numeric(19,2) NOT NULL CHECK(amount>=0),
  external_reference varchar(128),
  FOREIGN KEY(branch_id,order_id) REFERENCES pos.order_draft(branch_id,sync_id)
);
CREATE TABLE pos.command_receipt (
  command_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  actor_id uuid NOT NULL,
  terminal_identity varchar(512) NOT NULL,
  payload_hash char(64) NOT NULL,
  result jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pos.stream_head (
  branch_id integer PRIMARY KEY REFERENCES pos.store_identity(branch_id),
  position bigint NOT NULL DEFAULT 0
);
CREATE TABLE pos.audit_record (
  audit_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  actor_id uuid NOT NULL,
  action varchar(64) NOT NULL,
  aggregate_id uuid NOT NULL,
  command_id uuid,
  details jsonb NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pos.outbox_event (
  event_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  stream_position bigint NOT NULL,
  aggregate_id uuid NOT NULL,
  aggregate_revision bigint NOT NULL,
  event_type varchar(64) NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(branch_id,stream_position)
);
CREATE TABLE pos.inbox_receipt (
  event_id uuid PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  payload_hash char(64) NOT NULL,
  result jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE pos.terminal_printer (
  terminal_identity varchar(512) PRIMARY KEY,
  branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
  printer_name varchar(256),
  revision bigint NOT NULL DEFAULT 0
);
GRANT USAGE ON SCHEMA pos TO pos_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA pos TO pos_runtime;
GRANT INSERT,UPDATE,DELETE ON pos.staff_session TO pos_runtime;
GRANT INSERT,UPDATE ON pos.staff, pos.branch_price, pos.local_customer, pos.order_draft, pos.stream_head, pos.terminal_printer TO pos_runtime;
GRANT INSERT ON pos.order_line, pos.order_line_extra, pos.payment_metadata, pos.command_receipt, pos.audit_record, pos.outbox_event, pos.inbox_receipt TO pos_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA pos TO pos_runtime;
