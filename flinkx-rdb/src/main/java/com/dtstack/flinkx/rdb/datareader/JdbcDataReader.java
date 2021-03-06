/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.rdb.datareader;

import com.dtstack.flinkx.config.DataTransferConfig;
import com.dtstack.flinkx.config.ReaderConfig;
import com.dtstack.flinkx.rdb.DatabaseInterface;
import com.dtstack.flinkx.rdb.inputformat.JdbcInputFormatBuilder;
import com.dtstack.flinkx.inputformat.RichInputFormat;
import com.dtstack.flinkx.rdb.type.TypeConverterInterface;
import com.dtstack.flinkx.rdb.util.DBUtil;
import com.dtstack.flinkx.reader.DataReader;
import com.dtstack.flinkx.reader.MetaColumn;
import com.dtstack.flinkx.util.ClassUtil;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.Row;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The Reader plugin for any database that can be connected via JDBC.
 *
 * Company: www.dtstack.com
 * @author huyifan.zju@163.com
 */
public class JdbcDataReader extends DataReader {

    protected DatabaseInterface databaseInterface;

    protected TypeConverterInterface typeConverter;

    protected String dbUrl;

    protected String username;

    protected String password;

    protected List<MetaColumn> metaColumns;

    protected String[] columnTypes;

    protected String table;

    protected Connection connection;

    protected String where;

    protected String splitKey;

    protected String increColumn;

    protected Long startLocation;

    protected int fetchSize;

    protected int queryTimeOut;

    public void setDatabaseInterface(DatabaseInterface databaseInterface) {
        this.databaseInterface = databaseInterface;
    }

    public void setTypeConverterInterface(TypeConverterInterface typeConverter) {
        this.typeConverter = typeConverter;
    }

    public JdbcDataReader(DataTransferConfig config, StreamExecutionEnvironment env) {
        super(config, env);

        ReaderConfig readerConfig = config.getJob().getContent().get(0).getReader();
        dbUrl = readerConfig.getParameter().getConnection().get(0).getJdbcUrl().get(0);
        dbUrl = DBUtil.formatJdbcUrl(readerConfig.getName(),dbUrl);
        username = readerConfig.getParameter().getStringVal(JdbcConfigKeys.KEY_USER_NAME);
        password = readerConfig.getParameter().getStringVal(JdbcConfigKeys.KEY_PASSWORD);
        table = readerConfig.getParameter().getConnection().get(0).getTable().get(0);
        where = readerConfig.getParameter().getStringVal(JdbcConfigKeys.KEY_WHERE);
        metaColumns = MetaColumn.getMetaColumns(readerConfig.getParameter().getColumn());
        fetchSize = readerConfig.getParameter().getIntVal(JdbcConfigKeys.KEY_FETCH_SIZE,0);
        queryTimeOut = readerConfig.getParameter().getIntVal(JdbcConfigKeys.KEY_QUERY_TIME_OUT,0);
        splitKey = readerConfig.getParameter().getStringVal(JdbcConfigKeys.KEY_SPLIK_KEY);
        increColumn = readerConfig.getParameter().getStringVal(JdbcConfigKeys.KEY_INCRE_COLUMN);

        String startLocationStr = readerConfig.getParameter().getStringVal(JdbcConfigKeys.KEY_START_LOCATION,null);
        if(startLocationStr != null){
            startLocation = Long.parseLong(startLocationStr);
        }
    }

    @Override
    public DataStream<Row> readData() {
        // Read from JDBC
        JdbcInputFormatBuilder builder = new JdbcInputFormatBuilder();
        builder.setDrivername(databaseInterface.getDriverClass());
        builder.setDBUrl(dbUrl);
        builder.setUsername(username);
        builder.setPassword(password);
        builder.setBytes(bytes);
        builder.setMonitorUrls(monitorUrls);
        builder.setTable(table);
        builder.setDatabaseInterface(databaseInterface);
        builder.setTypeConverter(typeConverter);
        builder.setMetaColumn(metaColumns);
        builder.setFetchSize(fetchSize == 0 ? databaseInterface.getFetchSize() : fetchSize);
        builder.setQueryTimeOut(queryTimeOut == 0 ? databaseInterface.getQueryTimeout() : queryTimeOut);
        builder.setIncreCol(increColumn);
        builder.setStartLocation(startLocation);

        boolean isSplitByKey = false;
        if(numPartitions > 1 && splitKey != null && splitKey.trim().length() != 0) {
            builder.setParameterValues(DBUtil.getParameterValues(numPartitions));
            isSplitByKey = true;
        }

        if(increColumn != null){
            String increColType = getIncreColType();
            where = DBUtil.buildWhereSql(databaseInterface,increColType,where,increColumn,startLocation);
            builder.setIncreColType(increColType);
        }

        String query = DBUtil.getQuerySql(databaseInterface,table,metaColumns,splitKey,where,isSplitByKey);
        builder.setQuery(query);

        RichInputFormat format =  builder.finish();
        return createInput(format, (databaseInterface.getDatabaseType() + "reader").toLowerCase());
    }

    private String getIncreColType(){
        boolean containsIncreCol = false;
        for (MetaColumn metaColumn : metaColumns) {
            if(metaColumn.getName().equals(increColumn)){
                containsIncreCol = true;
                break;
            }
        }

        if (!containsIncreCol){
            MetaColumn metaIncreCol = new MetaColumn();
            metaIncreCol.setName(increColumn);
            metaColumns.add(metaIncreCol);
        }

        ClassUtil.forName(databaseInterface.getDriverClass(), getClass().getClassLoader());
        DBUtil.analyzeTable(dbUrl,username,password,databaseInterface,table, metaColumns);

        String type = null;
        for (MetaColumn metaColumn : metaColumns) {
            if(metaColumn.getName().equals(increColumn)){
                type = metaColumn.getType();
                break;
            }
        }

        if(type == null){
            throw new IllegalArgumentException("There is no " + increColumn +" field in the " + table +" table");
        }

        return type;
    }
}
