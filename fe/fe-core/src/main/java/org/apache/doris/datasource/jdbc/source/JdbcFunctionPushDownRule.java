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

package org.apache.doris.datasource.jdbc.source;

import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.FunctionCallExpr;
import org.apache.doris.analysis.FunctionName;
import org.apache.doris.analysis.TimestampArithmeticExpr;
import org.apache.doris.catalog.TableIf.TableType;
import org.apache.doris.common.Config;
import org.apache.doris.datasource.ExternalFunctionRules;
import org.apache.doris.datasource.ExternalFunctionRules.FunctionRewriteRules;
import org.apache.doris.thrift.TOdbcTableType;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class JdbcFunctionPushDownRule {
    private static final Logger LOG = LogManager.getLogger(JdbcFunctionPushDownRule.class);
    public static final TreeSet<String> MYSQL_UNSUPPORTED_FUNCTIONS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        MYSQL_UNSUPPORTED_FUNCTIONS.add("date_trunc");
        MYSQL_UNSUPPORTED_FUNCTIONS.add("money_format");
        MYSQL_UNSUPPORTED_FUNCTIONS.add("negative");
        MYSQL_UNSUPPORTED_FUNCTIONS.addAll(Arrays.asList(Config.jdbc_mysql_unsupported_pushdown_functions));
    }

    public static final TreeSet<String> CLICKHOUSE_SUPPORTED_FUNCTIONS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        CLICKHOUSE_SUPPORTED_FUNCTIONS.add("from_unixtime");
        CLICKHOUSE_SUPPORTED_FUNCTIONS.add("unix_timestamp");
    }

    public static final TreeSet<String> ORACLE_SUPPORTED_FUNCTIONS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        ORACLE_SUPPORTED_FUNCTIONS.add("nvl");
        ORACLE_SUPPORTED_FUNCTIONS.add("ifnull");
    }

    private static boolean isMySQLFunctionUnsupported(String functionName) {
        return MYSQL_UNSUPPORTED_FUNCTIONS.contains(functionName.toLowerCase());
    }

    private static boolean isClickHouseFunctionUnsupported(String functionName) {
        return !CLICKHOUSE_SUPPORTED_FUNCTIONS.contains(functionName.toLowerCase());
    }

    private static boolean isOracleFunctionUnsupported(String functionName) {
        return !ORACLE_SUPPORTED_FUNCTIONS.contains(functionName.toLowerCase());
    }

    public static final Map<String, String> REPLACE_MYSQL_FUNCTIONS = Maps.newHashMap();

    static {
        REPLACE_MYSQL_FUNCTIONS.put("nvl", "ifnull");
        REPLACE_MYSQL_FUNCTIONS.put("to_date", "date");
    }

    public static final Map<String, String> REPLACE_CLICKHOUSE_FUNCTIONS = Maps.newHashMap();

    static {
        REPLACE_CLICKHOUSE_FUNCTIONS.put("from_unixtime", "FROM_UNIXTIME");
        REPLACE_CLICKHOUSE_FUNCTIONS.put("unix_timestamp", "toUnixTimestamp");
    }

    public static final Map<String, String> REPLACE_ORACLE_FUNCTIONS = Maps.newHashMap();

    static {
        REPLACE_ORACLE_FUNCTIONS.put("ifnull", "nvl");
    }

    private static boolean isReplaceMysqlFunctions(String functionName) {
        return REPLACE_MYSQL_FUNCTIONS.containsKey(functionName.toLowerCase());
    }

    private static boolean isReplaceClickHouseFunctions(String functionName) {
        return REPLACE_CLICKHOUSE_FUNCTIONS.containsKey(functionName.toLowerCase());
    }

    private static boolean isReplaceOracleFunctions(String functionName) {
        return REPLACE_ORACLE_FUNCTIONS.containsKey(functionName.toLowerCase());
    }

    public static Expr processFunctions(TOdbcTableType tableType, Expr expr, List<String> errors,
            ExternalFunctionRules functionRules) {
        if (tableType == null || expr == null || functionRules == null) {
            return expr;
        }

        return processFunctionsRecursively(expr, functionRules, errors, tableType);
    }

    private static Expr processFunctionsRecursively(Expr expr, ExternalFunctionRules functionRules, List<String> errors,
            TOdbcTableType tableType) {
        if (expr instanceof FunctionCallExpr) {
            FunctionCallExpr functionCallExpr = (FunctionCallExpr) expr;
            String func = functionCallExpr.getFnName().getFunction();

            Preconditions.checkArgument(!func.isEmpty(), "function can not be empty");

            // 1. check can push down
            if (!functionRules.getFunctionPushDownRule().canPushDown(func)) {
                if (LOG.isDebugEnabled()) {
                    String errMsg = "Unsupported function: " + func + " in expr: " + expr.toExternalSql(
                            TableType.JDBC_EXTERNAL_TABLE, null)
                            + " in JDBC Table Type: " + tableType;
                    LOG.debug(errMsg);
                }
                errors.add("has error");
            }

            // 2. replace function
            replaceFunctionNameIfNecessary(func, functionRules.getFunctionRewriteRule(), functionCallExpr);
            expr = replaceGenericFunctionExpr(functionCallExpr, func);
        }

        List<Expr> children = expr.getChildren();
        for (int i = 0; i < children.size(); i++) {
            Expr child = children.get(i);
            Expr newChild = processFunctionsRecursively(child, functionRules, errors, tableType);
            expr.setChild(i, newChild);
        }

        return expr;
    }

    private static void replaceFunctionNameIfNecessary(String func, FunctionRewriteRules rewriteRule,
            FunctionCallExpr functionCallExpr) {
        String newFuncName = rewriteRule.rewriteFunction(func);
        if (!newFuncName.equals(func)) {
            functionCallExpr.setFnName(FunctionName.createBuiltinName(newFuncName));
        }
    }

    // Function used to convert nereids planner's function to old planner's function
    private static Expr replaceGenericFunctionExpr(FunctionCallExpr functionCallExpr, String func) {
        Map<String, String> supportedTimeUnits = Maps.newHashMap();
        supportedTimeUnits.put("years", "YEAR");
        supportedTimeUnits.put("months", "MONTH");
        supportedTimeUnits.put("weeks", "WEEK");
        supportedTimeUnits.put("days", "DAY");
        supportedTimeUnits.put("hours", "HOUR");
        supportedTimeUnits.put("minutes", "MINUTE");
        supportedTimeUnits.put("seconds", "SECOND");

        String baseFuncName = null;
        String timeUnit = null;

        for (Map.Entry<String, String> entry : supportedTimeUnits.entrySet()) {
            if (func.endsWith(entry.getKey() + "_add")) {
                baseFuncName = "date_add";
                timeUnit = entry.getValue();
                break;
            } else if (func.endsWith(entry.getKey() + "_sub")) {
                baseFuncName = "date_sub";
                timeUnit = entry.getValue();
                break;
            }
        }

        if (baseFuncName != null && timeUnit != null) {
            if (functionCallExpr.getChildren().size() == 2) {
                Expr child1 = functionCallExpr.getChild(0);
                Expr child2 = functionCallExpr.getChild(1);
                return new TimestampArithmeticExpr(
                        baseFuncName,
                        child1,
                        child2,
                        timeUnit
                );
            }
        }
        return functionCallExpr;
    }
}
