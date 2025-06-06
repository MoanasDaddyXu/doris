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

suite("create_table_use_partition_policy") {
    def cooldown_ttl = "10"

    def create_table_partition_use_not_create_policy = try_sql """
        CREATE TABLE IF NOT EXISTS create_table_partition_use_not_create_policy
        (
            k1 DATE,
            k2 INT,
            V1 VARCHAR(2048) REPLACE
        ) PARTITION BY RANGE (k1) (
            PARTITION p1 VALUES LESS THAN ("2022-01-01") ("storage_policy" = "not_exist_policy_1" ,"replication_num"="1"),
            PARTITION p2 VALUES LESS THAN ("2022-02-01") ("storage_policy" = "not_exist_policy_2" ,"replication_num"="1")
        ) DISTRIBUTED BY HASH(k2) BUCKETS 1;
    """

    // errCode = 2, detailMessage = Storage policy does not exist. name: not_exist_policy_1
    assertEquals(create_table_partition_use_not_create_policy, null)

    def storage_exist = { name ->
        def show_storage_policy = sql """
        SHOW STORAGE POLICY;
        """
        for(iter in show_storage_policy){
            if(name == iter[0]){
                return true;
            }
        }
        return false;
    }

    if (!storage_exist.call("test_create_table_partition_use_policy_1")) {
        def create_s3_resource = try_sql """
            CREATE RESOURCE IF NOT EXISTS "test_create_table_partition_use_resource_1"
            PROPERTIES(
                "type"="s3",
                "AWS_REGION" = "bj",
                "AWS_ENDPOINT" = "bj.s3.comaaaa",
                "AWS_ROOT_PATH" = "path/to/rootaaaa",
                "AWS_SECRET_KEY" = "aaaa",
                "AWS_ACCESS_KEY" = "bbba",
                "AWS_BUCKET" = "test-bucket",
                "s3_validity_check" = "false"
            );
        """
        def create_succ_1 = try_sql """
            CREATE STORAGE POLICY IF NOT EXISTS test_create_table_partition_use_policy_1
            PROPERTIES(
            "storage_resource" = "test_create_table_partition_use_resource_1",
            "cooldown_ttl" = "$cooldown_ttl"
            );
        """
        assertEquals(storage_exist.call("test_create_table_partition_use_policy_1"), true)
    }


    if (!storage_exist.call("test_create_table_partition_use_policy_2")) {
        def create_s3_resource = try_sql """
            CREATE RESOURCE IF NOT EXISTS "test_create_table_partition_use_resource_2"
            PROPERTIES(
                "type"="s3",
                "AWS_REGION" = "bj",
                "AWS_ENDPOINT" = "bj.s3.comaaaa",
                "AWS_ROOT_PATH" = "path/to/rootaaaa",
                "AWS_SECRET_KEY" = "aaaa",
                "AWS_ACCESS_KEY" = "bbba",
                "AWS_BUCKET" = "test-bucket",
                "s3_validity_check" = "false"
            );
        """
        def create_succ_1 = try_sql """
            CREATE STORAGE POLICY IF NOT EXISTS test_create_table_partition_use_policy_2
            PROPERTIES(
            "storage_resource" = "test_create_table_partition_use_resource_2",
            "cooldown_ttl" = "$cooldown_ttl"
            );
        """
        assertEquals(storage_exist.call("test_create_table_partition_use_policy_2"), true)
    }

    // success
    def create_table_partition_use_created_policy = try_sql """
        CREATE TABLE IF NOT EXISTS create_table_partition_use_created_policy
        (
            k1 DATE,
            k2 INT,
            V1 VARCHAR(2048) REPLACE
        ) PARTITION BY RANGE (k1) (
            PARTITION p1 VALUES LESS THAN ("2022-01-01") ("storage_policy" = "test_create_table_partition_use_policy_1" ,"replication_num"="1"),
            PARTITION p2 VALUES LESS THAN ("2022-02-01") ("storage_policy" = "test_create_table_partition_use_policy_2" ,"replication_num"="1")
        ) DISTRIBUTED BY HASH(k2) BUCKETS 1;
    """

    assertEquals(create_table_partition_use_created_policy.size(), 1);

    sql """
    DROP TABLE create_table_partition_use_created_policy
    """

    def create_table_partition_use_created_policy_1 = try_sql """
        CREATE TABLE IF NOT EXISTS create_table_partition_use_created_policy_1
        (
            k1 DATEV2,
            k2 INT,
            V1 VARCHAR(2048) REPLACE
        ) PARTITION BY RANGE (k1) (
            PARTITION p1 VALUES LESS THAN ("2022-01-01") ("storage_policy" = "test_create_table_partition_use_policy_1" ,"replication_num"="1"),
            PARTITION p2 VALUES LESS THAN ("2022-02-01") ("storage_policy" = "test_create_table_partition_use_policy_2" ,"replication_num"="1")
        ) DISTRIBUTED BY HASH(k2) BUCKETS 1;
    """

    assertEquals(create_table_partition_use_created_policy_1.size(), 1);

    sql """
    DROP TABLE create_table_partition_use_created_policy_1
    """

    def create_table_partition_use_created_policy_2 = try_sql """
        CREATE TABLE IF NOT EXISTS create_table_partition_use_created_policy_2
        (
            k1 DATETIMEV2(3),
            k2 INT,
            V1 VARCHAR(2048) REPLACE
        ) PARTITION BY RANGE (k1) (
            PARTITION p1 VALUES LESS THAN ("2022-01-01 00:00:00.111") ("storage_policy" = "test_create_table_partition_use_policy_1" ,"replication_num"="1"),
            PARTITION p2 VALUES LESS THAN ("2022-02-01 00:00:00.111") ("storage_policy" = "test_create_table_partition_use_policy_2" ,"replication_num"="1")
        ) DISTRIBUTED BY HASH(k2) BUCKETS 1
        PROPERTIES (
            "replication_num" = "1"
        );
    """

    assertEquals(create_table_partition_use_created_policy_2.size(), 1);

    // test create table like
    sql """
    CREATE TABLE create_table_partition_use_created_policy_3 LIKE create_table_partition_use_created_policy_2;
    """
    def policy_using_item_count_1 = try_sql """
        SHOW STORAGE POLICY USING for test_create_table_partition_use_policy_1
    """
    assertEquals(policy_using_item_count_1.size(), 2);

    def policy_using_item_count_2 = try_sql """
        SHOW STORAGE POLICY USING for test_create_table_partition_use_policy_2
    """
    assertEquals(policy_using_item_count_2.size(), 2);

    def create_table_partition_use_created_policy_4 = try_sql """
        CREATE TABLE IF NOT EXISTS create_table_partition_use_created_policy_4
        (
            k1 DATE,
            k2 INT,
            V1 VARCHAR(2048) REPLACE
        ) PARTITION BY LIST (k1) (
            PARTITION p1 VALUES IN ("2022-01-01") ("storage_policy" = "test_create_table_partition_use_policy_1" ,"replication_num"="1"),
            PARTITION p2 VALUES IN ("2022-02-01") ("storage_policy" = "test_create_table_partition_use_policy_2" ,"replication_num"="1")
        ) DISTRIBUTED BY HASH(k2) BUCKETS 1
        PROPERTIES (
            "replication_num" = "1"
        );
    """
    sql """
    CREATE TABLE create_table_partition_use_created_policy_5 LIKE create_table_partition_use_created_policy_4;
    """
    def policy_using_item_count_3 = try_sql """
        SHOW STORAGE POLICY USING for test_create_table_partition_use_policy_1
    """
    assertEquals(policy_using_item_count_3.size(), 4);

    def policy_using_item_count_4 = try_sql """
        SHOW STORAGE POLICY USING for test_create_table_partition_use_policy_2
    """
    assertEquals(policy_using_item_count_4.size(), 4);

    sql """
    DROP TABLE create_table_partition_use_created_policy_5;
    """
    sql """
    DROP TABLE create_table_partition_use_created_policy_4;
    """
    sql """
    DROP TABLE create_table_partition_use_created_policy_3;
    """
    sql """
    DROP TABLE create_table_partition_use_created_policy_2;
    """
    sql """
    DROP STORAGE POLICY test_create_table_partition_use_policy_1;
    """
    sql """
    DROP STORAGE POLICY test_create_table_partition_use_policy_2;
    """
    sql """
    DROP RESOURCE test_create_table_partition_use_resource_1
    """
    sql """
    DROP RESOURCE test_create_table_partition_use_resource_2
    """
}
