# DB-Diff Studio

DB-Diff Studio is a desktop tool for comparing MariaDB and MySQL schemas and exporting reviewed SQL. It connects to two endpoints, lets you choose tables, views, and routines, shows missing and changed objects, and exports SQL for selected targets. It never executes generated SQL.

## Requirements

- JDK 21 or later
- Network access the first time you launch, to download the MariaDB and MySQL JDBC drivers (after that they are cached locally)
- Two reachable instances of the selected database engine for a comparison

## Run

On Windows, run `run.bat`. It compiles the Java sources and launches the app. The JDBC driver is downloaded to `%LOCALAPPDATA%\DB-Diff-Studio\drivers`; credentials are never written there. On macOS or Linux, run `./run.sh`.

The connection dialog accepts a database engine, then a host, port, database, user, and password for each endpoint. Both endpoints use the selected engine in this version. Passwords stay in memory for the session and are cleared when the window closes. TLS can be enabled per connection. The app does not persist connection profiles.

After connecting, the object list is marked **not compared** until analysis runs. Use Ctrl-click or Shift-click to select individual objects. Compare analyzes the selected objects; with no selection, it analyzes every object in the enabled kinds. Select one compared table to compare its rows. Export SQL applies only to selected comparison results; with no selection, it asks you to select results rather than exporting everything.

## Current scope

- MariaDB and MySQL endpoint connections through JDBC. MySQL uses the official Connector/J driver and targets MySQL Server 8.0 or later.
- Listing and selection of tables, views, procedures, and functions.
- Table column and index metadata comparison; view and routine definition comparison.
- Selected-table row comparison using primary keys. Rows are classified as source-only, target-only, or different; INSERT and UPDATE exports are available, with DELETE opt-in.
- Readable line-by-line definition differences and missing-object status.
- `SHOW CREATE` based DDL for tables, views, procedures, and functions when the server grants access. Source-only tables/views export CREATE; changed views export CREATE OR REPLACE; changed routines export reviewed DROP + CREATE scripts. Every export selects the configured target database and is never executed.
- Changed table definitions remain review-only: the app exports the complete source CREATE TABLE for inspection but does not guess an ALTER migration.

SQL Server and other engines can be added behind the `DatabaseConnector` interface. MySQL and MariaDB are compared only within the same engine; cross-engine comparisons are not supported yet. Server permissions or versions may prevent `SHOW CREATE`; in that case the app displays the error and exports a review-only note instead of incomplete DDL. Routine SQL exports use the MySQL-family client `DELIMITER` directive and should be reviewed in a compatible SQL client. Row comparison skips tables without primary keys, tables whose full column signatures differ (including `COLUMN_TYPE`, character set, collation, nullability, and datetime precision), and types that cannot be safely ordered or compared. It streams rows in primary-key order with server-side cursor fetches and retains at most 10,000 changed rows per comparison; exports indicate when that cap was reached and contain only retained rows. Supported row types include common numeric, text, date/time, enum, and set values; binary, JSON, spatial, and other unsupported types are skipped. Row SQL is qualified with the target database and is never executed by the app.

## Design

The app uses Java 21 and Swing so it can run on a stock JDK without a separate UI toolkit or build system. JDBC access and object extraction live behind `DatabaseConnector`; `MariaDbConnector` and `MySqlConnector` share a MySQL-family implementation. The UI runs database work off the event thread and owns the short-lived password arrays used for login.
