/*
 * Copyright (C) Red Gate Software Ltd 2010-2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.database.dameng;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.Before;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in integration tests; each test creates and removes its own schema. */
public class DamengDatabaseTest {
    private String url;
    private String user;
    private String password;

    @Before
    public void configureConnection() throws Exception {
        url = System.getenv("DM_TEST_URL");
        assumeTrue("Set DM_TEST_URL, DM_TEST_USER and DM_TEST_PASSWORD to run against DM8", url != null);
        user = System.getenv("DM_TEST_USER");
        password = System.getenv("DM_TEST_PASSWORD");
        Class.forName("dm.jdbc.driver.DmDriver");
    }

    @Test
    public void migratesValidatesAndCleansItsOwnSchema() throws Exception {
        String schema = this.newSchema();
        Path dir = Files.createTempDirectory("flyway-dameng-");
        this.write(dir, "V1__tables.sql", "CREATE TABLE sample (id INT IDENTITY(1, 1) PRIMARY KEY, value VARCHAR(100));\n"
                + "CREATE TABLE sample_ref (sample_id INT REFERENCES sample(id));\n"
                + "CREATE VIEW sample_view AS SELECT id, value FROM sample;\nCREATE SEQUENCE sample_seq;\n");
        this.write(dir, "V2__data.sql", "SET IDENTITY_INSERT sample ON;\n"
                + "INSERT INTO sample (id, value) VALUES (10, 'text; it''s -- data');\n"
                + "SET IDENTITY_INSERT sample OFF;\nINSERT INTO sample (value) VALUES ('automatic');\n");
        this.write(dir, "V3__block.sql", "CREATE OR REPLACE PROCEDURE sample_proc AS\nBEGIN\n"
                + " INSERT INTO sample (value) VALUES ('procedure');\nEND;\n/\nCALL sample_proc();\n"
                + "BEGIN\n INSERT INTO sample (value) VALUES ('block');\nEND;\n/\n");
        Flyway flyway = this.configuration(schema, dir).tablespace("MAIN").load();
        try {
            assertEquals(3, flyway.migrate().migrationsExecuted);
            flyway.validate();
            assertEquals(3, Arrays.stream(flyway.info().applied())
                    .filter(migration -> migration.getVersion() != null).count());
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (Connection c = this.connection(); Statement s = c.createStatement();
                 ResultSet rows = s.executeQuery("SELECT COUNT(*), MAX(id) FROM \"" + schema + "\".sample")) {
                assertTrue(rows.next());
                assertEquals(4, rows.getInt(1));
                assertEquals(13, rows.getInt(2));
            }
            try (Connection c = this.connection(); Statement s = c.createStatement();
                 ResultSet rows = s.executeQuery("SELECT TABLESPACE_NAME FROM ALL_TABLES WHERE OWNER='" + schema
                         + "' AND TABLE_NAME='flyway_schema_history'")) {
                assertTrue(rows.next());
                assertEquals("MAIN", rows.getString(1));
            }
        } finally {
            flyway.clean();
            this.deleteScripts(dir);
        }
        assertEquals(0, this.schemaCount(schema));
    }

    @Test
    public void baselinesAnExistingSchemaAndRepairsAFailedMigration() throws Exception {
        String schema = this.newSchema();
        Path dir = Files.createTempDirectory("flyway-dameng-baseline-");
        try (Connection c = this.connection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA \"" + schema + "\" AUTHORIZATION \"" + user + "\"");
            s.execute("CREATE TABLE \"" + schema + "\".existing_table (id INT)");
        }
        this.write(dir, "V1__.sql", "INSERT INTO existing_table VALUES (1);\n");
        Flyway flyway = this.configuration(schema, dir).baselineOnMigrate(true).baselineVersion("0")
                .baselineDescription("").load();
        try {
            assertEquals(1, flyway.migrate().migrationsExecuted);
            this.write(dir, "V2__repair.sql", "INSERT INTO missing_table VALUES (2);\n");
            try {
                flyway.migrate();
                fail("Expected missing table migration to fail");
            } catch (FlywayException expected) {
                assertTrue(expected.getMessage().contains("V2__repair.sql"));
            }
            flyway.repair();
            this.write(dir, "V2__repair.sql", "INSERT INTO existing_table VALUES (2);\n");
            assertEquals(1, flyway.migrate().migrationsExecuted);
            flyway.validate();
        } finally {
            flyway.clean();
            try (Connection c = this.connection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
            this.deleteScripts(dir);
        }
    }

    @Test
    public void serializesConcurrentMigrators() throws Exception {
        String schema = this.newSchema();
        Path dir = Files.createTempDirectory("flyway-dameng-lock-");
        this.write(dir, "V1__table.sql", "CREATE TABLE sample (id INT PRIMARY KEY);\n");
        Flyway flyway = this.configuration(schema, dir).load();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            flyway.migrate();
            this.write(dir, "V2__data.sql", "INSERT INTO sample VALUES (1);\n");
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> migrate = () -> {
                start.await();
                return this.configuration(schema, dir).load().migrate().migrationsExecuted;
            };
            Future<Integer> first = executor.submit(migrate);
            Future<Integer> second = executor.submit(migrate);
            start.countDown();
            assertEquals(1, first.get(30, TimeUnit.SECONDS)
                    + second.get(30, TimeUnit.SECONDS));
            flyway.validate();
        } finally {
            executor.shutdownNow();
            flyway.clean();
            this.deleteScripts(dir);
        }
    }

    @Test
    public void treatsAViewOnlySchemaAsNonEmpty() throws Exception {
        String schema = this.newSchema();
        Path dir = Files.createTempDirectory("flyway-dameng-view-");
        try (Connection c = this.connection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA \"" + schema + "\" AUTHORIZATION \"" + user + "\"");
            s.execute("CREATE VIEW \"" + schema + "\".existing_view AS SELECT 1 AS id FROM DUAL");
        }
        this.write(dir, "V1__table.sql", "CREATE TABLE sample (id INT);\n");
        Flyway flyway = this.configuration(schema, dir).load();
        try {
            try {
                flyway.migrate();
                fail("A schema containing a view requires an explicit baseline");
            } catch (FlywayException expected) {
                assertTrue(expected.getMessage().contains("non-empty schema"));
            }
        } finally {
            flyway.clean();
            try (Connection c = this.connection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
            this.deleteScripts(dir);
        }
    }

    private FluentConfiguration configuration(String schema, Path dir) {
        return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + dir.toAbsolutePath()).cleanDisabled(false);
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    private String newSchema() {
        return "FW_DM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private int schemaCount(String schema) throws Exception {
        try (Connection c = this.connection(); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery("SELECT COUNT(*) FROM SYS.SYSOBJECTS WHERE TYPE$='SCH' AND NAME='" + schema + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void write(Path dir, String name, String sql) throws Exception {
        Files.write(dir.resolve(name), sql.getBytes(StandardCharsets.UTF_8));
    }

    private void deleteScripts(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                Files.delete(path);
            }
        }
        Files.delete(dir);
    }
}
