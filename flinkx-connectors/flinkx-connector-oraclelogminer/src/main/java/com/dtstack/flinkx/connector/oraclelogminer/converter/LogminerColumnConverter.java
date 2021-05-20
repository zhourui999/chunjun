/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.connector.oraclelogminer.converter;

import org.apache.flink.table.data.RowData;
import org.apache.flink.types.RowKind;

import com.dtstack.flinkx.connector.jdbc.util.JdbcUtil;
import com.dtstack.flinkx.connector.oraclelogminer.entity.LogminerEventRow;
import com.dtstack.flinkx.constants.ConstantValue;
import com.dtstack.flinkx.converter.AbstractCDCRowConverter;
import com.dtstack.flinkx.converter.IDeserializationConverter;
import com.dtstack.flinkx.element.AbstractBaseColumn;
import com.dtstack.flinkx.element.ColumnRowData;
import com.dtstack.flinkx.element.column.BigDecimalColumn;
import com.dtstack.flinkx.element.column.BytesColumn;
import com.dtstack.flinkx.element.column.MapColumn;
import com.dtstack.flinkx.element.column.StringColumn;
import com.dtstack.flinkx.element.column.TimestampColumn;
import com.dtstack.flinkx.util.DateUtil;
import com.dtstack.flinkx.util.GsonUtil;
import com.google.common.collect.Maps;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Date: 2021/04/29
 * Company: www.dtstack.com
 *
 * @author tudou
 */
public class LogminerColumnConverter extends AbstractCDCRowConverter<LogminerEventRow, String> {

    protected final Logger LOG = LoggerFactory.getLogger(getClass());

    protected static final String SCN = "scn";
    private Connection connection;
    //存储表字段
    protected final Map<String, Pair<List<String>, List<String>>> tableMetaDataCacheMap = new ConcurrentHashMap<>(32);

    public LogminerColumnConverter(boolean pavingData, boolean splitUpdate) {
        super.pavingData = pavingData;
        super.splitUpdate = splitUpdate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public LinkedList<RowData> toInternal(LogminerEventRow logminerEventRow) {
        LinkedList<RowData> result = new LinkedList<>();

        String eventType = logminerEventRow.getType();
        String schema = logminerEventRow.getSchema();
        String table = logminerEventRow.getTable();
        String key = schema + ConstantValue.POINT_SYMBOL + table;
        IDeserializationConverter[] converters = super.cdcConverterCacheMap.get(key);
        Pair<List<String>, List<String>> metadata = tableMetaDataCacheMap.get(key);

        List<LogminerEventRow.Column> beforeColumnList = logminerEventRow.getBeforeColumnList();

        //  如果缓存为空 或者 长度变了 或者名字变了  重新更新缓存
        if (Objects.isNull(converters) || Objects.isNull(metadata)
                || beforeColumnList.size() != converters.length
                || !beforeColumnList.stream().map(LogminerEventRow.Column::getName).collect(Collectors.toCollection(HashSet::new)).containsAll(metadata.getLeft())) {
            try {
                Pair<List<String>, List<String>> tableMetaData = JdbcUtil.getTableMetaData(schema, table, connection);
                converters = tableMetaData.getRight().stream().map(x -> wrapIntoNullableInternalConverter(createInternalConverter(x))).toArray(IDeserializationConverter[]::new);

                metadata = tableMetaData;
                super.cdcConverterCacheMap.put(key, converters);
                tableMetaDataCacheMap.put(key, tableMetaData);

            } catch (SQLException e) {
                throw new RuntimeException("get table " + table + " metadata failed", e);
            }
        }


        int size;
        if (pavingData) {
            //6: scn, type, schema, table, ts, opTime
            size = 6 + logminerEventRow.getBeforeColumnList().size() + logminerEventRow.getAfterColumnList().size();
        } else {
            //7: scn, type, schema, table, ts, opTime, before, after
            size = 8;
        }

        ColumnRowData columnRowData = new ColumnRowData(size);

        columnRowData.addField(new BigDecimalColumn(logminerEventRow.getScn()));
        columnRowData.addHeader(SCN);
        columnRowData.addField(new StringColumn(schema));
        columnRowData.addHeader(SCHEMA);
        columnRowData.addField(new StringColumn(table));
        columnRowData.addHeader(TABLE);
        columnRowData.addField(new BigDecimalColumn(logminerEventRow.getTs()));
        columnRowData.addHeader(TS);
        columnRowData.addField(new TimestampColumn(logminerEventRow.getOpTime()));
        columnRowData.addHeader(OP_TIME);

        List<LogminerEventRow.Column> beforeList = logminerEventRow.getBeforeColumnList();
        List<LogminerEventRow.Column> afterList = logminerEventRow.getAfterColumnList();

        List<AbstractBaseColumn> beforeFieldList = new ArrayList<>(beforeList.size());
        List<String> beforeHeaderList = new ArrayList<>(beforeList.size());
        List<AbstractBaseColumn> afterFieldList = new ArrayList<>(afterList.size());
        List<String> afterHeaderList = new ArrayList<>(afterList.size());

        if (pavingData) {
            parseColumnList(converters, beforeList, beforeFieldList, metadata.getLeft(), beforeHeaderList, BEFORE_);
            parseColumnList(converters, afterList, afterFieldList, metadata.getLeft(), afterHeaderList, AFTER_);
        } else {
            beforeFieldList.add(new MapColumn(processColumnList(beforeList)));
            beforeHeaderList.add(BEFORE);
            afterFieldList.add(new MapColumn(processColumnList(afterList)));
            afterHeaderList.add(AFTER);
        }

        //update类型且要拆分
        if (splitUpdate && "UPDATE".equalsIgnoreCase(eventType)) {
            ColumnRowData copy = columnRowData.copy();
            copy.setRowKind(RowKind.UPDATE_BEFORE);
            copy.addField(new StringColumn(RowKind.UPDATE_BEFORE.name()));
            copy.addHeader(TYPE);
            copy.addAllField(beforeFieldList);
            copy.addAllHeader(beforeHeaderList);
            result.add(copy);

            columnRowData.setRowKind(RowKind.UPDATE_AFTER);
            columnRowData.addField(new StringColumn(RowKind.UPDATE_AFTER.name()));
            columnRowData.addHeader(TYPE);
        } else {
            columnRowData.setRowKind(getRowKindByType(eventType));
            columnRowData.addField(new StringColumn(eventType));
            columnRowData.addHeader(TYPE);
            columnRowData.addAllField(beforeFieldList);
            columnRowData.addAllHeader(beforeHeaderList);
        }
        columnRowData.addAllField(afterFieldList);
        columnRowData.addAllHeader(afterHeaderList);

        result.add(columnRowData);

        return result;
    }

    /**
     * 解析CanalEntry.Column
     *
     * @param converters
     * @param entryColumnList
     * @param columnList
     * @param headerList
     * @param after
     */
    private void parseColumnList(
            IDeserializationConverter<String, AbstractBaseColumn>[] converters,
            List<LogminerEventRow.Column> entryColumnList,
            List<AbstractBaseColumn> columnList,
            List<String> metaColumnNameList,
            List<String> headerList,
            String after) {
        for (int i = 0; i < entryColumnList.size(); i++) {
            LogminerEventRow.Column entryColumn = entryColumnList.get(i);

            //解析的字段顺序和metadata顺序不一致 所以先从metadata里找到字段的index  再找到对应的converters
            int index = metaColumnNameList.indexOf(entryColumn.getName());
            //字段不一致
            if (index == -1) {
                throw new RuntimeException("The fields in the log are inconsistent with those in the current meta information，The fields in the log is "
                        + GsonUtil.GSON.toJson(entryColumnList) + " ,The fields in the metadata is" + GsonUtil.GSON.toJson(metaColumnNameList));
            }

            AbstractBaseColumn column = converters[index].deserialize(entryColumn.getData());
            columnList.add(column);
            headerList.add(after + entryColumn.getName());
        }
    }

    @Override
    protected IDeserializationConverter createInternalConverter(String type) {
        String substring = type;
        int index = type.indexOf(ConstantValue.LEFT_PARENTHESIS_SYMBOL);
        if (index > 0) {
            substring = type.substring(0, index);
        }

        switch (substring.toUpperCase(Locale.ENGLISH)) {
            case "NUMBER":
            case "SMALLINT":
            case "MEDIUMINT":
            case "INT":
            case "INTEGER":
            case "INT24":
            case "FLOAT":
            case "DOUBLE":
            case "REAL":
            case "BIGINT":
            case "DECIMAL":
            case "NUMERIC":
            case "BINARY_FLOAT":
            case "BINARY_DOUBLE":
                //1.223E-002 科学技术法 但是会有精度问题
                return (IDeserializationConverter<String, AbstractBaseColumn>) BigDecimalColumn::new;
            case "CHAR":
            case "NCHAR":
            case "NVARCHAR2":
            case "ROWID":
            case "VARCHAR2":
            case "VARCHAR":
                // oracle里的long 可以插入字符串
            case "LONG":
                return (IDeserializationConverter<String, AbstractBaseColumn>) StringColumn::new;
            case "RAW":
            case "BLOB":
            case "LONG RAW":
                return (IDeserializationConverter<String, AbstractBaseColumn>) val -> {
                    //HEXTORAW('1234')
                    if (val.startsWith("HEXTORAW('") && val.endsWith("')")) {
                        try {
                            return new StringColumn(new String(Hex.decodeHex(val.substring(10, val.length() - 2).toCharArray()), "UTF-8"));
                        } catch (Exception e) {
                            throw new RuntimeException("RAWConverter: parse value [" + val + " ] failed ", e);
                        }
                    } else {
                        return new StringColumn(val);
                    }
                };
                //暂不支持
//            case "INTERVAL YEAR":
//                return (IDeserializationConverter<String, AbstractBaseColumn>) val -> {
//                    // TO_YMINTERVAL('+00-03')
//                    if (val.startsWith("TO_YMINTERVAL('") && val.endsWith("')") ) {
//                        return new StringColumn(val.substring(15, val.length() - 2));
//                    } else {
//                        return new StringColumn(val);
//                    }
//                };
//            case "INTERVAL DAY":
//                return (IDeserializationConverter<String, AbstractBaseColumn>) val -> {
//                    //HEXTORAW('3132')
//                    if (val.startsWith("TO_DSINTERVAL('") && val.endsWith("')")) {
//                        return new StringColumn(val.substring(10, val.length() - 2));
//                    }else{
//                        return new StringColumn(val);
//                    }
//                };
            case "DATE":
            case "TIMESTAMP":
                return (IDeserializationConverter<String, AbstractBaseColumn>) val -> {
                    //如果包含时区会携带时区信息 无法转为timestamp '2021-05-17 15:08:27.000000 上午 +08:00'
                    if (type.contains("TIME ZONE")) {
                        throw new UnsupportedOperationException("Unsupported type:" + type);
                    } else {
                        return new TimestampColumn(DateUtil.getTimestampFromStr(val));
                    }
                };

            case "CLOB":
            case "NCLOB":
                return (IDeserializationConverter<String, AbstractBaseColumn>) val -> new BytesColumn(val.getBytes(StandardCharsets.UTF_8));
            case "INTERVAL YEAR":
            case "INTERVAL DAY":
            case "BFILE":
            default:
                throw new UnsupportedOperationException("Unsupported type:" + type);
        }
    }

    /**
     * 解析CanalEntry中的Column，获取字段名及值
     *
     * @return 字段名和值的map集合
     */
    private Map<String, Object> processColumnList(List<LogminerEventRow.Column> columnList) {
        Map<String, Object> map = Maps.newLinkedHashMapWithExpectedSize(columnList.size());
        for (LogminerEventRow.Column column : columnList) {
            map.put(column.getName(), column.getData());
        }
        return map;
    }

    public void setConnection(Connection connection) {
        this.connection = connection;
    }
}
