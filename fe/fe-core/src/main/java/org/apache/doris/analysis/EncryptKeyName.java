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

package org.apache.doris.analysis;

import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.FeNameFormat;
import org.apache.doris.qe.ConnectContext;

import com.google.common.base.Strings;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;

public class EncryptKeyName {
    private static final Logger LOG = LogManager.getLogger(EncryptKeyName.class);

    @SerializedName(value = "db")
    private String db;
    @SerializedName(value = "keyName")
    private String keyName;

    public EncryptKeyName(String db, String keyName) {
        this.db = db;
        this.keyName = keyName.toLowerCase();
        if (db != null) {
            this.db = db.toLowerCase();
        }
    }

    /**
     * EncryptKeyName
     * @param parts like [db1,keyName] or [keyName]
     */
    public EncryptKeyName(List<String> parts) {
        int size = parts.size();
        keyName = parts.get(size - 1);
        keyName = keyName.toLowerCase();
        if (size >= 2) {
            db = parts.get(size - 2);
        }
    }

    public EncryptKeyName(String keyName) {
        this.db = null;
        this.keyName = keyName.toLowerCase();
    }

    public void analyze() throws AnalysisException {
    }

    public void analyze(ConnectContext ctx) throws AnalysisException {
        FeNameFormat.checkCommonName("EncryptKey", keyName);
        if (db == null) {
            db = ctx.getDatabase();
            if (Strings.isNullOrEmpty(db)) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_NO_DB_ERROR);
            }
        }
    }

    public String getDb() {
        return db;
    }

    public String getKeyName() {
        return keyName;
    }

    @Override
    public String toString() {
        if (db == null) {
            return keyName;
        }
        return ClusterNamespace.getNameFromFullName(db) + "." + keyName;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof EncryptKeyName)) {
            return false;
        }
        EncryptKeyName o = (EncryptKeyName) obj;
        if ((db == null || o.db == null) && (db != o.db)) {
            if (db == null && o.db != null) {
                return false;
            }
            if (db != null && o.db == null) {
                return false;
            }
            if (!db.equalsIgnoreCase(o.db)) {
                return false;
            }
        }
        return keyName.equalsIgnoreCase(o.keyName);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(db) + Objects.hashCode(keyName);
    }

    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("KEY ");
        if (db != null) {
            sb.append(ClusterNamespace.getNameFromFullName(db)).append(".");
        }
        sb.append(keyName);
        return sb.toString();
    }
}
