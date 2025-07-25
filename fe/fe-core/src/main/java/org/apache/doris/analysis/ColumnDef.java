// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
// This file is copied from
// https://github.com/apache/impala/blob/branch-2.9.0/fe/src/main/java/org/apache/impala/ColumnDef.java
// and modified by Doris

package org.apache.doris.analysis;

import org.apache.doris.catalog.AggregateType;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.GeneratedColumnInfo;
import org.apache.doris.catalog.KeysType;
import org.apache.doris.catalog.PrimitiveType;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.catalog.Type;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.FeNameFormat;
import org.apache.doris.common.util.SqlUtils;
import org.apache.doris.common.util.TimeUtils;
import org.apache.doris.qe.SessionVariable;

import com.google.common.base.Preconditions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Column definition which is generated by SQL syntax parser
// Syntax:
//      name type [key] [agg_type] [NULL | NOT NULL] [AUTO_INCREMENT] [DEFAULT default_value] [comment]
// Example:
//      id bigint key NOT NULL DEFAULT "-1" "user id"
//      pv bigint sum NULL DEFAULT "-1" "page visit"
public class ColumnDef {
    private static final Logger LOG = LogManager.getLogger(ColumnDef.class);

    /*
     * User can set default value for a column
     * eg:
     *     k1 INT NOT NULL DEFAULT "10"
     *     k1 INT NULL
     *     k1 INT NULL DEFAULT NULL
     *
     * ColumnnDef will be transformed to Column in Analysis phase, and in Column, default value is a String.
     * No matter does the user set the default value as NULL explicitly, or not set default value, the default value
     * in Column will be "null", so that Doris can not distinguish between "not set" and "set as null".
     *
     * But this is OK because Column has another attribute "isAllowNull".
     * If the column is not allowed to be null, and user does not set the default value,
     * even if default value saved in Column is null, the "null" value can not be loaded into this column,
     * so data correctness can be guaranteed.
     */
    public static class DefaultValue {
        public boolean isSet;
        public String value;
        // used for column which defaultValue is an expression.
        public DefaultValueExprDef defaultValueExprDef;

        public DefaultValue(boolean isSet, Object value) {
            this.isSet = isSet;
            this.value = value == null ? null : value.toString();
            this.defaultValueExprDef = null;
        }

        /**
         * used for column which defaultValue is an expression.
         * @param isSet is Set DefaultValue
         * @param value default value
         * @param exprName default value expression
         */
        public DefaultValue(boolean isSet, String value, String exprName) {
            this.isSet = isSet;
            this.value = value;
            this.defaultValueExprDef = new DefaultValueExprDef(exprName);
        }

        public DefaultValue(boolean isSet, String value, String exprName, Long precision) {
            this.isSet = isSet;
            this.value = value;
            this.defaultValueExprDef = new DefaultValueExprDef(exprName, precision);
        }

        public static String E_NUM = "E";
        public static String PI = "PI";
        public static String CURRENT_DATE = "CURRENT_DATE";
        // default "CURRENT_TIMESTAMP", only for DATETIME type
        public static String CURRENT_TIMESTAMP = "CURRENT_TIMESTAMP";
        public static String NOW = "now";
        public static String HLL_EMPTY = "HLL_EMPTY";
        public static String BITMAP_EMPTY = "BITMAP_EMPTY";
        public static DefaultValue CURRENT_TIMESTAMP_DEFAULT_VALUE = new DefaultValue(true, CURRENT_TIMESTAMP, NOW);
        // no default value
        public static DefaultValue NOT_SET = new DefaultValue(false, null);
        // default null
        public static DefaultValue NULL_DEFAULT_VALUE = new DefaultValue(true, null);
        public static String ZERO = new String(new byte[] {0});
        // default "value", "0" means empty hll
        public static DefaultValue HLL_EMPTY_DEFAULT_VALUE = new DefaultValue(true, ZERO, HLL_EMPTY);
        // default "value", "0" means empty bitmap
        public static DefaultValue BITMAP_EMPTY_DEFAULT_VALUE = new DefaultValue(true, ZERO, BITMAP_EMPTY);
        // default "value", "[]" means empty array
        public static DefaultValue ARRAY_EMPTY_DEFAULT_VALUE = new DefaultValue(true, "[]");

        public static DefaultValue currentTimeStampDefaultValueWithPrecision(Long precision) {
            if (precision > ScalarType.MAX_DATETIMEV2_SCALE || precision < 0) {
                throw new IllegalArgumentException("column's default value current_timestamp"
                        + " precision must be between 0 and 6");
            }
            if (precision == 0) {
                return new DefaultValue(true, CURRENT_TIMESTAMP, NOW);
            }
            String value = CURRENT_TIMESTAMP + "(" + precision + ")";
            String exprName = NOW;
            return new DefaultValue(true, value, exprName, precision);
        }

        public boolean isCurrentTimeStamp() {
            return "CURRENT_TIMESTAMP".equals(value) && defaultValueExprDef != null
                    && NOW.equals(defaultValueExprDef.getExprName());
        }

        public boolean isCurrentTimeStampWithPrecision() {
            return defaultValueExprDef != null && value.startsWith(CURRENT_TIMESTAMP + "(")
                    && NOW.equals(defaultValueExprDef.getExprName());
        }

        public long getCurrentTimeStampPrecision() {
            if (isCurrentTimeStampWithPrecision()) {
                return Long.parseLong(value.substring(CURRENT_TIMESTAMP.length() + 1, value.length() - 1));
            }
            return 0;
        }

        public boolean isNullDefaultValue() {
            return !isSet && value == null && defaultValueExprDef == null;
        }

        public String getValue() {
            if (isCurrentTimeStamp()) {
                return LocalDateTime.now(TimeUtils.getTimeZone().toZoneId()).toString().replace('T', ' ');
            } else if (isCurrentTimeStampWithPrecision()) {
                long precision = getCurrentTimeStampPrecision();
                String format = "yyyy-MM-dd HH:mm:ss";
                if (precision == 0) {
                    return LocalDateTime.now(TimeUtils.getTimeZone().toZoneId()).toString().replace('T', ' ');
                } else if (precision == 1) {
                    format = "yyyy-MM-dd HH:mm:ss.S";
                } else if (precision == 2) {
                    format = "yyyy-MM-dd HH:mm:ss.SS";
                } else if (precision == 3) {
                    format = "yyyy-MM-dd HH:mm:ss.SSS";
                } else if (precision == 4) {
                    format = "yyyy-MM-dd HH:mm:ss.SSSS";
                } else if (precision == 5) {
                    format = "yyyy-MM-dd HH:mm:ss.SSSSS";
                } else if (precision == 6) {
                    format = "yyyy-MM-dd HH:mm:ss.SSSSSS";
                }
                return LocalDateTime.now(TimeUtils.getTimeZone().toZoneId())
                        .format(DateTimeFormatter.ofPattern(format));
            }
            return value;
        }
    }

    // parameter initialized in constructor
    private String name;
    private TypeDef typeDef;
    private AggregateType aggregateType;

    private boolean isKey;
    private boolean isAllowNull;
    private boolean isAutoInc;
    private long autoIncInitValue;
    private KeysType keysType;
    private DefaultValue defaultValue;
    private String comment;
    private boolean visible;
    private int clusterKeyId = -1;
    private Optional<GeneratedColumnInfo> generatedColumnInfo = Optional.empty();
    private Set<String> generatedColumnsThatReferToThis = new HashSet<>();


    public ColumnDef(String name, TypeDef typeDef) {
        this(name, typeDef, false, null, ColumnNullableType.NOT_NULLABLE, DefaultValue.NOT_SET, "");
    }

    public ColumnDef(String name, TypeDef typeDef, boolean isAllowNull) {
        this(name, typeDef, false, null, isAllowNull, DefaultValue.NOT_SET, "");
    }

    public ColumnDef(String name, TypeDef typeDef, ColumnNullableType nullableType) {
        this(name, typeDef, false, null, nullableType, DefaultValue.NOT_SET, "");
    }

    public ColumnDef(String name, TypeDef typeDef, boolean isKey, AggregateType aggregateType,
            ColumnNullableType nullableType, long autoIncInitValue, DefaultValue defaultValue, String comment) {
        this(name, typeDef, isKey, aggregateType, nullableType, autoIncInitValue, defaultValue, comment, true,
                Optional.empty());
    }

    public ColumnDef(String name, TypeDef typeDef, boolean isKey, AggregateType aggregateType,
            ColumnNullableType nullableType, DefaultValue defaultValue, String comment) {
        this(name, typeDef, isKey, aggregateType, nullableType, -1, defaultValue, comment, true,
                Optional.empty());
    }

    public ColumnDef(String name, TypeDef typeDef, boolean isKey, AggregateType aggregateType,
            ColumnNullableType nullableType, long autoIncInitValue, DefaultValue defaultValue, String comment,
            boolean visible, Optional<GeneratedColumnInfo> generatedColumnInfo) {
        this.name = name;
        this.typeDef = typeDef;
        this.isKey = isKey;
        this.aggregateType = aggregateType;
        if (nullableType != ColumnNullableType.UNKNOWN) {
            isAllowNull = nullableType.getNullable(typeDef.getType().getPrimitiveType());
        }
        this.isAutoInc = autoIncInitValue != -1;
        this.autoIncInitValue = autoIncInitValue;
        this.defaultValue = defaultValue;
        this.comment = comment;
        this.visible = visible;
        this.generatedColumnInfo = generatedColumnInfo;
    }

    public ColumnDef(String name, TypeDef typeDef, boolean isKey, AggregateType aggregateType, boolean isAllowNull,
            DefaultValue defaultValue, String comment) {
        this(name, typeDef, isKey, aggregateType, isAllowNull, -1, defaultValue, comment, true);
    }

    public ColumnDef(String name, TypeDef typeDef, boolean isKey, AggregateType aggregateType,
            boolean isAllowNull, long autoIncInitValue, DefaultValue defaultValue, String comment, boolean visible) {
        this.name = name;
        this.typeDef = typeDef;
        this.isKey = isKey;
        this.aggregateType = aggregateType;
        this.isAllowNull = isAllowNull;
        this.isAutoInc = autoIncInitValue != -1;
        this.autoIncInitValue = autoIncInitValue;
        this.defaultValue = defaultValue;
        this.comment = comment;
        this.visible = visible;
    }

    public ColumnDef(String name, TypeDef typeDef, boolean isKey, ColumnNullableType nullableType, String comment,
            Optional<GeneratedColumnInfo> generatedColumnInfo) {
        this(name, typeDef, isKey, null, nullableType, -1, DefaultValue.NOT_SET,
                comment, true, generatedColumnInfo);
    }

    public static ColumnDef newDeleteSignColumnDef() {
        return new ColumnDef(Column.DELETE_SIGN, TypeDef.create(PrimitiveType.TINYINT), false, null,
                ColumnNullableType.NOT_NULLABLE, -1, new ColumnDef.DefaultValue(true, "0"),
                "doris delete flag hidden column", false, Optional.empty());
    }

    public static ColumnDef newDeleteSignColumnDef(AggregateType aggregateType) {
        return new ColumnDef(Column.DELETE_SIGN, TypeDef.create(PrimitiveType.TINYINT), false, aggregateType,
                ColumnNullableType.NOT_NULLABLE, -1, new ColumnDef.DefaultValue(true, "0"),
                "doris delete flag hidden column", false, Optional.empty());
    }

    public static ColumnDef newSequenceColumnDef(Type type) {
        return new ColumnDef(Column.SEQUENCE_COL, new TypeDef(type), false, null, ColumnNullableType.NULLABLE, -1,
                DefaultValue.NULL_DEFAULT_VALUE, "sequence column hidden column", false, Optional.empty());
    }

    public static ColumnDef newSequenceColumnDef(Type type, AggregateType aggregateType) {
        return new ColumnDef(Column.SEQUENCE_COL, new TypeDef(type), false, aggregateType, ColumnNullableType.NULLABLE,
                -1, DefaultValue.NULL_DEFAULT_VALUE, "sequence column hidden column", false,
                Optional.empty());
    }

    public static ColumnDef newRowStoreColumnDef(AggregateType aggregateType) {
        return new ColumnDef(Column.ROW_STORE_COL, TypeDef.create(PrimitiveType.STRING), false, aggregateType,
                ColumnNullableType.NOT_NULLABLE, -1, new ColumnDef.DefaultValue(true, ""),
                "doris row store hidden column", false, Optional.empty());
    }

    public static ColumnDef newVersionColumnDef() {
        return new ColumnDef(Column.VERSION_COL, TypeDef.create(PrimitiveType.BIGINT), false, null,
                ColumnNullableType.NOT_NULLABLE, -1, new ColumnDef.DefaultValue(true, "0"),
                "doris version hidden column", false, Optional.empty());
    }

    public static ColumnDef newVersionColumnDef(AggregateType aggregateType) {
        return new ColumnDef(Column.VERSION_COL, TypeDef.create(PrimitiveType.BIGINT), false, aggregateType,
                ColumnNullableType.NOT_NULLABLE, -1, new ColumnDef.DefaultValue(true, "0"),
                "doris version hidden column", false, Optional.empty());
    }

    public static ColumnDef newSkipBitmapColumnDef(AggregateType aggregateType) {
        return new ColumnDef(Column.SKIP_BITMAP_COL, TypeDef.create(PrimitiveType.BITMAP), false, aggregateType,
                ColumnNullableType.NOT_NULLABLE, -1, DefaultValue.BITMAP_EMPTY_DEFAULT_VALUE,
                "doris skip bitmap hidden column", false, Optional.empty());
    }

    public boolean isAllowNull() {
        return isAllowNull;
    }

    public String getDefaultValue() {
        return defaultValue.value;
    }

    public String getName() {
        return name;
    }

    public AggregateType getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(AggregateType aggregateType) {
        this.aggregateType = aggregateType;
    }

    public boolean isKey() {
        return isKey;
    }

    public void setIsKey(boolean isKey) {
        this.isKey = isKey;
    }

    public void setKeysType(KeysType keysType) {
        this.keysType = keysType;
    }

    public TypeDef getTypeDef() {
        return typeDef;
    }

    public Type getType() {
        return typeDef.getType();
    }

    public String getComment() {
        return comment;
    }

    public boolean isVisible() {
        return visible;
    }

    public int getClusterKeyId() {
        return this.clusterKeyId;
    }

    public void setClusterKeyId(int clusterKeyId) {
        this.clusterKeyId = clusterKeyId;
    }

    public void analyze(boolean isOlap) throws AnalysisException {
        if (name == null || typeDef == null) {
            throw new AnalysisException("No column name or column type in column definition.");
        }
        FeNameFormat.checkColumnName(name);
        FeNameFormat.checkColumnCommentLength(comment);

        typeDef.analyze();

        Type type = typeDef.getType();

        if (!Config.enable_quantile_state_type && type.isQuantileStateType()) {
            throw new AnalysisException("quantile_state is disabled"
                    + "Set config 'enable_quantile_state_type' = 'true' to enable this column type.");
        }

        // disable Bitmap Hll type in keys, values without aggregate function.
        if (type.isBitmapType() || type.isHllType() || type.isQuantileStateType()) {
            if (isKey) {
                throw new AnalysisException("Key column can not set complex type:" + name);
            }
            if (keysType == null || keysType == KeysType.AGG_KEYS) {
                if (aggregateType == null) {
                    throw new AnalysisException("complex type have to use aggregate function: " + name);
                }
            }
            if (isAllowNull) {
                throw new AnalysisException("complex type column must be not nullable, column:" + name);
            }
        }

        // A column is a key column if and only if isKey is true.
        // aggregateType == null does not mean that this is a key column,
        // because when creating a UNIQUE KEY table, aggregateType is implicit.
        if (aggregateType != null) {
            if (isKey) {
                throw new AnalysisException("Key column can not set aggregation type: " + name);
            }

            // check if aggregate type is valid
            if (aggregateType != AggregateType.GENERIC
                    && !aggregateType.checkCompatibility(type.getPrimitiveType())) {
                throw new AnalysisException(String.format("Aggregate type %s is not compatible with primitive type %s",
                        toString(), type.toSql()));
            }
            if (aggregateType == AggregateType.GENERIC) {
                if (!SessionVariable.enableAggState()) {
                    throw new AnalysisException("agg state not enable, need set enable_agg_state=true");
                }
            }
        }

        if (type.getPrimitiveType() == PrimitiveType.FLOAT || type.getPrimitiveType() == PrimitiveType.DOUBLE) {
            if (isOlap && isKey) {
                throw new AnalysisException("Float or double can not used as a key, use decimal instead.");
            }
        }

        if (type.getPrimitiveType() == PrimitiveType.HLL) {
            if (defaultValue != null && defaultValue.isSet) {
                throw new AnalysisException("Hll type column can not set default value");
            }
            defaultValue = DefaultValue.HLL_EMPTY_DEFAULT_VALUE;
        }

        if (type.getPrimitiveType() == PrimitiveType.BITMAP) {
            if (defaultValue.isSet && defaultValue != DefaultValue.NULL_DEFAULT_VALUE
                    && !defaultValue.value.equals(DefaultValue.BITMAP_EMPTY_DEFAULT_VALUE.value)) {
                throw new AnalysisException("Bitmap type column default value only support null or "
                        + DefaultValue.BITMAP_EMPTY_DEFAULT_VALUE.value);
            }
            defaultValue = DefaultValue.BITMAP_EMPTY_DEFAULT_VALUE;
        }

        if (type.getPrimitiveType() == PrimitiveType.ARRAY && isOlap) {
            if (isKey()) {
                throw new AnalysisException("Array can only be used in the non-key column of"
                        + " the duplicate table at present.");
            }
            if (defaultValue.isSet && defaultValue != DefaultValue.NULL_DEFAULT_VALUE
                    && !defaultValue.value.equals(DefaultValue.ARRAY_EMPTY_DEFAULT_VALUE.value)) {
                throw new AnalysisException("Array type column default value only support null or "
                        + DefaultValue.ARRAY_EMPTY_DEFAULT_VALUE.value);
            }
        }
        if (isKey() && type.getPrimitiveType() == PrimitiveType.STRING && isOlap) {
            throw new AnalysisException("String Type should not be used in key column[" + getName()
                    + "].");
        }

        if (type.getPrimitiveType() == PrimitiveType.JSONB
                || type.getPrimitiveType() == PrimitiveType.VARIANT) {
            if (isKey()) {
                throw new AnalysisException("JSONB or VARIANT type should not be used in key column[" + getName()
                        + "].");
            }
            if (defaultValue.isSet && defaultValue != DefaultValue.NULL_DEFAULT_VALUE) {
                throw new AnalysisException("JSONB or VARIANT type column default value just support null");
            }
        }

        if (type.getPrimitiveType() == PrimitiveType.MAP) {
            if (isKey()) {
                throw new AnalysisException("Map can only be used in the non-key column of"
                        + " the duplicate table at present.");
            }
            if (defaultValue.isSet && defaultValue != DefaultValue.NULL_DEFAULT_VALUE) {
                throw new AnalysisException("Map type column default value just support null");
            }
        }

        if (type.getPrimitiveType() == PrimitiveType.STRUCT) {
            if (isKey()) {
                throw new AnalysisException("Struct can only be used in the non-key column of"
                        + " the duplicate table at present.");
            }
            if (defaultValue.isSet && defaultValue != DefaultValue.NULL_DEFAULT_VALUE) {
                throw new AnalysisException("Struct type column default value just support null");
            }
        }


        if (aggregateType == AggregateType.REPLACE_IF_NOT_NULL) {
            if (!isAllowNull) {
                throw new AnalysisException(
                        "REPLACE_IF_NOT_NULL column must be nullable, maybe should use REPLACE, column:" + name);
            }
            if (!defaultValue.isSet) {
                defaultValue = DefaultValue.NULL_DEFAULT_VALUE;
            }
        }

        if (!isAllowNull && defaultValue == DefaultValue.NULL_DEFAULT_VALUE) {
            throw new AnalysisException("Can not set null default value to non nullable column: " + name);
        }

        if (type.isScalarType() && defaultValue.isSet && defaultValue.value != null) {
            validateDefaultValue(type, defaultValue.value, defaultValue.defaultValueExprDef);
        }
        validateGeneratedColumnInfo();
    }

    @SuppressWarnings("checkstyle:Indentation")
    public static void validateDefaultValue(Type type, String defaultValue, DefaultValueExprDef defaultValueExprDef)
            throws AnalysisException {
        Preconditions.checkNotNull(defaultValue);
        Preconditions.checkArgument(type.isScalarType());
        ScalarType scalarType = (ScalarType) type;

        // check if default value is valid.
        // first, check if the type of defaultValue matches primitiveType.
        // if not check it first, some literal constructor will throw AnalysisException,
        // and it is not intuitive to users.
        PrimitiveType primitiveType = scalarType.getPrimitiveType();
        if (null != defaultValueExprDef && defaultValueExprDef.getExprName().equalsIgnoreCase("now")) {
            switch (primitiveType) {
                case DATETIME:
                case DATETIMEV2:
                    break;
                default:
                    throw new AnalysisException("Types other than DATETIME and DATETIMEV2 "
                            + "cannot use current_timestamp as the default value");
            }
        } else if (null != defaultValueExprDef
                && defaultValueExprDef.getExprName().equalsIgnoreCase(DefaultValue.CURRENT_DATE)) {
            switch (primitiveType) {
                case DATE:
                case DATEV2:
                    break;
                default:
                    throw new AnalysisException("Types other than DATE and DATEV2 "
                            + "cannot use current_date as the default value");
            }
        } else if (null != defaultValueExprDef
                && defaultValueExprDef.getExprName().equalsIgnoreCase(DefaultValue.PI)) {
            switch (primitiveType) {
                case DOUBLE:
                    break;
                default:
                    throw new AnalysisException("Types other than DOUBLE cannot use pi as the default value");
            }
        } else if (null != defaultValueExprDef
                && defaultValueExprDef.getExprName().equalsIgnoreCase(DefaultValue.E_NUM)) {
            switch (primitiveType) {
                case DOUBLE:
                    break;
                default:
                    throw new AnalysisException("Types other than DOUBLE cannot use e as the default value");
            }
        }
        switch (primitiveType) {
            case TINYINT:
            case SMALLINT:
            case INT:
            case BIGINT:
                new IntLiteral(defaultValue, type);
                break;
            case LARGEINT:
                new LargeIntLiteral(defaultValue);
                break;
            case FLOAT:
                FloatLiteral floatLiteral = new FloatLiteral(defaultValue);
                if (floatLiteral.getType().equals(Type.DOUBLE)) {
                    throw new AnalysisException("Default value will loose precision: " + defaultValue);
                }
                break;
            case DOUBLE:
                new FloatLiteral(defaultValue);
                break;
            case DECIMALV2:
                //no need to check precision and scale, since V2 is fixed point
                new DecimalLiteral(defaultValue);
                break;
            case DECIMAL32:
            case DECIMAL64:
            case DECIMAL128:
            case DECIMAL256:
                DecimalLiteral decimalLiteral = new DecimalLiteral(defaultValue);
                decimalLiteral.checkPrecisionAndScale(scalarType.getScalarPrecision(), scalarType.getScalarScale());
                break;
            case DATE:
            case DATEV2:
                if (defaultValueExprDef == null) {
                    new DateLiteral(defaultValue, scalarType);
                } else {
                    if (defaultValueExprDef.getExprName().equalsIgnoreCase(DefaultValue.CURRENT_DATE)) {
                        break;
                    } else {
                        throw new AnalysisException("date literal [" + defaultValue + "] is invalid");
                    }
                }
                break;
            case DATETIME:
            case DATETIMEV2:
                if (defaultValueExprDef == null) {
                    new DateLiteral(defaultValue, scalarType);
                } else {
                    if (defaultValueExprDef.getExprName().equals(DefaultValue.NOW)) {
                        if (defaultValueExprDef.getPrecision() != null) {
                            Long defaultValuePrecision = defaultValueExprDef.getPrecision();
                            String typeStr = scalarType.toString();
                            int typePrecision =
                                    Integer.parseInt(typeStr.substring(typeStr.indexOf("(") + 1, typeStr.indexOf(")")));
                            if (defaultValuePrecision > typePrecision) {
                                typeStr = typeStr.replace("V2", "");
                                throw new AnalysisException("default value precision: " + defaultValue
                                        + " can not be greater than type precision: " + typeStr);
                            }
                        }
                        break;
                    } else {
                        throw new AnalysisException("date literal [" + defaultValue + "] is invalid");
                    }
                }
                break;
            case CHAR:
            case VARCHAR:
            case HLL:
            case STRING:
            case JSONB:
                if (defaultValue.length() > scalarType.getLength()) {
                    throw new AnalysisException("Default value is too long: " + defaultValue);
                }
                break;
            case BITMAP:
            case ARRAY:
            case MAP:
            case STRUCT:
                break;
            case BOOLEAN:
                new BoolLiteral(defaultValue);
                break;
            case IPV4:
                new IPv4Literal(defaultValue);
                break;
            case IPV6:
                new IPv6Literal(defaultValue);
                break;
            default:
                throw new AnalysisException("Unsupported type: " + type);
        }
    }

    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("`").append(name).append("` ");
        sb.append(typeDef.toSql()).append(" ");

        if (aggregateType != null && aggregateType != AggregateType.NONE) {
            sb.append(aggregateType.name()).append(" ");
        }

        if (!isAllowNull) {
            sb.append("NOT NULL ");
        } else {
            // should append NULL to make result can be executed right.
            sb.append("NULL ");
        }

        if (isAutoInc) {
            sb.append("AUTO_INCREMENT ");
            sb.append("(");
            sb.append(autoIncInitValue);
            sb.append(")");
        }

        if (defaultValue.isSet) {
            if (defaultValue.value != null) {
                if (typeDef.getType().getPrimitiveType() != PrimitiveType.BITMAP
                        && typeDef.getType().getPrimitiveType() != PrimitiveType.HLL) {
                    if (defaultValue.defaultValueExprDef != null) {
                        sb.append("DEFAULT ").append(defaultValue.value).append(" ");
                    } else {
                        sb.append("DEFAULT ").append("\"").append(SqlUtils.escapeQuota(defaultValue.value)).append("\"")
                                .append(" ");
                    }
                } else if (typeDef.getType().getPrimitiveType() == PrimitiveType.BITMAP) {
                    sb.append("DEFAULT ").append(defaultValue.defaultValueExprDef.getExprName()).append(" ");
                }
            } else {
                sb.append("DEFAULT ").append("NULL").append(" ");
            }
        }
        sb.append("COMMENT \"").append(SqlUtils.escapeQuota(comment)).append("\"");

        return sb.toString();
    }

    public Column toColumn() {
        Type type = typeDef.getType();
        return new Column(name, type, isKey, aggregateType, isAllowNull, autoIncInitValue, defaultValue.value, comment,
                visible, defaultValue.defaultValueExprDef, Column.COLUMN_UNIQUE_ID_INIT_VALUE, defaultValue.getValue(),
                clusterKeyId, generatedColumnInfo.orElse(null), generatedColumnsThatReferToThis);
    }

    @Override
    public String toString() {
        return toSql();
    }

    public void setAllowNull(boolean allowNull) {
        isAllowNull = allowNull;
    }

    public Optional<GeneratedColumnInfo> getGeneratedColumnInfo() {
        return generatedColumnInfo;
    }

    public long getAutoIncInitValue() {
        return autoIncInitValue;
    }

    public void addGeneratedColumnsThatReferToThis(List<String> list) {
        generatedColumnsThatReferToThis.addAll(list);
    }

    private void validateGeneratedColumnInfo() throws AnalysisException {
        // for generated column
        if (generatedColumnInfo.isPresent()) {
            if (autoIncInitValue != -1) {
                throw new AnalysisException("Generated columns cannot be auto_increment.");
            }
            if (defaultValue != null && !defaultValue.isNullDefaultValue()) {
                throw new AnalysisException("Generated columns cannot have default value.");
            }
        }
    }
}
