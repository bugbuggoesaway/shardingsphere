/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.core.metadata.generator;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.spi.ddlgenerator.CreateTableSQLGeneratorFactory;
import org.apache.shardingsphere.infra.binder.LogicSQL;
import org.apache.shardingsphere.infra.binder.SQLStatementContextFactory;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.statement.ddl.AlterTableStatementContext;
import org.apache.shardingsphere.infra.binder.statement.ddl.CommentStatementContext;
import org.apache.shardingsphere.infra.binder.statement.ddl.CreateIndexStatementContext;
import org.apache.shardingsphere.infra.binder.statement.ddl.CreateTableStatementContext;
import org.apache.shardingsphere.infra.binder.type.ConstraintAvailable;
import org.apache.shardingsphere.infra.binder.type.IndexAvailable;
import org.apache.shardingsphere.infra.binder.type.TableAvailable;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.exception.ShardingSphereException;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.schema.util.IndexMetaDataUtil;
import org.apache.shardingsphere.infra.parser.ShardingSphereSQLParserEngine;
import org.apache.shardingsphere.infra.rule.identifier.type.DataNodeContainedRule;
import org.apache.shardingsphere.sql.parser.sql.common.segment.SQLSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.ddl.constraint.ConstraintSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.ddl.index.IndexSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.generic.table.SimpleTableSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.generic.table.TableNameSegment;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pipeline ddl generator.
 */
@RequiredArgsConstructor
@Slf4j
public final class PipelineDDLGenerator {
    
    private static final String DELIMITER = ";";
    
    private static final String SET_SEARCH_PATH_PREFIX = "set search_path";
    
    /**
     * Generate logic ddl sql.
     *
     * @param database database
     * @param dataSourceName data source name
     * @param schemaName schema name
     * @param logicTableName table name
     * @param actualTableName actual table name
     * @param parserEngine parser engine
     * @return ddl SQL
     */
    @SneakyThrows
    public String generateLogicDDLSQL(final ShardingSphereDatabase database, final String dataSourceName, final String schemaName,
                                      final String logicTableName, final String actualTableName,
                                      final ShardingSphereSQLParserEngine parserEngine) {
        DatabaseType databaseType = database.getProtocolType();
        log.info("generateLogicDDLSQL, databaseType={}, databaseName={}, schemaName={}, tableName={}, dataSourceNames={}",
                databaseType.getType(), database.getName(), schemaName, logicTableName, database.getResource().getDataSources().keySet());
        Collection<String> multiSQL = generateActualDDLSQL(databaseType, schemaName, actualTableName, database.getResource().getDataSources().get(dataSourceName));
        StringBuilder result = new StringBuilder();
        for (String each : multiSQL) {
            Optional<String> logicSQL = decorate(databaseType, database.getName(), schemaName, database, each, parserEngine);
            logicSQL.ifPresent(ddlSQL -> result.append(ddlSQL).append(DELIMITER).append(System.lineSeparator()));
        }
        return result.toString();
    }
    
    /**
     * Replace table name with prefix.
     *
     * @param sql sql
     * @param prefix prefix
     * @param databaseName database name
     * @param parserEngine parser engine
     * @return replaced sql
     */
    public String replaceTableNameWithPrefix(final String sql, final String prefix, final String databaseName,
                                             final ShardingSphereSQLParserEngine parserEngine) {
        LogicSQL logicSQL = getLogicSQL(sql, databaseName, parserEngine);
        SQLStatementContext<?> sqlStatementContext = logicSQL.getSqlStatementContext();
        if (sqlStatementContext instanceof CreateTableStatementContext || sqlStatementContext instanceof CommentStatementContext || sqlStatementContext instanceof CreateIndexStatementContext
                || sqlStatementContext instanceof AlterTableStatementContext) {
            if (!sqlStatementContext.getTablesContext().getTables().isEmpty()) {
                TableNameSegment tableNameSegment = sqlStatementContext.getTablesContext().getTables().iterator().next().getTableName();
                Map<SQLSegment, String> replaceMap = new TreeMap<>(Comparator.comparing(SQLSegment::getStartIndex));
                replaceMap.put(tableNameSegment, prefix + tableNameSegment.getIdentifier().getValue());
                return doDecorateActualTable(replaceMap, sql);
            }
        }
        return sql;
    }
    
    private Optional<String> decorate(final DatabaseType databaseType, final String databaseName, final String schemaName, final ShardingSphereDatabase database, final String sql,
                                      final ShardingSphereSQLParserEngine parserEngine) {
        if (sql.trim().isEmpty()) {
            return Optional.empty();
        }
        String result = decorateActualSQL(sql.trim(), database, databaseName, parserEngine);
        // TODO remove it after set search_path is supported.
        if ("openGauss".equals(databaseType.getType())) {
            return decorateOpenGauss(databaseName, schemaName, result, parserEngine);
        }
        return Optional.of(result);
    }
    
    private Collection<String> generateActualDDLSQL(final DatabaseType databaseType, final String schemaName, final String actualTable, final DataSource dataSource) throws SQLException {
        return CreateTableSQLGeneratorFactory.findInstance(databaseType).orElseThrow(() -> new ShardingSphereException("Failed to get dialect ddl sql generator"))
                .generate(actualTable, schemaName, dataSource);
    }
    
    private String decorateActualSQL(final String sql, final ShardingSphereDatabase database, final String databaseName, final ShardingSphereSQLParserEngine parserEngine) {
        LogicSQL logicSQL = getLogicSQL(sql, databaseName, parserEngine);
        SQLStatementContext<?> sqlStatementContext = logicSQL.getSqlStatementContext();
        Map<SQLSegment, String> replaceMap = new TreeMap<>(Comparator.comparing(SQLSegment::getStartIndex));
        if (sqlStatementContext instanceof CreateTableStatementContext) {
            appendFromIndexAndConstraint(replaceMap, database, sqlStatementContext);
            appendFromTable(replaceMap, database, (TableAvailable) sqlStatementContext);
        }
        if (sqlStatementContext instanceof CommentStatementContext) {
            appendFromTable(replaceMap, database, (TableAvailable) sqlStatementContext);
        }
        if (sqlStatementContext instanceof CreateIndexStatementContext) {
            appendFromTable(replaceMap, database, (TableAvailable) sqlStatementContext);
            appendFromIndexAndConstraint(replaceMap, database, sqlStatementContext);
        }
        if (sqlStatementContext instanceof AlterTableStatementContext) {
            appendFromIndexAndConstraint(replaceMap, database, sqlStatementContext);
            appendFromTable(replaceMap, database, (TableAvailable) sqlStatementContext);
        }
        return doDecorateActualTable(replaceMap, sql);
    }
    
    private void appendFromIndexAndConstraint(final Map<SQLSegment, String> replaceMap, final ShardingSphereDatabase database, final SQLStatementContext<?> sqlStatementContext) {
        if (!(sqlStatementContext instanceof TableAvailable) || ((TableAvailable) sqlStatementContext).getTablesContext().getTables().isEmpty()) {
            return;
        }
        TableNameSegment tableNameSegment = ((TableAvailable) sqlStatementContext).getTablesContext().getTables().iterator().next().getTableName();
        String logicTable = findLogicTable(tableNameSegment, database);
        if (!tableNameSegment.getIdentifier().getValue().equals(logicTable)) {
            if (sqlStatementContext instanceof IndexAvailable) {
                for (IndexSegment each : ((IndexAvailable) sqlStatementContext).getIndexes()) {
                    String logicIndexName = IndexMetaDataUtil.getLogicIndexName(each.getIndexName().getIdentifier().getValue(), tableNameSegment.getIdentifier().getValue());
                    replaceMap.put(each.getIndexName(), logicIndexName);
                }
            }
            if (sqlStatementContext instanceof ConstraintAvailable) {
                for (ConstraintSegment each : ((ConstraintAvailable) sqlStatementContext).getConstraints()) {
                    String logicConstraint = IndexMetaDataUtil.getLogicIndexName(each.getIdentifier().getValue(), tableNameSegment.getIdentifier().getValue());
                    replaceMap.put(each, logicConstraint);
                }
            }
        }
    }
    
    private void appendFromTable(final Map<SQLSegment, String> replaceMap, final ShardingSphereDatabase database, final TableAvailable sqlStatementContext) {
        for (SimpleTableSegment each : sqlStatementContext.getAllTables()) {
            String logicTable = findLogicTable(each.getTableName(), database);
            if (!logicTable.equals(each.getTableName().getIdentifier().getValue())) {
                replaceMap.put(each.getTableName(), logicTable);
            }
        }
    }
    
    private String doDecorateActualTable(final Map<SQLSegment, String> replaceMap, final String sql) {
        StringBuilder result = new StringBuilder();
        int lastStopIndex = 0;
        for (Entry<SQLSegment, String> entry : replaceMap.entrySet()) {
            result.append(sql, lastStopIndex, entry.getKey().getStartIndex());
            result.append(entry.getValue());
            lastStopIndex = entry.getKey().getStopIndex() + 1;
        }
        if (lastStopIndex < sql.length()) {
            result.append(sql, lastStopIndex, sql.length());
        }
        return result.toString();
    }
    
    private String findLogicTable(final TableNameSegment tableNameSegment, final ShardingSphereDatabase database) {
        String actualTable = tableNameSegment.getIdentifier().getValue();
        return database.getRuleMetaData().getRules().stream().filter(each -> each instanceof DataNodeContainedRule)
                .map(each -> ((DataNodeContainedRule) each).findLogicTableByActualTable(actualTable).orElse(null)).filter(Objects::nonNull).findFirst().orElse(actualTable);
    }
    
    private LogicSQL getLogicSQL(final String sql, final String databaseName, final ShardingSphereSQLParserEngine parserEngine) {
        SQLStatement sqlStatement = parserEngine.parse(sql, false);
        SQLStatementContext<?> sqlStatementContext = SQLStatementContextFactory.newInstance(null, sqlStatement, databaseName);
        return new LogicSQL(sqlStatementContext, sql, Collections.emptyList());
    }
    
    // TODO remove it after set search_path is supported.
    private Optional<String> decorateOpenGauss(final String databaseName, final String schemaName, final String logicSQL,
                                               final ShardingSphereSQLParserEngine parserEngine) {
        if (logicSQL.toLowerCase().startsWith(SET_SEARCH_PATH_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(replaceTableNameWithPrefix(logicSQL, schemaName + ".", databaseName, parserEngine));
    }
}
