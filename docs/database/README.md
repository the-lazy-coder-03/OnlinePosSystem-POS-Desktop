# Complete database setup SQL

These files collect all current application migrations in numeric order, including table creation, later column changes, constraints, indexes, functions, triggers and required migration configuration rows.

- `postgresql-setup.sql`: installed local PostgreSQL database, migrations V1–V18. Catalogue source records are stored in the application's local catalogue structure; this does not set up the separate website/cloud database.
- `h2-setup.sql`: development H2 database, migrations V1–V12 (there is no V4 migration). Includes the relational source catalogue and `dev_meta` tables.

Use these scripts for reviewing the complete schema or creating a separate, empty database for manual development. They contain no exported business data or login credentials. They do not provision a store, initial staff account, terminal trust or starter menu.

Normal application setup and upgrades must continue to use the existing Flyway migrations. These standalone scripts do not create Flyway's migration history: do not run them before pointing the app at a new database, and do not use them to upgrade an existing database. Application installers and the development launcher already perform the appropriate migrations.

## PostgreSQL

The installed schema expects the PostgreSQL permission roles `pos_owner` and `pos_runtime`, which the normal installer creates. For a separate manual setup, an administrator can create these without login credentials:

```sql
CREATE ROLE pos_owner NOLOGIN;
CREATE ROLE pos_runtime NOLOGIN;
CREATE DATABASE onlinepos_schema_review OWNER pos_owner;
```

Skip role creation if those roles already exist. Run the setup as `pos_owner`, or as an administrator who first assumes `pos_owner` with `SET ROLE pos_owner`. Create a dedicated empty database using your usual PostgreSQL administration tools, then run:

```sh
psql -X --set=ON_ERROR_STOP=1 --dbname=YOUR_EMPTY_DATABASE --command="SET ROLE pos_owner" --file=docs/database/postgresql-setup.sql
```

The script runs within one transaction. Use a role allowed to create schema objects. Supply authentication through your normal PostgreSQL configuration; no password is embedded in the script.

## H2

Use the same H2 version as the application and its PostgreSQL compatibility options. For an in-memory structural check:

```sh
java -cp /path/to/h2.jar org.h2.tools.RunScript \
  -url 'jdbc:h2:mem:setup_check;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH' \
  -user sa -script docs/database/h2-setup.sql
```

H2 DDL may commit as it executes. Run against an empty database and stop on the first error.

## Regenerate after changing migrations

From the project root:

```sh
python3 tools/export-database-setup.py
python3 tools/export-database-setup.py --check
```

The migration files under `store-server/src/main/resources/db/` remain the source of truth. Do not edit generated setup SQL directly.
