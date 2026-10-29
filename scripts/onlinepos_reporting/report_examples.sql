-- OnlinePOS reporting query examples (PostgreSQL).
-- Run on a database after the V19 migration has been applied through Flyway.
-- Reports on completed sales will correctly return zeros/empty rows until the
-- future transaction authority populates pos.report_* immutable facts.
-- Dates use POS business_date (not midnight UTC) unless stated otherwise.
-- Currency is ZAR here; replace it for non-ZAR stores. Never add currencies.

-- 01. YEAR-TO-DATE financial summary. From Jan 1 through today, inclusive.
SELECT * FROM pos.report_sales_summary(
    date_trunc('year',current_date)::date,current_date,'ZAR');

-- 02. ANY custom inclusive date interval.
SELECT * FROM pos.report_sales_summary(DATE '2026-01-01',DATE '2026-10-28','ZAR');

-- 03. Monthly revenue, taxes, transactions, refunds for the calendar year.
SELECT date_trunc('month',business_date::timestamp)::date AS month,
       sum(completed_sales) AS sales_count,
       sum(refunds) AS refund_count,
       sum(gross_excl_tax) AS original_sales_before_discount,
       sum(discount_excl_tax) AS discounts,
       sum(returns_excl_tax) AS returns,
       sum(net_sales_excl_tax) AS net_sales_excl_vat,
       sum(net_amount_incl_tax) AS net_amount_incl_vat
FROM pos.v_report_daily_sales
WHERE business_date >= date_trunc('year',current_date)::date
  AND business_date <= current_date AND currency='ZAR'
GROUP BY date_trunc('month',business_date::timestamp)::date
ORDER BY month;

-- 04. YTD vs the equivalent previous-year interval (not full last year).
SELECT 'THIS_YEAR' AS period, *
FROM pos.report_sales_summary(date_trunc('year',current_date)::date,current_date,'ZAR')
UNION ALL
SELECT 'LAST_YEAR_SAME_DATES', *
FROM pos.report_sales_summary(
    (date_trunc('year',current_date)-interval '1 year')::date,
    (current_date-interval '1 year')::date,'ZAR');

-- 05. LAST 30 COMPLETED DAYS product demand versus the prior 30 days.
SELECT *
FROM pos.report_product_trends(
    current_date-30,current_date-1,
    current_date-60,current_date-31,'ZAR')
ORDER BY growth_percent DESC NULLS LAST;

-- 06. Biggest DECLINES in units per day.
SELECT product_name,variant_key,previous_units,current_units,
       previous_daily_units,current_daily_units,growth_percent,trend
FROM pos.report_product_trends(
    current_date-30,current_date-1,current_date-60,current_date-31,'ZAR')
WHERE trend IN ('DECLINING','STOPPED')
ORDER BY growth_percent ASC NULLS FIRST,previous_daily_units DESC;

-- 07. Most popular products by units. Bundle components count as demand,
-- but BUNDLE parent counts are excluded to prevent double-counting units.
SELECT product_key,max(product_name) AS product,variant_key,unit,
       sum(units) AS net_units,
       sum(revenue_excl_tax) AS direct_net_revenue_excl_tax
FROM pos.v_report_product_activity
WHERE business_date BETWEEN current_date-30 AND current_date-1
  AND currency='ZAR' AND line_kind<>'BUNDLE'
GROUP BY product_key,variant_key,unit
ORDER BY net_units DESC LIMIT 30;

-- 08. Unsold catalog entries in a custom date range. This uses the local
-- catalogue source identity; see notes on inactive/archived items and variants.
SELECT c.source_type,c.source_id,c.description
FROM pos.catalog_item c
JOIN pos.store_identity st ON st.branch_id=c.branch_id
WHERE c.source_type IN ('menu_item','pizza') -- Extend for other sellable source types.
  AND NOT EXISTS(
 SELECT 1 FROM pos.v_report_product_activity a
 WHERE a.store_id=st.store_uuid AND a.source_type=c.source_type
   AND a.source_id=c.source_id AND a.business_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-30'
)
ORDER BY c.description;

-- 09. Revenue by store; relevant when querying consolidated reporting data.
SELECT business_id,store_id, sum(completed_sales) AS orders,
       sum(net_sales_excl_tax) AS net_sales,
       sum(net_amount_incl_tax) AS amount_incl_tax
FROM pos.v_report_daily_sales
WHERE business_date BETWEEN current_date-30 AND current_date AND currency='ZAR'
GROUP BY business_id,store_id ORDER BY net_sales DESC;

-- 10. Category performance, including known allocated direct revenues only.
-- Included items in a special have zero direct revenue by design.
SELECT category_key,max(category_name) AS category,
       sum(units) AS units,sum(revenue_excl_tax) AS directly_billed_revenue
FROM pos.v_report_product_activity
WHERE business_date BETWEEN current_date-30 AND current_date
  AND currency='ZAR' AND line_kind<>'BUNDLE'
GROUP BY category_key ORDER BY directly_billed_revenue DESC;

-- 11. Checkout hour distribution. Adapt timezone to the store's configuration.
SELECT extract(hour from completed_at AT TIME ZONE 'Africa/Johannesburg')::integer AS local_hour,
       count(*) AS sale_count,sum(amount_due) AS total_incl_tax
FROM pos.report_sale
WHERE business_date BETWEEN current_date-30 AND current_date AND currency='ZAR'
GROUP BY 1 ORDER BY 1;

-- 12. Order-channel performance, with refunds attributed to the source channel.
SELECT s.order_channel,count(*) FILTER (WHERE e.event_type='SALE') AS sales,
       count(*) FILTER (WHERE e.event_type='REFUND') AS refunds,
       sum(e.net_sales_excl_tax) AS net_sales
FROM pos.v_report_financial_events e
JOIN pos.report_sale s ON s.sale_id=e.sale_id
WHERE e.business_date BETWEEN current_date-30 AND current_date AND e.currency='ZAR'
GROUP BY s.order_channel ORDER BY net_sales DESC;

-- 13. Cash versus card / other confirmed tenders; refund payouts are negative.
SELECT method,sum(amount) AS net_collected
FROM pos.v_report_tender_activity
WHERE business_date BETWEEN current_date-30 AND current_date AND currency='ZAR'
GROUP BY method ORDER BY net_collected DESC;

-- 14. Daily cashup differences; actual counted vs expected.
SELECT store_id,business_date,closed_at,opening_float,
       expected_cash,counted_cash,variance
FROM pos.report_cashup
WHERE business_date BETWEEN current_date-30 AND current_date AND currency='ZAR'
ORDER BY abs(variance) DESC,closed_at DESC;

-- 15. Sales attributable to a cashier; excludes refunds processed by others.
SELECT cashier_id,count(*) AS completed_sales,sum(amount_due) AS original_amount_incl_tax
FROM pos.report_sale
WHERE business_date BETWEEN current_date-30 AND current_date
  AND currency='ZAR' AND cashier_id IS NOT NULL
GROUP BY cashier_id ORDER BY completed_sales DESC;

-- 16. Customer repeat behaviour (recognized customer IDs only).
SELECT customer_id,count(*) AS completed_orders,sum(amount_due) AS original_spend,
       min(completed_at) AS first_seen,max(completed_at) AS most_recent
FROM pos.report_sale
WHERE customer_id IS NOT NULL AND currency='ZAR'
GROUP BY customer_id ORDER BY completed_orders DESC LIMIT 100;

-- 17. Aged/dormant registered customers (no completed sale in 90 days).
SELECT c.customer_id,c.display_name,max(s.completed_at) AS last_completed_sale
FROM pos.local_customer c
LEFT JOIN pos.report_sale s ON s.customer_id=c.customer_id
    AND s.branch_id=c.branch_id
WHERE c.canonical_customer_id IS NULL AND NOT c.archived
GROUP BY c.customer_id,c.display_name
HAVING max(s.completed_at)<now()-interval '90 days'
   OR max(s.completed_at) IS NULL
ORDER BY last_completed_sale NULLS FIRST;

-- 18. Catalog price and access audit; may be incomplete when optional events off.
SELECT branch_id,occurred_at,username_snapshot,action,terminal_identity
FROM pos.v_report_staff_audit_activity
WHERE occurred_at >= now()-interval '30 days'
  AND action IN ('PRICE_CHANGED','CATALOGUE_CHANGED','ADMIN_PIN_CHANGED')
ORDER BY occurred_at DESC;

-- 19. Stock movement by ingredient or stock item (only after stock events exist).
SELECT store_id,source_item_type,source_item_id,max(item_name) AS item,
       unit,sum(quantity_delta) AS net_quantity_change
FROM pos.report_stock_movement
WHERE business_date BETWEEN current_date-30 AND current_date
GROUP BY store_id,source_item_type,source_item_id,unit
ORDER BY net_quantity_change;

-- 20. Gross profit only where tracked costs are complete.
SELECT business_date,count(*) AS sales,
       sum(gross_profit_when_complete) FILTER(WHERE missing_cost_lines=0)
            AS known_complete_gross_profit,
       count(*) FILTER(WHERE missing_cost_lines>0) AS sales_missing_cost_data
FROM pos.v_report_sale_cost_coverage
WHERE business_date BETWEEN current_date-30 AND current_date
GROUP BY business_date ORDER BY business_date;

-- 21. Reconciliation problems must be investigated, not silently excluded.
SELECT * FROM pos.v_report_sale_reconciliation
WHERE line_total_mismatch OR payment_mismatch ORDER BY business_date DESC;
SELECT * FROM pos.v_report_refund_reconciliation
WHERE unallocated_refund_lines OR unmatched_refund_payout
ORDER BY business_date DESC;

-- 22. Live-order draft activity, supported by pre-V19 records immediately.
SELECT state,count(*) AS draft_count,sum(total) AS draft_value_not_revenue
FROM pos.v_report_draft_activity
WHERE created_at >= now()-interval '7 days'
GROUP BY state ORDER BY state;

-- 23. Projection/sync health. NULL freshness means no projection yet.
SELECT business_id,store_id,origin_system,last_source_position,
       last_source_at,last_ingested_at,pending_events
FROM pos.report_projection_cursor ORDER BY store_id,origin_system;

-- 24. Specials/bundles as products in their own right (billed bundle revenue).
SELECT product_key,max(product_name) AS special_name,
       sum(units) AS bundles_sold,sum(revenue_excl_tax) AS bundle_revenue_excl_tax
FROM pos.v_report_product_activity
WHERE line_kind='BUNDLE' AND revenue_role='BILLABLE'
  AND currency='ZAR' AND business_date BETWEEN current_date-30 AND current_date
GROUP BY product_key ORDER BY bundles_sold DESC;

-- 25. Report definitions saved by the owner (once a secured save API is built).
SELECT report_id,name,dataset,shared,enabled,updated_at
FROM pos.report_definition
ORDER BY updated_at DESC;
