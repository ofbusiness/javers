package org.javers.repository.sql.finders;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.javers.common.string.ToStringBuilder;
import org.javers.repository.api.QueryParams;
import org.javers.repository.sql.schema.SchemaNameAware;
import org.javers.repository.sql.schema.TableNameProvider;
import org.polyjdbc.core.query.SelectQuery;
import org.polyjdbc.core.type.Timestamp;

import static org.javers.core.json.typeadapter.util.UtilTypeCoreAdapters.toUtilDate;
import static org.javers.repository.sql.schema.FixedSchemaFactory.COMMIT_AUTHOR;
import static org.javers.repository.sql.schema.FixedSchemaFactory.COMMIT_COMMIT_DATE;
import static org.javers.repository.sql.schema.FixedSchemaFactory.COMMIT_COMMIT_ID;
import static org.javers.repository.sql.schema.FixedSchemaFactory.COMMIT_PK;
import static org.javers.repository.sql.schema.FixedSchemaFactory.COMMIT_PROPERTY_COMMIT_FK;
import static org.javers.repository.sql.schema.FixedSchemaFactory.COMMIT_PROPERTY_NAME;
import static org.javers.repository.sql.schema.FixedSchemaFactory.COMMIT_PROPERTY_VALUE;
import static org.javers.repository.sql.schema.FixedSchemaFactory.GLOBAL_ID_FRAGMENT;
import static org.javers.repository.sql.schema.FixedSchemaFactory.GLOBAL_ID_LOCAL_ID;
import static org.javers.repository.sql.schema.FixedSchemaFactory.GLOBAL_ID_OWNER_ID_FK;
import static org.javers.repository.sql.schema.FixedSchemaFactory.GLOBAL_ID_PK;
import static org.javers.repository.sql.schema.FixedSchemaFactory.GLOBAL_ID_TYPE_NAME;
import static org.javers.repository.sql.schema.FixedSchemaFactory.SNAPSHOT_CHANGED;
import static org.javers.repository.sql.schema.FixedSchemaFactory.SNAPSHOT_COMMIT_FK;
import static org.javers.repository.sql.schema.FixedSchemaFactory.SNAPSHOT_GLOBAL_ID_FK;
import static org.javers.repository.sql.schema.FixedSchemaFactory.SNAPSHOT_MANAGED_TYPE;
import static org.javers.repository.sql.schema.FixedSchemaFactory.SNAPSHOT_STATE;
import static org.javers.repository.sql.schema.FixedSchemaFactory.SNAPSHOT_TYPE;
import static org.javers.repository.sql.schema.FixedSchemaFactory.SNAPSHOT_VERSION;

abstract class SnapshotFilter extends SchemaNameAware {

    SnapshotFilter(TableNameProvider tableNameProvider) {
        super(tableNameProvider);
    }

    private static final Integer MAX_JOIN_QUERY_THRESHOLD = 50;

    private final Map<QueryMode, QueryProcessor> queryProcessors = ImmutableMap.of(
        QueryMode.JOIN, new JoinModeQueryProcessor(),
        QueryMode.SUB_QUERY, new SubQueryModeQueryProcessor());

    private static final String BASE_FIELDS =
        SNAPSHOT_STATE + ", " +
            SNAPSHOT_TYPE + ", " +
            SNAPSHOT_VERSION + ", " +
            SNAPSHOT_CHANGED + ", " +
            SNAPSHOT_MANAGED_TYPE + ", " +
            COMMIT_PK + ", " +
            COMMIT_AUTHOR + ", " +
            COMMIT_COMMIT_DATE + ", " +
            COMMIT_COMMIT_ID;

    static final String BASE_AND_GLOBAL_ID_FIELDS =
        BASE_FIELDS + ", " +
            "g." + GLOBAL_ID_LOCAL_ID + ", " +
            "g." + GLOBAL_ID_FRAGMENT + ", " +
            "g." + GLOBAL_ID_OWNER_ID_FK + ", " +
            "o." + GLOBAL_ID_LOCAL_ID + " owner_" + GLOBAL_ID_LOCAL_ID + ", " +
            "o." + GLOBAL_ID_FRAGMENT + " owner_" + GLOBAL_ID_FRAGMENT + ", " +
            "o." + GLOBAL_ID_TYPE_NAME + " owner_" + GLOBAL_ID_TYPE_NAME;

    protected String getFromCommitWithSnapshot() {
        return getSnapshotTableNameWithSchema() +
            " INNER JOIN " + getCommitTableNameWithSchema() + " ON " + COMMIT_PK + " = " + SNAPSHOT_COMMIT_FK +
            " INNER JOIN " + getGlobalIdTableNameWithSchema() + " g ON g." + GLOBAL_ID_PK + " = " + SNAPSHOT_GLOBAL_ID_FK +
            " LEFT OUTER JOIN " + getGlobalIdTableNameWithSchema() + " o ON o." + GLOBAL_ID_PK + " = g." + GLOBAL_ID_OWNER_ID_FK;
    }

    String select() {
        return BASE_AND_GLOBAL_ID_FIELDS;
    }

    void addFrom(SelectQuery query, Optional<QueryParams> queryParams) {
        String from = getFromCommitWithSnapshot();
        QueryProcessor queryProcessor = getQueryProcessor(queryParams);
        from = from + queryProcessor.processFrom(query, queryParams);
        query.from(from);
    }

    abstract void addWhere(SelectQuery query);

    void applyQueryParams(SelectQuery query, QueryParams queryParams) {
        if (queryParams.changedProperty().isPresent()){
            query.append(" AND " + SNAPSHOT_CHANGED + " like :changedProperty ")
                  .withArgument("changedProperty", "%\"" + queryParams.changedProperty().get() +"\"%");
        }
        if (queryParams.from().isPresent()) {
            query.append(" AND " + COMMIT_COMMIT_DATE + " >= :commitFromDate")
                 .withArgument("commitFromDate", new Timestamp(toUtilDate( queryParams.from().get())));
        }
        if (queryParams.to().isPresent()) {
            query.append(" AND " + COMMIT_COMMIT_DATE + " <= :commitToDate")
                 .withArgument("commitToDate", new Timestamp(toUtilDate(queryParams.to().get())));
        }
        if (queryParams.toCommitId().isPresent()) {
            query.append(" AND " + COMMIT_COMMIT_ID + " <= " + queryParams.toCommitId().get().valueAsNumber());
        }
        if (queryParams.commitIds().size() > 0) {
            query.append(" AND " + COMMIT_COMMIT_ID + " IN (" + ToStringBuilder.join(
                    queryParams.commitIds().stream().map(c -> c.valueAsNumber()).collect(Collectors.toList())) + ")");
        }
        if (queryParams.version().isPresent()) {
            query.append(" AND " + SNAPSHOT_VERSION + " = :version")
                 .withArgument("version", queryParams.version().get());
        }
        if (queryParams.author().isPresent()) {
            query.append(" AND " + COMMIT_AUTHOR + " = :author")
                 .withArgument("author",  queryParams.author().get());
        }
        if (queryParams.commitProperties().size() > 0) {
            addCommitPropertyConditions(query, queryParams);
        }
        if (queryParams.snapshotType().isPresent()){
            query.append(" AND " + SNAPSHOT_TYPE + " = :snapshotType")
                 .withArgument("snapshotType", queryParams.snapshotType().get().name());
        }
        query.limit(queryParams.limit(), queryParams.skip());
    }

    private void addCommitPropertyConditions(SelectQuery query, QueryParams queryParams) {
        QueryProcessor queryProcessor = getQueryProcessor(Optional.of(queryParams));
        queryProcessor.processCommitPropertiesConditions(query, queryParams);
    }

    /**
     * Returns the relevant {@link QueryProcessor} for query params.
     *
     *  - {@link QueryMode#JOIN} - used when filtering on commit properties and number of filters is
     * less than {@link #MAX_JOIN_QUERY_THRESHOLD} which is currently set to 50
     *
     *  - {@link QueryMode#SUB_QUERY} - default mode for queries. We switch to this mode when number
     * of filters on commit properties exceed {@link #MAX_JOIN_QUERY_THRESHOLD}. This is due to the
     * limitation that most SQL database engines impose the maximum number of inner joins. Currently
     * this standard is 61 for more popular databases.
     */
    private QueryProcessor getQueryProcessor(Optional<QueryParams> optionalQueryParams) {
        if (optionalQueryParams.isPresent()) {
            QueryParams queryParams = optionalQueryParams.get();
            if (queryParams.commitProperties().size() <= MAX_JOIN_QUERY_THRESHOLD) {
                return queryProcessors.get(QueryMode.JOIN);
            }
        }
        return queryProcessors.get(QueryMode.SUB_QUERY);
    }

    private enum QueryMode {
        /**
         * This mode should be use when queries on commit properties which have many-to-one mapping
         * with commit, snapshot. With this mode, inner self join for commit properties with itself
         * is done multiple times and each filter is applied on a single inner join.
         *
         * see {@link JoinModeQueryProcessor}
         */
        JOIN,


        /**
         * This mode is the legacy implementation of querying on commit properties wherein all
         * filters on commit properties are represented as nested join subqueries.
         *
         * see {@link SubQueryModeQueryProcessor}
         */
        SUB_QUERY;
    }

    private interface QueryProcessor {
        String processFrom(SelectQuery query, Optional<QueryParams> optional);

        void processCommitPropertiesConditions(SelectQuery query, QueryParams queryParams);
    }

    private class SubQueryModeQueryProcessor implements QueryProcessor {
        @Override public String processFrom(SelectQuery query, Optional<QueryParams> queryParams) {
            // no-op
            return "";
        }

        @Override
        public void processCommitPropertiesConditions(SelectQuery query, QueryParams queryParams) {
            Map<String, String> commitProperties = queryParams.commitProperties();
            for (Map.Entry<String, String> commitProperty : commitProperties.entrySet()) {
                addCommitPropertyCondition(query, commitProperty.getKey(), commitProperty.getValue());
            }
        }

        private void addCommitPropertyCondition(SelectQuery query, String propertyName, String propertyValue) {
            query.append(" AND EXISTS (" +
                "SELECT * FROM " + getCommitPropertyTableNameWithSchema() +
                " WHERE " + COMMIT_PROPERTY_COMMIT_FK + " = " + COMMIT_PK +
                " AND " + COMMIT_PROPERTY_NAME + " = :propertyName_" + propertyName +
                " AND " + COMMIT_PROPERTY_VALUE + " = :propertyValue_" + propertyName +
                ")")
                .withArgument("propertyName_" + propertyName, propertyName)
                .withArgument("propertyValue_" + propertyName, propertyValue);
        }
    }

    private class JoinModeQueryProcessor implements QueryProcessor {
        @Override public String processFrom(SelectQuery query, Optional<QueryParams> queryParams) {
            if (!queryParams.isPresent()) {
               return "";
            }
            Map<String, String> commitProperties = queryParams.get().commitProperties();
            StringBuilder sb = new StringBuilder();
            for (int aliasIndex = 0 ; aliasIndex < commitProperties.size(); aliasIndex++) {
                String alias = getAliasName(aliasIndex);
                sb.append(
                    " INNER JOIN " + getCommitPropertyTableNameWithSchema() + " AS " + alias + " ON " + COMMIT_PK + " = " + alias + "." + COMMIT_PROPERTY_COMMIT_FK
                );
            }
            return sb.toString();
        }

        @Override public void processCommitPropertiesConditions(SelectQuery query, QueryParams queryParams) {
            Map<String, String> commitProperties = queryParams.commitProperties();
            int aliasIndex = 0;
            for (Map.Entry<String, String> commitProperty : commitProperties.entrySet()) {
                addCommitPropertyCondition(query, getAliasName(aliasIndex), commitProperty.getKey(), commitProperty.getValue());
                aliasIndex++;
            }
        }

        private void addCommitPropertyCondition(SelectQuery query, String aliasName, String propertyName, String propertyValue) {
            query.append(
                " AND " + aliasName + "." + COMMIT_PROPERTY_NAME + " = :propertyName_" + propertyName + "_" + aliasName +
                " AND "  + aliasName+"."+ COMMIT_PROPERTY_VALUE + " = :propertyValue_" + propertyName + "_" + aliasName)
                .withArgument("propertyName_" + propertyName + "_" + aliasName, propertyName)
                .withArgument("propertyValue_" + propertyName + "_" + aliasName, propertyValue);
        }

        private String getAliasName(int index) {
            return String.format("cp_%d", index);
        }
    }
}
