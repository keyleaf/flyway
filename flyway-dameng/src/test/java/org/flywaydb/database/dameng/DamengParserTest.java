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

import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.database.DatabaseTypeRegister;
import org.flywaydb.core.internal.database.postgresql.PostgreSQLDatabaseType;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.core.internal.sqlscript.SqlStatementIterator;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class DamengParserTest {
    @Test
    public void discoversDatabaseTypeAlongsideMysqlAndPostgresql() {
        assertEquals("Dameng", DatabaseTypeRegister.getDatabaseTypeForUrl("jdbc:dm://localhost:5236").getName());
        assertEquals("MySQL", DatabaseTypeRegister.getDatabaseTypeForUrl("jdbc:mysql://localhost/iap").getName());
        assertTrue(new PostgreSQLDatabaseType()
                .handlesJDBCUrl("jdbc:postgresql://localhost/iap"));
        DamengDatabaseType type = new DamengDatabaseType();
        assertTrue(type.handlesDatabaseProductNameAndVersion("DM DBMS 8.0", "8.0", null));
        assertFalse(type.handlesDatabaseProductNameAndVersion("MySQL 8.0", "8.0", null));
        assertFalse(type.handlesJDBCUrl("jdbc:mysql://localhost/iap"));
        assertFalse(type.handlesJDBCUrl("jdbc:postgresql://localhost/iap"));
    }

    @Test
    public void preservesIdentityCommandsAndQuotedSemicolons() {
        List<String> sql = this.parse("SET IDENTITY_INSERT \"client\" ON;\n"
                + "INSERT INTO \"client\" (id, value) VALUES (1, 'text; it''s -- data');\n"
                + "SET IDENTITY_INSERT \"client\" OFF;\n");
        assertEquals(3, sql.size());
        assertTrue(sql.get(0).contains("IDENTITY_INSERT"));
        assertTrue(sql.get(1).contains("'text; it''s -- data'"));
        assertTrue(sql.get(2).endsWith("OFF"));
    }

    @Test
    public void keepsProceduralBlocksTogether() {
        List<String> sql = this.parse("BEGIN\n INSERT INTO sample VALUES (1);\n INSERT INTO sample VALUES (2);\nEND;\n/\n"
                + "INSERT INTO sample VALUES (3);\n");
        assertEquals(2, sql.size());
        assertTrue(sql.get(0).contains("VALUES (2);"));
        assertTrue(sql.get(1).contains("VALUES (3)"));
    }

    private List<String> parse(String script) {
        List<String> sql = new ArrayList<>();
        DamengParser parser = new DamengParser(new ClassicConfiguration(), new ParsingContext());
        try (SqlStatementIterator iterator = parser.parse(new StringResource(script))) {
            while (iterator.hasNext()) {
                sql.add(iterator.next().getSql());
            }
        }
        return sql;
    }
}
