# Dameng support for Flyway 8.5.11

Add `org.flywaydb:flyway-dameng:8.5.11-iap` alongside `flyway-core:8.5.11` and the application's Dameng JDBC driver. MySQL and PostgreSQL support can coexist on the classpath. The driver is not bundled with this module.

The module recognizes `jdbc:dm:` and `DM DBMS`, supports DM8 schemas, schema history with optional tablespace placement, table locking, migrate, validate, baseline, repair and clean. SQL uses semicolon delimiters; Oracle-style procedural blocks use `/` on a separate line. Dameng DDL commits implicitly and cannot be rolled back as a migration transaction.

Use a JDBC driver whose database major/minor version metadata is valid. On the local DM8 20260427 image, DmJdbcDriver18 8.2.2.70 throws NumberFormatException for `getDatabaseMinorVersion()` before Flyway can select a database module. Version 8.1.2.192 returns valid metadata and is used only as a test dependency here. Match the production driver to the server and verify it independently.

Build and unit tests (JDK 9 or later; produces Java 8 bytecode):

```sh
./mvnw -pl flyway-dameng test
```

Integration tests are skipped unless `DM_TEST_URL`, `DM_TEST_USER`, and `DM_TEST_PASSWORD` are set. With those variables, the same command creates unique `FW_DM_*` schemas, exercises migration/history/identity/procedural SQL and baseline/repair, then removes those schemas. The account needs permission to create and drop its own test schemas. No existing application schema is cleaned.

This module is intended for application API use. The standalone Flyway CLI distribution must explicitly add the module to its dependencies and assembly if needed.

## Verification (2026-10-28)

Built with JDK 17 targeting Java 8 and ran all seven tests on Java 8 against the local DM8 20260427 instance: three parser/database-selection tests and four integration tests, all passed. Integration coverage includes concurrent migrators, identity values, stored procedures, anonymous blocks, repeat migration, validate, baseline, repair, and clean.

The three IAP Dameng baseline migrations were also executed through this module in a temporary schema. All three succeeded, a second migrate applied zero migrations, 50 unique constraints were present, and row counts included 39,943 administrative divisions and 607 menus. All generated verification schemas were removed afterwards.

See [COMPARISON.md](COMPARISON.md) for the source and behavior comparison with flyway-core-dm:7.1.1, including the tablespace omission corrected after comparison.
