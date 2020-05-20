package org.apache.calcite.adapter.jdbc;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.adapter.jdbc.util.CacheReplacePolicy;
import org.apache.calcite.adapter.jdbc.util.GlobalInfo;
import org.apache.calcite.adapter.jdbc.util.SparkHandler;
import org.apache.calcite.plan.*;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterImpl;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.util.SqlString;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.spark.sql.*;
import org.spark_project.jetty.util.security.Credential;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class JdbcToJdbcConverter extends ConverterImpl implements JdbcRel {
    /**
     * Creates a ConverterImpl.
     *
     * @param cluster planner's cluster
     * @param traits  the output traits of this converter
     * @param child   child rel (provides input traits)
     */
    protected JdbcToJdbcConverter(RelOptCluster cluster, RelTraitSet traits, RelNode child) {
        super(cluster, ConventionTraitDef.INSTANCE, traits, child);
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        // cost x 3: ingest -> transfer -> ingest
        return super.computeSelfCost(planner, mq).multiplyBy(3.);
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new JdbcToJdbcConverter(
                getCluster(), traitSet, sole(inputs));
    }

    @Override
    protected RelDataType deriveRowType() {
        return super.deriveRowType();
    }

    @Override
    public JdbcImplementor.Result implement(JdbcImplementor implementor) {
        return implementor.implement(this);
    }

    /**
     * <p>1. 迁移child对应的数据到目标的convention中</p>
     * <p>2. 注册迁移表到meta中，并返回该表</p>
     *
     * <code><pre>
     *     if(input instanceof JdbcTableScan){
     *         // 直接迁移表
     *     }else{
     *         // 生成查询语句
     *         source input为TableScan，则直接返回
     *         source input为JdbcToJdbcConverter，则递归{@link #doMigration()}
     *         // 迁移查询结果
     *     }
     * </pre></code>
     */
    public JdbcTable doMigration() {
        // todo 通过签名的匹配方式有待改进
        RelNode input = getInput();
        JdbcConvention inputTrait = (JdbcConvention) getInputTraits().getTrait(ConventionTraitDef.INSTANCE);
        JdbcConvention outputTrait = (JdbcConvention) getTraitSet().getTrait(ConventionTraitDef.INSTANCE);
        assert inputTrait != null;
        assert outputTrait != null;
        BasicDataSource inputDataSource = (BasicDataSource) inputTrait.dataSource;
        BasicDataSource outputDataSource = (BasicDataSource) outputTrait.dataSource;
        String outputTableName;
        if (input instanceof JdbcTableScan) {
            JdbcTable jdbcTable = ((JdbcTableScan) input).jdbcTable;
            outputTableName = generateTableName(inputTrait, jdbcTable.jdbcTableName);
            if (outputTrait.jdbcSchema.getTableNames().contains(outputTableName) && GlobalInfo.getInstance().getConfigThreadLocal().get().cacheEnabled()) {
                log.info("migrate {} not truly run because it already exist", outputTableName);
                return (JdbcTable) outputTrait.jdbcSchema.getTable(outputTableName);
            }
            migrateBySpark(inputDataSource, outputDataSource, jdbcTable.jdbcTableName, outputTableName, false);
        } else {
            SqlString sqlString = generateSql(inputTrait.dialect);
            outputTableName = generateTableName(inputTrait, sqlString.getSql());
            if (outputTrait.jdbcSchema.getTableNames().contains(outputTableName) && GlobalInfo.getInstance().getConfigThreadLocal().get().cacheEnabled()) {
                log.info("migrate {} not truly run because it already exist", outputTableName);
                return (JdbcTable) outputTrait.jdbcSchema.getTable(outputTableName);
            }
            migrateBySpark(inputDataSource, outputDataSource, sqlString.getSql(), outputTableName, true);
        }
        // 进行缓存替换逻辑
        CacheReplacePolicy.doCacheReplace(outputTableName);
        outputTrait.jdbcSchema.getTableNames(true);
        return (JdbcTable) outputTrait.jdbcSchema.getTable(outputTableName);
    }

    private String generateTableName(Convention in, String query) {
        String tableName = "mig_"
                + in.getName().replace('.', '_').toLowerCase(Locale.ROOT)
                + "_" + Credential.MD5.digest(query).substring(4, 8);
        log.info("migrate table name: {} \n query = {}", tableName, query);
        return tableName;
    }

    private SqlString generateSql(SqlDialect dialect) {
        final JdbcImplementor jdbcImplementor =
                new JdbcImplementor(dialect,
                        (JavaTypeFactory) getCluster().getTypeFactory());
        final JdbcImplementor.Result result =
                jdbcImplementor.visitChild(0, getInput());
        return result.asStatement().toSqlString(dialect);
    }

    private void migrateBySpark(BasicDataSource inputDataSource, BasicDataSource outputDataSource, String inputTableName, String outputTableName, Boolean isQuery) {
        SparkSession spark = SparkHandler.instance().spark;
        DataFrameReader option = spark.read()
                .format("jdbc")
                .option("url", inputDataSource.getUrl())
                .option("user", inputDataSource.getUsername())
                .option("password", inputDataSource.getPassword());
        if (isQuery) {
            option.option("query", inputTableName);
        } else {
            option.option("dbtable", inputTableName);
        }
        Dataset<Row> jdbcDF = option.load();
        Map<String, String> options = new HashMap<>();
        if (outputDataSource.getUrl().contains("clickhouse")) {
            options.put("createTableOptions", "ENGINE=Memory");
        }
        jdbcDF.write()
                .mode(SaveMode.Overwrite)
                .format("jdbc")
                .option("url", outputDataSource.getUrl())
                .option("dbtable", outputTableName)
                .option("user", outputDataSource.getUsername())
                .option("password", outputDataSource.getPassword())
                .options(options)
                .save();
    }

}
