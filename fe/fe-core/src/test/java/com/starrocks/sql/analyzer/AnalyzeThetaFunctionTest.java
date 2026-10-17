// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.analyzer;

import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.type.IntegerType;
import com.starrocks.type.Type;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeFail;
import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeSuccess;

public class AnalyzeThetaFunctionTest {
    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        AnalyzeTestUtil.init();
    }

    @Test
    public void testFunctionFamilyAndPlainBinaryStates() {
        assertBinaryResult("select ds_theta_accumulate(v1) from t0");
        assertBinaryResult("select ds_theta_accumulate(v1, 14) from t0");
        assertBinaryResult("select ds_theta_combine(to_binary('FE0E', 'hex')) from t0");
        assertBinaryResult("select ds_theta_combine(ds_theta_count_distinct_state(v1, 16)) from t0");
        assertBigintResult("select ds_theta_estimate(to_binary('FE0E', 'hex')) from t0");
        assertBigintResult("select ds_theta_estimate(ds_theta_count_distinct_state(v1)) from t0");
        assertBigintResult("select ds_theta_count_distinct(v1, 5) from t0");
        assertBigintResult("select ds_theta_count_distinct(v1, 26) from t0");
        analyzeFail("select ds_theta_estimate(v1) from t0");
        analyzeFail("select ds_theta_combine(v1) from t0");
    }

    @Test
    public void testPrecisionIsAValidatedConstant() {
        for (String function : new String[] {"ds_theta_count_distinct", "ds_theta_count_distinct_state",
                "ds_theta_accumulate"}) {
            for (String precision : new String[] {"4", "27", "256", "NULL", "v2", "14.5",
                    "18446744073709551630", "-18446744073709551602"}) {
                analyzeFail("select " + function + "(v1, " + precision + ") from t0",
                        "second parameter must be a constant integer between 5 and 26");
            }
        }
    }

    private static void assertBinaryResult(String sql) {
        Assertions.assertTrue(outputType(sql).isBinaryType());
    }

    private static void assertBigintResult(String sql) {
        Assertions.assertEquals(IntegerType.BIGINT, outputType(sql));
    }

    private static Type outputType(String sql) {
        QueryStatement statement = (QueryStatement) analyzeSuccess(sql);
        return statement.getQueryRelation().getOutputExpression().get(0).getType();
    }
}
