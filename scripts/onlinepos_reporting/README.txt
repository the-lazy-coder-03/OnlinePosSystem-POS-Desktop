OnlinePOS REPORTING SQL
=======================

Contents
--------
V19__reporting_foundation.sql       Additive migration for an EXISTING local PostgreSQL POS
report_examples.sql                Examples of YTD, date-range, trend and other reports
../OnlinePOS_fresh_setup_with_reporting_V19.sql
                                   Full V1-V19 fresh-setup export for EMPTY databases

Which script should I use?
--------------------------
1. EXISTING store: Add V19__reporting_foundation.sql to your real migration
   source directory, typically src/main/resources/db/migration/ for the module
   owning local PostgreSQL. Apply through Flyway. Do not execute the full
   fresh-setup export, and do not change V1-V18 migration checksums.
2. EMPTY development database: Integrate the V19 migration in the real
   migration directory and regenerate the export with the repository's
   tools/export-database-setup.py. The supplied merged setup is a generated-style
   convenience copy only. Never run it manually against application-managed DB.

What works now?
---------------
- Existing audit and order-DRAFT analysis: v_report_staff_audit_activity,
  v_report_draft_activity.
- Report definitions/alert settings schema and full reporting data model.
- Financial queries are implemented but return no sales until the transaction
  implementation records genuine completed sales and reconciled refunds.

Integration requirements
------------------------
- Completed transaction ingestion must originate from verified master-side
  transaction records and use the same immutable transaction/sync identity
  after failover and cloud replay. Do not write financial report facts from
  order_draft or payment_metadata alone.
- Never count both a special/bundle parent and included child revenue.
- Snapshot historical names, categories, variants, quantities, unit values,
  tax basis, payment method, employee, store and business date.
- The transaction authority must validate balance/line allocations/over-refunds
  before posting financial facts. v_report_*_reconciliation surfaces issues.
- Append-only financial facts are read-only to pos_runtime; the application
  needs a tightly authorized projector/ingestion function or separate trusted
  database role once completed sale processing is implemented.
- The Java backend must check module.reports, store/business scope and the
  user's permissions before executing these queries. Database service credentials
  do not themselves identify an individual staff member.
- report_definition and report_alert_rule do not yet have a secured CRUD port;
  add one through the existing module-session / permission model before exposing
  Save Report / alert editing in the UI. Scheduling and actual exports need code.
- Use business_date for financial periods, with store-specific timezone and
  cashup-day cutoff computed by the POS transaction authority.
- Multi-store consolidated reporting requires a dedicated cloud reporting
  warehouse or an authorized report query service: this LOCAL pos.store_identity
  supports one store per database, even though fact records include store_id.
- Cost/stock analytics require reliable inventory and cost history.
- Product demand correctly counts included bundle items but does not allocate
  revenue from a bundle to those child products, as that would require an
  explicit non-additive allocation model.

Validation and safety
---------------------
- These SQL files were structurally reviewed but not executed against PostgreSQL
  here. Apply through Flyway on a disposable test database and run integration
  tests before promotion to a live store.
- The original 18 migrations and the uploaded input file have not been changed.
