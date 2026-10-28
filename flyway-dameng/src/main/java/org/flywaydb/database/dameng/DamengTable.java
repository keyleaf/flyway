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

import org.flywaydb.core.internal.database.base.Table;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;
import java.sql.SQLException;

public class DamengTable extends Table<DamengDatabase, DamengSchema> {
    public DamengTable(JdbcTemplate jdbcTemplate, DamengDatabase database, DamengSchema schema, String name) {
        super(jdbcTemplate, database, schema, name);
    }

    @Override
    protected boolean doExists() throws SQLException {
        return jdbcTemplate.queryForInt("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME=?",
                schema.getName(), name) > 0;
    }

    @Override
    protected void doDrop() throws SQLException {
        jdbcTemplate.execute("DROP TABLE " + this + " CASCADE CONSTRAINTS");
    }

    @Override
    protected void doLock() throws SQLException {
        // A table lock also serializes migrations while the history table is empty.
        jdbcTemplate.execute("LOCK TABLE " + this + " IN EXCLUSIVE MODE");
    }
}
