/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2016 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.ext.postgresql.model;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.ext.postgresql.PostgreConstants;
import org.jkiss.dbeaver.ext.postgresql.PostgreDataSourceProvider;
import org.jkiss.dbeaver.ext.postgresql.PostgreUtils;
import org.jkiss.dbeaver.ext.postgresql.model.jdbc.PostgreJdbcFactory;
import org.jkiss.dbeaver.ext.postgresql.model.plan.PostgrePlanAnalyser;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPErrorAssistant;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.exec.jdbc.*;
import org.jkiss.dbeaver.model.exec.plan.DBCPlan;
import org.jkiss.dbeaver.model.exec.plan.DBCQueryPlanner;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCDataSource;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCExecutionContext;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectCache;
import org.jkiss.dbeaver.model.impl.sql.QueryTransformerLimit;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.dbeaver.runtime.net.DefaultCallbackHandler;
import org.jkiss.utils.CommonUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreDataSource
 */
public class PostgreDataSource extends JDBCDataSource implements DBSObjectSelector, DBSInstanceContainer, DBCQueryPlanner, IAdaptable
{

    private static final Log log = Log.getLog(PostgreDataSource.class);

    private final DatabaseCache databaseCache = new DatabaseCache();
    private String activeDatabaseName;
    private String activeSchemaName;
    private boolean databaseSwitchInProgress;
    private final List<String> searchPath = new ArrayList<>();
    private String activeUser;

    public PostgreDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container)
        throws DBException
    {
        super(monitor, container);
    }

    @Override
    protected Map<String, String> getInternalConnectionProperties(DBRProgressMonitor monitor) throws DBCException
    {
        Map<String, String> props = new LinkedHashMap<>(PostgreDataSourceProvider.getConnectionsProps());
        final DBWHandlerConfiguration sslConfig = getContainer().getActualConnectionConfiguration().getDeclaredHandler(PostgreConstants.HANDLER_SSL);
        if (sslConfig != null && sslConfig.isEnabled()) {
            try {
                initSSL(props, sslConfig);
            } catch (Exception e) {
                throw new DBCException("Error configuring SSL certificates", e);
            }
        }
        return props;
    }

    private void initSSL(Map<String, String> props, DBWHandlerConfiguration sslConfig) throws Exception {
        props.put("ssl", "true");

        final String rootCertProp = sslConfig.getProperties().get(PostgreConstants.PROP_SSL_ROOT_CERT);
        if (!CommonUtils.isEmpty(rootCertProp)) {
            props.put("sslrootcert", rootCertProp);
        }
        final String clientCertProp = sslConfig.getProperties().get(PostgreConstants.PROP_SSL_CLIENT_CERT);
        if (!CommonUtils.isEmpty(clientCertProp)) {
            props.put("sslcert", clientCertProp);
        }
        final String keyCertProp = sslConfig.getProperties().get(PostgreConstants.PROP_SSL_CLIENT_KEY);
        if (!CommonUtils.isEmpty(keyCertProp)) {
            props.put("sslkey", keyCertProp);
        }

        final String modeProp = sslConfig.getProperties().get(PostgreConstants.PROP_SSL_MODE);
        if (!CommonUtils.isEmpty(modeProp)) {
            props.put("sslmode", modeProp);
        }
        final String factoryProp = sslConfig.getProperties().get(PostgreConstants.PROP_SSL_FACTORY);
        if (!CommonUtils.isEmpty(factoryProp)) {
            props.put("sslfactory", factoryProp);
        }
        props.put("sslpasswordcallback", DefaultCallbackHandler.class.getName());
    }

    protected void initializeContextState(@NotNull DBRProgressMonitor monitor, @NotNull JDBCExecutionContext context, boolean setActiveObject) throws DBCException {
        if (setActiveObject) {
            PostgreDatabase activeDatabase = getDefaultObject();
            if (activeDatabase != null) {
                final PostgreSchema activeSchema = activeDatabase.getDefaultObject();
                if (activeSchema != null) {
                    activeDatabase.setSearchPath(monitor, activeSchema, context);
                }
            }
        }
    }

    @Override
    protected PostgreDialect createSQLDialect(@NotNull JDBCDatabaseMetaData metaData) {
        return new PostgreDialect(metaData);
    }

    public DatabaseCache getDatabaseCache()
    {
        return databaseCache;
    }

    public Collection<PostgreDatabase> getDatabases()
    {
        return databaseCache.getCachedObjects();
    }

    public PostgreDatabase getDatabase(String name)
    {
        return databaseCache.getCachedObject(name);
    }


    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        super.initialize(monitor);

        activeDatabaseName = getContainer().getConnectionConfiguration().getDatabaseName();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load meta info")) {
            determineDefaultObjects(session);
        } catch (Exception e) {
            log.debug(e);
        }

        // Read databases
        databaseCache.getAllObjects(monitor, this);
        getDefaultInstance().cacheDataTypes(monitor);
    }

    private void determineDefaultObjects(JDBCSession session) throws DBCException, SQLException {
        try (JDBCPreparedStatement stat = session.prepareStatement("SELECT current_database(), current_schema(),session_user")) {
            try (JDBCResultSet rs = stat.executeQuery()) {
                if (rs.nextRow()) {
                    activeDatabaseName = JDBCUtils.safeGetString(rs, 1);
                    activeSchemaName = JDBCUtils.safeGetString(rs, 2);
                    activeUser = JDBCUtils.safeGetString(rs, 3);
                }
            }
        }
        String searchPathStr = JDBCUtils.queryString(session, "SHOW search_path");
        this.searchPath.clear();
        if (searchPathStr != null) {
            for (String str : searchPathStr.replace("$user", activeUser).split(",")) {
                str = str.trim();
                this.searchPath.add(DBUtils.getUnQuotedIdentifier(str, "\""));
            }
        } else {
            this.searchPath.add(PostgreConstants.PUBLIC_SCHEMA_NAME);
        }
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        super.refreshObject(monitor);

        this.databaseCache.clearCache();
        this.activeDatabaseName = null;

        this.initialize(monitor);

        return this;
    }

    @Override
    public Collection<? extends PostgreDatabase> getChildren(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        return getDatabases();
    }

    @Override
    public PostgreDatabase getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName)
        throws DBException
    {
        return getDatabase(childName);
    }

    @Override
    public Class<? extends PostgreDatabase> getChildType(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        return PostgreDatabase.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope)
        throws DBException
    {
        databaseCache.getAllObjects(monitor, this);
    }

    @Override
    public boolean supportsDefaultChange()
    {
        return true;
    }

    @Override
    public PostgreDatabase getDefaultObject()
    {
        return getDatabase(activeDatabaseName);
    }

    @Override
    public void setDefaultObject(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object)
        throws DBException
    {
        final PostgreDatabase oldDatabase = getDefaultObject();
        if (!(object instanceof PostgreDatabase)) {
            throw new IllegalArgumentException("Invalid object type: " + object);
        }
        final PostgreDatabase newDatabase = (PostgreDatabase) object;
        if (oldDatabase == newDatabase) {
            // The same
            return;
        }

        // FIXME: make real target database change
        // 1. Check active transactions
        // 2. Reconnect all open contexts
        // 3. Refresh datasource tree
        activeDatabaseName = object.getName();
        try {
            databaseSwitchInProgress = true;
            for (JDBCExecutionContext context : getAllContexts()) {
                context.reconnect(monitor);
            }
            getDefaultInstance().cacheDataTypes(monitor);
        }
        finally {
            databaseSwitchInProgress = false;
        }

        if (oldDatabase != null) {
            DBUtils.fireObjectSelect(oldDatabase, false);
            DBUtils.fireObjectUpdate(oldDatabase, false);
        }
        DBUtils.fireObjectSelect(newDatabase, true);
        DBUtils.fireObjectUpdate(newDatabase, true);
    }

    @Override
    public boolean refreshDefaultObject(@NotNull DBCSession session) throws DBException {
        // Check only for schema change. Database cannot be changed by any SQL query
        final PostgreDatabase activeDatabase = getDefaultObject();
        if (activeDatabase == null) {
            return false;
        }
        try {
            String oldDefSchema = activeSchemaName;
            determineDefaultObjects((JDBCSession) session);
            if (activeSchemaName != null && !CommonUtils.equalObjects(oldDefSchema, activeSchemaName)) {
                final PostgreSchema newSchema = activeDatabase.getSchema(session.getProgressMonitor(), activeSchemaName);
                if (newSchema != null) {
                    activeDatabase.setDefaultObject(session.getProgressMonitor(), newSchema);
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new DBException(e, this);
        }
    }

    public String getActiveUser() {
        return activeUser;
    }

    public String getActiveSchemaName() {
        return activeSchemaName;
    }

    public void setActiveSchemaName(String activeSchemaName) {
        this.activeSchemaName = activeSchemaName;
    }

    public List<String> getSearchPath() {
        return searchPath;
    }

    public void setSearchPath(String path) {
        searchPath.clear();
        searchPath.add(path);
        if (!path.equals(activeUser)) {
            searchPath.add(activeUser);
        }
    }

    @Override
    protected Connection openConnection(@NotNull DBRProgressMonitor monitor, @NotNull String purpose) throws DBCException {
        Connection pgConnection;
        if (databaseSwitchInProgress) {
            final DBPConnectionConfiguration conConfig = getContainer().getActualConnectionConfiguration();
            final DBPConnectionConfiguration originalConfig = new DBPConnectionConfiguration(conConfig);
            try {
                // Patch URL with new database name
                conConfig.setDatabaseName(activeDatabaseName);
                conConfig.setUrl(getContainer().getDriver().getDataSourceProvider().getConnectionURL(getContainer().getDriver(), conConfig));

                pgConnection = super.openConnection(monitor, purpose);
            }
            finally {
                conConfig.setDatabaseName(originalConfig.getDatabaseName());
                conConfig.setUrl(originalConfig.getUrl());
            }
        } else {
            pgConnection = super.openConnection(monitor, purpose);
        }

        {
            // Provide client info
            IProduct product = Platform.getProduct();
            if (product != null) {
                String appName = DBeaverCore.getProductTitle();
                try {
                    pgConnection.setClientInfo("ApplicationName", appName + " - " + purpose);
                } catch (Throwable e) {
                    // just ignore
                    log.debug(e);
                }
            }
        }

        return pgConnection;
    }

    @Override
    public DBCPlan planQueryExecution(DBCSession session, String query) throws DBCException
    {
        PostgrePlanAnalyser plan = new PostgrePlanAnalyser(query);
        plan.explain(session);
        return plan;
    }

    @Override
    public Object getAdapter(Class adapter)
    {
        if (adapter == DBSStructureAssistant.class) {
            return new PostgreStructureAssistant(this);
        }
/*
        else if (adapter == DBAServerSessionManager.class) {
            return new PostgreSessionManager(this);
        }
*/
        return super.getAdapter(adapter);
    }

    @NotNull
    @Override
    public PostgreDataSource getDataSource() {
        return this;
    }

    @Override
    public Collection<PostgreDataType> getLocalDataTypes()
    {
        final PostgreSchema schema = getDefaultInstance().getCatalogSchema();
        if (schema != null) {
            return schema.dataTypeCache.getCachedObjects();
        }
        return null;
    }

    @Override
    public PostgreDataType getLocalDataType(String typeName)
    {
        return getDefaultInstance().getDataType(typeName);
    }

    @Override
    public String getDefaultDataTypeName(@NotNull DBPDataKind dataKind) {
        return PostgreUtils.getDefaultDataTypeName(dataKind);
    }

    @NotNull
    @Override
    public PostgreDatabase getDefaultInstance() {
        PostgreDatabase defDatabase = databaseCache.getCachedObject(activeDatabaseName);
        if (defDatabase == null) {
            defDatabase = databaseCache.getCachedObject(PostgreConstants.DEFAULT_DATABASE);
        }
        if (defDatabase == null) {
            final List<PostgreDatabase> allDatabases = databaseCache.getCachedObjects();
            if (allDatabases.isEmpty()) {
                throw new IllegalStateException("No default database");
            }
            defDatabase = allDatabases.get(0);
        }
        return defDatabase;
    }

    @NotNull
    @Override
    public Collection<PostgreDatabase> getAvailableInstances() {
        return databaseCache.getCachedObjects();
    }

    class DatabaseCache extends JDBCObjectCache<PostgreDataSource, PostgreDatabase>
    {
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull PostgreDataSource owner) throws SQLException
        {
            final boolean showNDD = CommonUtils.toBoolean(getContainer().getActualConnectionConfiguration().getProperty(PostgreConstants.PROP_SHOW_NON_DEFAULT_DB));
            StringBuilder catalogQuery = new StringBuilder(
                "SELECT db.oid,db.*" +
                "\nFROM pg_catalog.pg_database db WHERE NOT datistemplate AND datallowconn");
            if (!showNDD) {
                catalogQuery.append("\nAND db.datname=?");
            }
            DBSObjectFilter catalogFilters = owner.getContainer().getObjectFilter(PostgreDatabase.class, null, false);
            if (showNDD) {
                if (catalogFilters != null) {
                    JDBCUtils.appendFilterClause(catalogQuery, catalogFilters, "datname", true);
                }
                catalogQuery.append("\nORDER BY db.datname");
            }
            JDBCPreparedStatement dbStat = session.prepareStatement(catalogQuery.toString());
            if (!showNDD) {
                dbStat.setString(1, activeDatabaseName);
            } else if (catalogFilters != null) {
                JDBCUtils.setFilterParameters(dbStat, 1, catalogFilters);
            }
            return dbStat;
        }

        @Override
        protected PostgreDatabase fetchObject(@NotNull JDBCSession session, @NotNull PostgreDataSource owner, @NotNull JDBCResultSet resultSet) throws SQLException, DBException
        {
            return new PostgreDatabase(owner, resultSet);
        }

    }

    private Pattern ERROR_POSITION_PATTERN = Pattern.compile("\\n\\s*Position: ([0-9]+)");

    @Nullable
    @Override
    public ErrorPosition[] getErrorPosition(@NotNull DBCSession session, @NotNull String query, @NotNull Throwable error) {
        String message = error.getMessage();
        if (!CommonUtils.isEmpty(message)) {
            Matcher matcher = ERROR_POSITION_PATTERN.matcher(message);
            if (matcher.find()) {
                DBPErrorAssistant.ErrorPosition pos = new DBPErrorAssistant.ErrorPosition();
                pos.position = Integer.parseInt(matcher.group(1)) - 1;
                return new ErrorPosition[] {pos};
            }
        }
        return null;
    }

    @NotNull
    @Override
    protected JDBCFactory createJdbcFactory() {
        return new PostgreJdbcFactory();
    }

    @Nullable
    @Override
    public DBCQueryTransformer createQueryTransformer(@NotNull DBCQueryTransformType type) {
        if (type == DBCQueryTransformType.RESULT_SET_LIMIT) {
            return new QueryTransformerLimit(false);
        }
        return null;
    }

}
