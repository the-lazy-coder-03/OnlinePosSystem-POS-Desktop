-- V19__reporting_foundation.sql
-- OnlinePOS local PostgreSQL: additive reporting foundation after V18.
-- DO NOT alter, re-run, or checksum-change V1..V18 in an existing Flyway database.
-- Apply this file through Flyway against the LOCAL POS only.
-- IMPORTANT: The pre-existing pos.order_draft and pos.payment_metadata are
-- NOT completed sales or settled tender records. Nothing here turns them into sales.
-- All report_* financial facts start empty until trusted transaction code records
-- a finalized sale, refund, payment, cashup or stock movement.
-- Append-only fact tables are PROJECTIONS of verified business events. They must
-- not replace the transaction authority or its immutable command/outbox records.
-- The future application transaction layer must ingest through a trusted,
-- idempotent, authorized server-side path in the SAME ACID transaction as the
-- source event, or via a replay-safe outbox projection with reconciliation.
-- Never grant arbitrary report-fact INSERT privileges to cashier sessions.
-- Currency, business day and sale price/tax semantics are explicit, rather than
-- inferred from the current menu or the database server's timezone.

-- 1. Finished sale facts. A sale is recorded exactly once globally by event and
-- by originating business/store/transaction identifier.
CREATE TABLE pos.report_sale (
    sale_id uuid PRIMARY KEY,
    origin_event_id uuid NOT NULL UNIQUE,
    business_id uuid NOT NULL,
    store_id uuid NOT NULL,
    branch_id integer NOT NULL,
    source_system varchar(64) NOT NULL,
    source_transaction_id varchar(128) NOT NULL,
    receipt_number varchar(128),
    completed_at timestamptz NOT NULL,
    business_date date NOT NULL,
    currency char(3) NOT NULL DEFAULT 'ZAR',
    customer_id uuid,
    cashier_id uuid,
    terminal_identity varchar(512),
    order_channel varchar(64) NOT NULL DEFAULT 'IN_STORE',
    order_type varchar(64) NOT NULL DEFAULT 'SALE',
    -- Prices use an EXCLUSIVE-tax basis; any inclusive price must be normalized
    -- by the transaction authority before insertion.
    subtotal_excl_tax numeric(19,2) NOT NULL CHECK (subtotal_excl_tax >= 0),
    discount_excl_tax numeric(19,2) NOT NULL DEFAULT 0 CHECK (discount_excl_tax >= 0),
    tax_total numeric(19,2) NOT NULL DEFAULT 0 CHECK (tax_total >= 0),
    service_fee_total numeric(19,2) NOT NULL DEFAULT 0 CHECK (service_fee_total >= 0),
    amount_due numeric(19,2) NOT NULL CHECK (amount_due >= 0),
    payment_policy varchar(24) NOT NULL DEFAULT 'IMMEDIATE'
        CHECK (payment_policy IN ('IMMEDIATE','ON_ACCOUNT','OTHER')),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(attributes) = 'object'),
    ingested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT report_sale_unique_origin UNIQUE (business_id,store_id,source_system,source_transaction_id),
    CONSTRAINT report_sale_discount_limit CHECK (discount_excl_tax <= subtotal_excl_tax),
    CONSTRAINT report_sale_total_rule CHECK (
        amount_due = subtotal_excl_tax - discount_excl_tax + tax_total + service_fee_total
    ),
    CONSTRAINT report_sale_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX report_sale_day_store ON pos.report_sale(business_id,store_id,business_date,sale_id);
CREATE INDEX report_sale_day_branch ON pos.report_sale(branch_id,business_date,sale_id);
CREATE INDEX report_sale_customer ON pos.report_sale(business_id,customer_id,completed_at DESC)
    WHERE customer_id IS NOT NULL;
CREATE INDEX report_sale_cashier ON pos.report_sale(store_id,cashier_id,completed_at DESC)
    WHERE cashier_id IS NOT NULL;
CREATE INDEX report_sale_channel ON pos.report_sale(business_id,business_date,order_channel);

-- 2. Unified product facts, independent of pizza/burger/retail schema.
-- BUNDLE sales may have INCLUDED COMPONENT children. Count their quantities,
-- but NEVER count their line prices as additional revenue.
CREATE TABLE pos.report_sale_line (
    line_id uuid PRIMARY KEY,
    sale_id uuid NOT NULL REFERENCES pos.report_sale(sale_id) ON DELETE RESTRICT,
    parent_line_id uuid,
    line_kind varchar(20) NOT NULL CHECK (line_kind IN ('PRODUCT','BUNDLE','COMPONENT','ADDON')),
    revenue_role varchar(16) NOT NULL CHECK (revenue_role IN ('BILLABLE','INCLUDED')),
    source_type varchar(64) NOT NULL,
    source_id varchar(128) NOT NULL,
    -- A stable, canonical business-wide identifier, when one exists; otherwise
    -- reporting views namespace the local source ID by store to avoid collisions.
    global_product_key varchar(256),
    sku varchar(128),
    product_name varchar(256) NOT NULL,
    category_key varchar(128),
    category_name varchar(256),
    variant_key varchar(128) NOT NULL DEFAULT 'baseline',
    variant_name varchar(256),
    quantity numeric(19,3) NOT NULL CHECK (quantity > 0),
    unit varchar(24) NOT NULL DEFAULT 'each',
    unit_price_excl_tax numeric(19,4) NOT NULL CHECK (unit_price_excl_tax >= 0),
    gross_excl_tax numeric(19,2) NOT NULL CHECK (gross_excl_tax >= 0),
    discount_excl_tax numeric(19,2) NOT NULL DEFAULT 0 CHECK (discount_excl_tax >= 0),
    net_excl_tax numeric(19,2) NOT NULL CHECK (net_excl_tax >= 0),
    requires_cost_tracking boolean NOT NULL DEFAULT false,
    product_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(product_snapshot) = 'object'),
    UNIQUE(sale_id,line_id),
    CONSTRAINT report_line_parent_fk FOREIGN KEY(sale_id,parent_line_id)
        REFERENCES pos.report_sale_line(sale_id,line_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT report_line_not_own_parent CHECK (parent_line_id IS NULL OR parent_line_id <> line_id),
    CONSTRAINT report_line_net_rule CHECK (net_excl_tax = gross_excl_tax - discount_excl_tax),
    CONSTRAINT report_line_included_free CHECK (
        revenue_role <> 'INCLUDED' OR
        (gross_excl_tax = 0 AND discount_excl_tax = 0 AND net_excl_tax = 0)
    )
);
CREATE INDEX report_line_sale ON pos.report_sale_line(sale_id);
CREATE INDEX report_line_product ON pos.report_sale_line(source_type,source_id,variant_key);
CREATE INDEX report_line_global_product ON pos.report_sale_line(global_product_key)
    WHERE global_product_key IS NOT NULL;
CREATE INDEX report_line_category ON pos.report_sale_line(category_key);

-- 3. Captured/confirmed tenders, not attempts or a payment-method suggestion.
-- Stable tender IDs must survive replay, cloud sync and Master failover.
CREATE TABLE pos.report_sale_tender (
    tender_id uuid PRIMARY KEY,
    source_event_id uuid NOT NULL UNIQUE,
    sale_id uuid NOT NULL REFERENCES pos.report_sale(sale_id) ON DELETE RESTRICT,
    captured_at timestamptz NOT NULL,
    business_date date NOT NULL,
    method varchar(64) NOT NULL,
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    external_reference varchar(256),
    provider varchar(64),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(attributes) = 'object')
);
CREATE INDEX report_tender_sale ON pos.report_sale_tender(sale_id);
CREATE INDEX report_tender_method ON pos.report_sale_tender(method,captured_at);

-- 4. Refunds are explicit negative financial events. Do not modify/delete
-- original completed sales. Refunds are reported on their OWN business date.
CREATE TABLE pos.report_refund (
    refund_id uuid PRIMARY KEY,
    origin_event_id uuid NOT NULL UNIQUE,
    sale_id uuid NOT NULL REFERENCES pos.report_sale(sale_id) ON DELETE RESTRICT,
    source_system varchar(64) NOT NULL,
    source_refund_id varchar(128) NOT NULL,
    refunded_at timestamptz NOT NULL,
    business_date date NOT NULL,
    -- These are positive values that the reporting views subtract.
    refunded_net_excl_tax numeric(19,2) NOT NULL CHECK(refunded_net_excl_tax >= 0),
    refunded_tax numeric(19,2) NOT NULL DEFAULT 0 CHECK(refunded_tax >= 0),
    refunded_fees numeric(19,2) NOT NULL DEFAULT 0 CHECK(refunded_fees >= 0),
    refunded_total numeric(19,2) NOT NULL CHECK(refunded_total > 0),
    employee_id uuid,
    reason varchar(512),
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(attributes) = 'object'),
    ingested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(source_system,source_refund_id,sale_id),
    UNIQUE(refund_id,sale_id),
    CHECK(refunded_total = refunded_net_excl_tax + refunded_tax + refunded_fees)
);
CREATE INDEX report_refund_sale ON pos.report_refund(sale_id,refunded_at);
CREATE INDEX report_refund_date ON pos.report_refund(business_date,refund_id);

CREATE TABLE pos.report_refund_line (
    refund_line_id uuid PRIMARY KEY,
    refund_id uuid NOT NULL,
    sale_id uuid NOT NULL,
    sale_line_id uuid NOT NULL,
    quantity numeric(19,3) NOT NULL CHECK(quantity > 0),
    refunded_net_excl_tax numeric(19,2) NOT NULL CHECK(refunded_net_excl_tax >= 0),
    -- Only reverse COGS if returned stock is reusable/actually credited.
    reversed_cogs numeric(19,2) NOT NULL DEFAULT 0 CHECK(reversed_cogs >= 0),
    FOREIGN KEY(refund_id,sale_id) REFERENCES pos.report_refund(refund_id,sale_id),
    FOREIGN KEY(sale_id,sale_line_id) REFERENCES pos.report_sale_line(sale_id,line_id)
);
CREATE INDEX report_refund_line_refund ON pos.report_refund_line(refund_id);
CREATE INDEX report_refund_line_source ON pos.report_refund_line(sale_line_id);

CREATE TABLE pos.report_refund_tender (
    refund_tender_id uuid PRIMARY KEY,
    source_event_id uuid NOT NULL UNIQUE,
    refund_id uuid NOT NULL REFERENCES pos.report_refund(refund_id) ON DELETE RESTRICT,
    refunded_at timestamptz NOT NULL,
    business_date date NOT NULL,
    method varchar(64) NOT NULL,
    amount numeric(19,2) NOT NULL CHECK(amount > 0),
    external_reference varchar(256)
);
CREATE INDEX report_refund_tender_refund ON pos.report_refund_tender(refund_id);

-- 5. Actual closed cashups with recorded float and counted amount.
CREATE TABLE pos.report_cashup (
    cashup_id uuid PRIMARY KEY,
    origin_event_id uuid NOT NULL UNIQUE,
    business_id uuid NOT NULL,
    store_id uuid NOT NULL,
    branch_id integer NOT NULL,
    terminal_identity varchar(512),
    employee_id uuid,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    business_date date NOT NULL,
    currency char(3) NOT NULL DEFAULT 'ZAR' CHECK(currency ~ '^[A-Z]{3}$'),
    opening_float numeric(19,2) NOT NULL DEFAULT 0 CHECK(opening_float >= 0),
    expected_cash numeric(19,2) NOT NULL CHECK(expected_cash >= 0),
    counted_cash numeric(19,2) NOT NULL CHECK(counted_cash >= 0),
    variance numeric(19,2) GENERATED ALWAYS AS (counted_cash - expected_cash) STORED,
    closed_at timestamptz NOT NULL,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(attributes) = 'object'),
    CHECK(period_end > period_start)
);
CREATE INDEX report_cashup_store_date ON pos.report_cashup(store_id,business_date,closed_at);

CREATE TABLE pos.report_cashup_tender (
    cashup_id uuid NOT NULL REFERENCES pos.report_cashup(cashup_id),
    tender_method varchar(64) NOT NULL,
    expected_amount numeric(19,2) NOT NULL CHECK(expected_amount >= 0),
    counted_amount numeric(19,2) CHECK(counted_amount >= 0),
    PRIMARY KEY(cashup_id,tender_method)
);

-- 6. Cost snapshots and inventory activity. No fabricated zero cost.
CREATE TABLE pos.report_sale_line_cost (
    line_id uuid PRIMARY KEY REFERENCES pos.report_sale_line(line_id),
    cost_total numeric(19,2) NOT NULL CHECK(cost_total >= 0),
    cost_method varchar(64) NOT NULL,
    cost_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(cost_snapshot) = 'object'),
    captured_at timestamptz NOT NULL
);

CREATE TABLE pos.report_stock_movement (
    movement_id uuid PRIMARY KEY,
    origin_event_id uuid NOT NULL UNIQUE,
    business_id uuid NOT NULL,
    store_id uuid NOT NULL,
    branch_id integer NOT NULL,
    source_item_type varchar(64) NOT NULL,
    source_item_id varchar(128) NOT NULL,
    item_name varchar(256) NOT NULL,
    movement_type varchar(40) NOT NULL,
    quantity_delta numeric(19,3) NOT NULL CHECK(quantity_delta <> 0),
    unit varchar(24) NOT NULL,
    unit_cost numeric(19,4) CHECK(unit_cost >= 0),
    occurred_at timestamptz NOT NULL,
    business_date date NOT NULL,
    source_sale_id uuid,
    reason varchar(512)
);
CREATE INDEX report_stock_item_date ON pos.report_stock_movement(store_id,source_item_type,source_item_id,business_date);

-- 7. User-configurable reports and monitoring (configuration, not financial facts).
CREATE TABLE pos.report_definition (
    report_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
    created_by uuid NOT NULL,
    name varchar(160) NOT NULL CHECK(length(btrim(name)) > 0),
    dataset varchar(64) NOT NULL,
    definition jsonb NOT NULL CHECK(jsonb_typeof(definition) = 'object'),
    shared boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision >= 0)
);
CREATE INDEX report_definition_owner ON pos.report_definition(branch_id,created_by);

CREATE TABLE pos.report_alert_rule (
    rule_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id integer NOT NULL REFERENCES pos.store_identity(branch_id),
    created_by uuid NOT NULL,
    name varchar(160) NOT NULL,
    report_id uuid REFERENCES pos.report_definition(report_id),
    dataset varchar(64) NOT NULL,
    predicate jsonb NOT NULL CHECK(jsonb_typeof(predicate) = 'object'),
    enabled boolean NOT NULL DEFAULT true,
    last_evaluated_at timestamptz,
    last_triggered_at timestamptz
);

-- Optional sync/report projection freshness. No initial value is invented.
CREATE TABLE pos.report_projection_cursor (
    business_id uuid NOT NULL,
    store_id uuid NOT NULL,
    origin_system varchar(64) NOT NULL,
    last_source_position bigint CHECK(last_source_position >= 0),
    last_source_at timestamptz,
    last_ingested_at timestamptz,
    pending_events bigint CHECK(pending_events >= 0),
    PRIMARY KEY(business_id,store_id,origin_system)
);

-- 8. Universal financial event stream. Refunds are negative on refund date.
-- Use this event view for custom ranges and YTD, not order_draft or catalog prices.
CREATE VIEW pos.v_report_financial_events AS
SELECT s.business_id, s.store_id, s.branch_id, s.currency,
       s.sale_id AS event_id, s.sale_id, 'SALE'::text AS event_type,
       s.business_date, s.completed_at AS occurred_at,
       s.subtotal_excl_tax AS gross_excl_tax,
       s.discount_excl_tax AS discounts_excl_tax,
       0::numeric(19,2) AS returns_excl_tax,
       (s.subtotal_excl_tax - s.discount_excl_tax) AS net_sales_excl_tax,
       s.tax_total, s.service_fee_total, s.amount_due AS amount_incl_tax,
       1::bigint AS sale_count, 0::bigint AS refund_count
FROM pos.report_sale s
UNION ALL
SELECT s.business_id, s.store_id, s.branch_id, s.currency,
       r.refund_id AS event_id, s.sale_id, 'REFUND'::text AS event_type,
       r.business_date, r.refunded_at AS occurred_at,
       0::numeric(19,2), 0::numeric(19,2),
       r.refunded_net_excl_tax, -r.refunded_net_excl_tax, -r.refunded_tax, -r.refunded_fees,
       -r.refunded_total, 0::bigint, 1::bigint
FROM pos.report_refund r JOIN pos.report_sale s ON s.sale_id = r.sale_id;

CREATE VIEW pos.v_report_daily_sales AS
SELECT business_id,store_id,branch_id,currency,business_date,
       sum(sale_count)::bigint AS completed_sales,
       sum(refund_count)::bigint AS refunds,
       sum(gross_excl_tax)::numeric(19,2) AS gross_excl_tax,
       sum(discounts_excl_tax)::numeric(19,2) AS discount_excl_tax,
       sum(returns_excl_tax)::numeric(19,2) AS returns_excl_tax,
       sum(net_sales_excl_tax)::numeric(19,2) AS net_sales_excl_tax,
       sum(tax_total)::numeric(19,2) AS net_tax,
       sum(service_fee_total)::numeric(19,2) AS net_fees,
       sum(amount_incl_tax)::numeric(19,2) AS net_amount_incl_tax,
       sum(CASE WHEN event_type='SALE' THEN amount_incl_tax ELSE 0 END)::numeric(19,2)
           AS original_sale_amount_incl_tax
FROM pos.v_report_financial_events
GROUP BY business_id,store_id,branch_id,currency,business_date;

-- 9. Unified product activity. Refund line allocations are required for
-- item-level return accuracy. Financial totals remain sourced from headers.
CREATE VIEW pos.v_report_product_activity AS
SELECT s.business_id,s.store_id,s.branch_id,s.currency,s.business_date,
       l.line_id,s.sale_id,'SALE'::text AS event_type,
       coalesce(l.global_product_key,
           s.store_id::text||':'||l.source_type||':'||l.source_id) AS product_key,
       l.source_type,l.source_id,l.global_product_key,l.sku,l.product_name,
       l.category_key,l.category_name,l.variant_key,l.variant_name,
       l.line_kind,l.revenue_role,l.unit,
       l.quantity AS units,
       CASE WHEN l.revenue_role='BILLABLE' THEN l.net_excl_tax ELSE 0::numeric END AS revenue_excl_tax
FROM pos.report_sale_line l JOIN pos.report_sale s ON s.sale_id=l.sale_id
UNION ALL
SELECT s.business_id,s.store_id,s.branch_id,s.currency,r.business_date,
       l.line_id,s.sale_id,'REFUND'::text,
       coalesce(l.global_product_key,
           s.store_id::text||':'||l.source_type||':'||l.source_id),
       l.source_type,l.source_id,l.global_product_key,l.sku,l.product_name,
       l.category_key,l.category_name,l.variant_key,l.variant_name,
       l.line_kind,l.revenue_role,l.unit,
       -rl.quantity,
       -rl.refunded_net_excl_tax
FROM pos.report_refund_line rl
JOIN pos.report_refund r ON r.refund_id=rl.refund_id
JOIN pos.report_sale_line l ON l.line_id=rl.sale_line_id
JOIN pos.report_sale s ON s.sale_id=r.sale_id;

-- 10. Tender breakdown. Captured payments minus paid-out refunds.
CREATE VIEW pos.v_report_tender_activity AS
SELECT s.business_id,s.store_id,s.branch_id,s.currency,
       t.business_date,
       t.captured_at AS occurred_at,t.method,t.amount,'CAPTURE'::text AS event_type
FROM pos.report_sale_tender t JOIN pos.report_sale s ON s.sale_id=t.sale_id
UNION ALL
SELECT s.business_id,s.store_id,s.branch_id,s.currency,
       t.business_date,t.refunded_at,t.method,-t.amount,'REFUND'::text
FROM pos.report_refund_tender t
JOIN pos.report_refund r ON r.refund_id=t.refund_id
JOIN pos.report_sale s ON s.sale_id=r.sale_id;
-- Tender business dates are supplied by the trusted transaction authority
-- using the store’s configured cutoff and local timezone.

-- 11. Reconciliation checks. A mismatch is flagged, not silently corrected.
CREATE VIEW pos.v_report_sale_reconciliation AS
SELECT s.sale_id,s.business_id,s.store_id,s.business_date,s.currency,
       s.subtotal_excl_tax - s.discount_excl_tax AS header_net_excl_tax,
       coalesce(lines.total,0)::numeric(19,2) AS billed_lines_net_excl_tax,
       s.amount_due,
       coalesce(tenders.total,0)::numeric(19,2) AS captured_tenders,
       s.payment_policy,
       (abs((s.subtotal_excl_tax-s.discount_excl_tax)-coalesce(lines.total,0))>0.01)
           AS line_total_mismatch,
       (s.payment_policy='IMMEDIATE' AND abs(s.amount_due-coalesce(tenders.total,0))>0.01)
           AS payment_mismatch
FROM pos.report_sale s
LEFT JOIN LATERAL (
    SELECT sum(l.net_excl_tax) AS total FROM pos.report_sale_line l
    WHERE l.sale_id=s.sale_id AND l.revenue_role='BILLABLE'
) lines ON true
LEFT JOIN LATERAL (
    SELECT sum(t.amount) AS total FROM pos.report_sale_tender t WHERE t.sale_id=s.sale_id
) tenders ON true;

CREATE VIEW pos.v_report_refund_reconciliation AS
SELECT r.refund_id,r.sale_id,r.business_date,r.refunded_net_excl_tax,r.refunded_total,
       coalesce(lines.amount,0)::numeric(19,2) AS allocated_net_refund,
       coalesce(tenders.amount,0)::numeric(19,2) AS refunded_tender_amount,
       (abs(r.refunded_net_excl_tax-coalesce(lines.amount,0))>0.01) AS unallocated_refund_lines,
       (abs(r.refunded_total-coalesce(tenders.amount,0))>0.01) AS unmatched_refund_payout
FROM pos.report_refund r
LEFT JOIN LATERAL (
   SELECT sum(rl.refunded_net_excl_tax) AS amount
   FROM pos.report_refund_line rl WHERE rl.refund_id=r.refund_id
) lines ON true
LEFT JOIN LATERAL (
   SELECT sum(t.amount) AS amount
   FROM pos.report_refund_tender t WHERE t.refund_id=r.refund_id
) tenders ON true;

-- 12. Available TODAY with existing V1..V18 records: drafts, not sales.
CREATE VIEW pos.v_report_draft_activity AS
SELECT d.branch_id,d.sync_id AS draft_id,d.customer_id,d.state,
       d.created_at,d.total,d.provisional_reference,
       count(l.line_id)::bigint AS line_count,
       coalesce(sum(l.quantity),0)::bigint AS total_units
FROM pos.order_draft d
LEFT JOIN pos.order_line l ON l.branch_id=d.branch_id AND l.order_id=d.sync_id
GROUP BY d.branch_id,d.sync_id,d.customer_id,d.state,d.created_at,d.total,d.provisional_reference;

-- 12a. Existing audit activity is useful before a completed-sale ledger exists.
CREATE VIEW pos.v_report_staff_audit_activity AS
SELECT a.branch_id,a.audit_id,a.actor_id,a.username_snapshot,
       a.action,a.entity_type,a.transaction_id,a.terminal_identity,
       a.occurred_at,a.source_system
FROM pos.audit_record a;

-- 12b. Product-cost coverage. If no cost-tracked lines exist or expected
-- costs are missing, do not show a fictitious gross profit.
CREATE VIEW pos.v_report_sale_cost_coverage AS
SELECT s.sale_id,s.business_id,s.store_id,s.business_date,
       count(l.line_id) FILTER (WHERE l.requires_cost_tracking)::bigint AS tracked_lines,
       count(l.line_id) FILTER (WHERE l.requires_cost_tracking AND c.line_id IS NULL)::bigint AS missing_cost_lines,
       sum(c.cost_total)::numeric(19,2) AS known_cost_total,
       CASE WHEN count(l.line_id) FILTER (WHERE l.requires_cost_tracking)>0
                  AND count(l.line_id) FILTER (WHERE l.requires_cost_tracking AND c.line_id IS NULL)=0
            THEN (s.subtotal_excl_tax-s.discount_excl_tax)-coalesce(sum(c.cost_total),0)
            ELSE NULL END::numeric(19,2) AS gross_profit_when_complete
FROM pos.report_sale s
LEFT JOIN pos.report_sale_line l ON l.sale_id=s.sale_id
LEFT JOIN pos.report_sale_line_cost c ON c.line_id=l.line_id
GROUP BY s.sale_id,s.business_id,s.store_id,s.business_date,
         s.subtotal_excl_tax,s.discount_excl_tax;

-- 13. Owner-specified inclusive start/end dates with exclusive boundary inside.
-- Each currency is queried separately. No mixed-currency fake totals.
CREATE FUNCTION pos.report_sales_summary(
    p_start_date date,p_end_date date,p_currency text DEFAULT 'ZAR',
    p_business_id uuid DEFAULT NULL,p_store_id uuid DEFAULT NULL)
RETURNS TABLE (
    completed_sales bigint, refunds bigint, gross_excl_tax numeric,
    discount_excl_tax numeric,returns_excl_tax numeric,net_sales_excl_tax numeric,net_tax numeric,
    net_fees numeric,net_amount_incl_tax numeric,average_original_sale numeric)
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pos AS $$
BEGIN
  IF p_start_date IS NULL OR p_end_date IS NULL OR p_end_date < p_start_date
     OR p_currency IS NULL OR upper(p_currency) !~ '^[A-Z]{3}$' THEN
    RAISE EXCEPTION 'Valid dates and three-letter currency required' USING ERRCODE='22023';
  END IF;
  RETURN QUERY
  SELECT coalesce(sum(e.sale_count),0)::bigint,
         coalesce(sum(e.refund_count),0)::bigint,
         coalesce(sum(e.gross_excl_tax),0),coalesce(sum(e.discounts_excl_tax),0),
         coalesce(sum(e.returns_excl_tax),0),coalesce(sum(e.net_sales_excl_tax),0),coalesce(sum(e.tax_total),0),
         coalesce(sum(e.service_fee_total),0),coalesce(sum(e.amount_incl_tax),0),
         round(sum(CASE WHEN e.event_type='SALE' THEN e.amount_incl_tax ELSE 0 END)
               / nullif(sum(e.sale_count),0),2)
  FROM pos.v_report_financial_events e
  WHERE e.business_date >= p_start_date
    AND e.business_date < (p_end_date + 1)
    AND e.currency=upper(p_currency)
    AND (p_business_id IS NULL OR e.business_id=p_business_id)
    AND (p_store_id IS NULL OR e.store_id=p_store_id);
END $$;

-- 14. Demand trends. Compares daily rates for periods of different length.
-- Both positive sales and negative line-level returns count toward quantity.
-- Use only returned items with report_refund_line detail for complete trends.
CREATE FUNCTION pos.report_product_trends(
    p_current_start date,p_current_end date,
    p_previous_start date,p_previous_end date,
    p_currency text DEFAULT 'ZAR',p_business_id uuid DEFAULT NULL,p_store_id uuid DEFAULT NULL)
RETURNS TABLE (
    business_id uuid,product_key text,product_name text,category_name text,variant_key text,
    current_units numeric,previous_units numeric,
    current_revenue_excl_tax numeric,previous_revenue_excl_tax numeric,
    current_daily_units numeric,previous_daily_units numeric,
    growth_percent numeric,trend text)
LANGUAGE plpgsql STABLE SET search_path=pg_catalog,pos AS $$
BEGIN
  IF p_current_start IS NULL OR p_current_end IS NULL OR p_previous_start IS NULL OR p_previous_end IS NULL
     OR p_current_start>p_current_end OR p_previous_start>p_previous_end
     OR p_currency IS NULL OR upper(p_currency) !~ '^[A-Z]{3}$' THEN
    RAISE EXCEPTION 'Valid current/previous date ranges and currency required' USING ERRCODE='22023';
  END IF;
  RETURN QUERY
  WITH grouped AS (
    SELECT a.business_id, a.product_key,
           max(a.product_name)::text AS name_value,
           max(a.category_name)::text AS category_value,
           a.variant_key,
           sum(CASE WHEN a.business_date BETWEEN p_current_start AND p_current_end THEN a.units ELSE 0 END) AS curr_units,
           sum(CASE WHEN a.business_date BETWEEN p_previous_start AND p_previous_end THEN a.units ELSE 0 END) AS prev_units,
           sum(CASE WHEN a.business_date BETWEEN p_current_start AND p_current_end THEN a.revenue_excl_tax ELSE 0 END) AS curr_revenue,
           sum(CASE WHEN a.business_date BETWEEN p_previous_start AND p_previous_end THEN a.revenue_excl_tax ELSE 0 END) AS prev_revenue
    FROM pos.v_report_product_activity a
    WHERE ((a.business_date BETWEEN p_current_start AND p_current_end)
           OR (a.business_date BETWEEN p_previous_start AND p_previous_end))
      AND a.currency=upper(p_currency)
      AND (p_business_id IS NULL OR a.business_id=p_business_id)
      AND (p_store_id IS NULL OR a.store_id=p_store_id)
      -- Exclude bundle parents from unit rankings to avoid counting both a bundle
      -- and its included components as equivalent units. BUNDLE revenue remains
      -- available in separate sales/category reports.
      AND a.line_kind <> 'BUNDLE'
    GROUP BY a.business_id,a.product_key,a.variant_key
  ), rates AS (
    SELECT g.*,
           (g.curr_units/(p_current_end-p_current_start+1))::numeric AS curr_daily,
           (g.prev_units/(p_previous_end-p_previous_start+1))::numeric AS prev_daily
    FROM grouped g
  )
  SELECT r.business_id,r.product_key,r.name_value,r.category_value,r.variant_key::text,
         r.curr_units,r.prev_units,r.curr_revenue,r.prev_revenue,
         round(r.curr_daily,3),round(r.prev_daily,3),
         round(100*(r.curr_daily-r.prev_daily)/nullif(r.prev_daily,0),2),
         CASE WHEN r.prev_units=0 AND r.curr_units>0 THEN 'NEW'
              WHEN r.curr_units<=0 AND r.prev_units>0 THEN 'STOPPED'
              WHEN r.curr_units+r.prev_units<10 THEN 'LOW_VOLUME'
              WHEN r.prev_daily>0 AND r.curr_daily>=r.prev_daily*1.2 THEN 'GROWING'
              WHEN r.prev_daily>0 AND r.curr_daily<=r.prev_daily*0.8 THEN 'DECLINING'
              ELSE 'STABLE' END::text
  FROM rates r
  ORDER BY abs(r.curr_daily-r.prev_daily) DESC,r.name_value;
END $$;

-- 15. Immutable posted financial facts: corrections are compensating refunds/
-- adjustments in the transaction authority, never UPDATE/DELETE of history.
CREATE FUNCTION pos.guard_report_history() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pos AS $$
BEGIN
    RAISE EXCEPTION 'Posted reporting facts are append-only' USING ERRCODE='42501';
END $$;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'report_sale','report_sale_line','report_sale_tender','report_refund',
    'report_refund_line','report_refund_tender','report_cashup',
    'report_cashup_tender','report_sale_line_cost','report_stock_movement'
  ] LOOP
    EXECUTE format('CREATE TRIGGER report_immutable_change BEFORE UPDATE OR DELETE ON pos.%I FOR EACH STATEMENT EXECUTE FUNCTION pos.guard_report_history()',t);
    EXECUTE format('CREATE TRIGGER report_immutable_truncate BEFORE TRUNCATE ON pos.%I FOR EACH STATEMENT EXECUTE FUNCTION pos.guard_report_history()',t);
  END LOOP;
END $$;
REVOKE ALL ON FUNCTION pos.guard_report_history() FROM PUBLIC;

-- 16. No financial-fact write rights from the interactive runtime DB role.
-- A separate, explicitly authorized transaction projector is required later.
REVOKE ALL ON pos.report_sale,pos.report_sale_line,pos.report_sale_tender,
    pos.report_refund,pos.report_refund_line,pos.report_refund_tender,
    pos.report_cashup,pos.report_cashup_tender,pos.report_sale_line_cost,
    pos.report_stock_movement,pos.report_definition,pos.report_alert_rule,
    pos.report_projection_cursor FROM PUBLIC,pos_runtime;
GRANT SELECT ON pos.report_sale,pos.report_sale_line,pos.report_sale_tender,
    pos.report_refund,pos.report_refund_line,pos.report_refund_tender,
    pos.report_cashup,pos.report_cashup_tender,pos.report_sale_line_cost,
    pos.report_stock_movement,pos.report_definition,pos.report_alert_rule,
    pos.report_projection_cursor TO pos_runtime;
GRANT SELECT ON pos.v_report_financial_events,pos.v_report_daily_sales,
    pos.v_report_product_activity,pos.v_report_tender_activity,
    pos.v_report_sale_reconciliation,pos.v_report_refund_reconciliation,
    pos.v_report_draft_activity,pos.v_report_staff_audit_activity,
    pos.v_report_sale_cost_coverage TO pos_runtime;
REVOKE ALL ON FUNCTION pos.report_sales_summary(date,date,text,uuid,uuid),
    pos.report_product_trends(date,date,date,date,text,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pos.report_sales_summary(date,date,text,uuid,uuid),
    pos.report_product_trends(date,date,date,date,text,uuid,uuid) TO pos_runtime;

-- NOTE ON PERMISSIONS: The Java backend must validate module.reports and
-- store/business scope before invoking these SELECTs/functions. A shared
-- pos_runtime login is not a substitute for per-staff authorization.
-- NOTE ON COSTS: Do not assign the same inventory cost to BOTH a bundle parent
-- and its included component children. Each actual cost is attributed once.
-- NOTE ON CONSISTENCY: Projector must reject or flag mismatched sale totals,
-- duplicate source IDs, over-refunds, missing refund allocations and missing
-- cost snapshots before marking a business-day financial report reconciled.
