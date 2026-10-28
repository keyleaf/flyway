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

import org.flywaydb.core.internal.database.base.Schema;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;

public class DamengSchema extends Schema<DamengDatabase, DamengTable> {
    public DamengSchema(JdbcTemplate jdbcTemplate, DamengDatabase database, String name) {
        super(jdbcTemplate, database, name);
    }

    @Override
    protected boolean doExists() throws SQLException {
        return jdbcTemplate.queryForInt("SELECT COUNT(*) FROM SYS.SYSOBJECTS WHERE TYPE$='SCH' AND NAME=?", name) > 0;
    }

    @Override
    protected boolean doEmpty() throws SQLException {
        return jdbcTemplate.queryForInt("SELECT COUNT(*) FROM ALL_OBJECTS WHERE OWNER=?", name) == 0;
    }

    @Override
    protected void doCreate() throws SQLException {
        jdbcTemplate.execute("CREATE SCHEMA " + database.quote(name) + " AUTHORIZATION "
                + database.quote(database.getCurrentUser()));
    }

    @Override
    protected void doDrop() throws SQLException {
        // Dameng refuses to drop a schema while it is the session's current schema.
        jdbcTemplate.execute("SET SCHEMA " + database.quote(database.getCurrentUser()));
        jdbcTemplate.execute("DROP SCHEMA " + database.quote(name) + " CASCADE");
    }

    @Override
    protected void doClean() throws SQLException {
        // Drop dependants before their tables and types. Package/type bodies are removed with their definitions.
        for (String type : new String[]{"VIEW", "TRIGGER", "PROCEDURE", "FUNCTION", "PACKAGE", "SYNONYM"}) {
            this.dropObjects(type);
        }
        for (DamengTable table : super.allTables()) {
            table.drop();
        }
        this.dropObjects("SEQUENCE");
        this.dropObjects("TYPE");
    }

    private void dropObjects(String type) throws SQLException {
        List<String> names = jdbcTemplate.queryForStringList(
                "SELECT OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER=? AND OBJECT_TYPE=?", name, type);
        for (String objectName : names) {
            jdbcTemplate.execute("DROP " + type + " " + database.quote(name, objectName));
        }
    }

    @Override
    protected DamengTable[] doAllTables() throws SQLException {
        List<String> names = jdbcTemplate.queryForStringList(
                "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME", name);
        DamengTable[] tables = new DamengTable[names.size()];
        for (int i = 0; i < names.size(); i++) {
            tables[i] = new DamengTable(jdbcTemplate, database, this, names.get(i));
        }
        return tables;
    }

    @Override
    public DamengTable getTable(String tableName) {
        return new DamengTable(jdbcTemplate, database, this, tableName);
    }
}
