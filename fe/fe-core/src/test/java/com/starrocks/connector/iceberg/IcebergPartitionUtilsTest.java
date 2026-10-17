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

package com.starrocks.connector.iceberg;

import com.google.common.collect.Range;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.plan.ConnectorPlanTestBase;
import com.starrocks.type.DateType;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.apache.iceberg.PartitionField;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.TimeZone;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IcebergPartitionUtilsTest extends TableTestBase {
    @TempDir
    public static File catalogTemp;
    private static ConnectContext connectContext;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        connectContext = UtFrameUtils.createDefaultCtx();
        ConnectorPlanTestBase.mockAllCatalogs(connectContext, newFolder(catalogTemp, "junit").toURI().toString());
    }

    @Test
    public void testNormalizeTimePartitionName() {
        new MockUp<TimeUtils>() {
            @Mock
            public  TimeZone getTimeZone() {
                return TimeZone.getTimeZone("GMT+6");
            }
        };
        // year
        // with time zone
        String partitionName = "2020";
        PartitionField partitionField = SPEC_D_2.fields().get(0);
        String result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATETIME);
        Assertions.assertEquals("2020-01-01 06:00:00", result);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATE);
        Assertions.assertEquals("2020-01-01", result);
        // without time zone
        partitionField = SPEC_E_2.fields().get(0);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_E,
                DateType.DATETIME);
        Assertions.assertEquals("2020-01-01 00:00:00", result);

        // month
        // with time zone
        partitionName = "2020-02";
        partitionField = SPEC_D_3.fields().get(0);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATETIME);
        Assertions.assertEquals("2020-02-01 06:00:00", result);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATE);
        Assertions.assertEquals("2020-02-01", result);
        // without time zone
        partitionField = SPEC_E_3.fields().get(0);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_E,
                DateType.DATETIME);
        Assertions.assertEquals("2020-02-01 00:00:00", result);

        // day
        // with time zone
        partitionName = "2020-01-02";
        partitionField = SPEC_D_4.fields().get(0);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATETIME);
        Assertions.assertEquals("2020-01-02 06:00:00", result);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATE);
        Assertions.assertEquals("2020-01-02", result);
        // without time zone
        partitionField = SPEC_E_4.fields().get(0);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_E,
                DateType.DATETIME);
        Assertions.assertEquals("2020-01-02 00:00:00", result);

        // hour
        partitionName = "2020-01-02-12";
        partitionField = SPEC_D_5.fields().get(0);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATETIME);
        Assertions.assertEquals("2020-01-02 18:00:00", result);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_D,
                DateType.DATE);
        Assertions.assertEquals("2020-01-02", result);
        // without time zone
        partitionField = SPEC_E_5.fields().get(0);
        result = IcebergPartitionUtils.normalizeTimePartitionName(partitionName, partitionField, SCHEMA_E,
                DateType.DATETIME);
        Assertions.assertEquals("2020-01-02 12:00:00", result);
    }

    @Test
    public void testNormalizeProlepticYearAndLeapDay() {
        PartitionField day = SPEC_E_4.fields().get(0);
        for (String value : new String[] {"0000-01-01", "0000-02-29", "2000-02-29", "9999-12-31"}) {
            Assertions.assertEquals(value, IcebergPartitionUtils.normalizeTimePartitionName(
                    value, day, SCHEMA_E, DateType.DATE));
        }
        Assertions.assertEquals("0000-01-01", IcebergPartitionUtils.normalizeTimePartitionName(
                "0000", SPEC_E_2.fields().get(0), SCHEMA_E, DateType.DATE));
        Assertions.assertEquals("0000-02-01", IcebergPartitionUtils.normalizeTimePartitionName(
                "0000-02", SPEC_E_3.fields().get(0), SCHEMA_E, DateType.DATE));
    }

    @Test
    public void testNormalizeInvalidPartitionFailsWithoutChangingItsValue() {
        PartitionField day = SPEC_E_4.fields().get(0);
        for (String value : new String[] {"1900-02-29", "2024-02-30", "12000-05-25", "-0001-01-01"}) {
            IcebergPartitionUtils.InvalidPartitionValueException error = Assertions.assertThrows(
                    IcebergPartitionUtils.InvalidPartitionValueException.class,
                    () -> IcebergPartitionUtils.normalizeTimePartitionName(value, day, SCHEMA_E, DateType.DATETIME));
            Assertions.assertTrue(error.getMessage().contains(value));
            Assertions.assertTrue(error.getMessage().contains("[0000, 9999]"));
            Assertions.assertTrue(error.getMessage().contains("day"));
            Assertions.assertNotNull(error.getCause());
        }
        Assertions.assertThrows(IcebergPartitionUtils.InvalidPartitionValueException.class,
                () -> IcebergPartitionUtils.normalizeTimePartitionName(
                        "2024-01-01-24", SPEC_E_5.fields().get(0), SCHEMA_E, DateType.DATETIME));
    }

    @Test
    public void testNormalizeTimeZoneDateBoundaryAndOverflow() {
        new MockUp<TimeUtils>() {
            @Mock
            public TimeZone getTimeZone() {
                return TimeZone.getTimeZone("GMT-6");
            }
        };
        PartitionField day = SPEC_D_4.fields().get(0);
        Assertions.assertEquals("2023-12-31 18:00:00", IcebergPartitionUtils.normalizeTimePartitionName(
                "2024-01-01", day, SCHEMA_D, DateType.DATETIME));
        Assertions.assertThrows(IcebergPartitionUtils.InvalidPartitionValueException.class,
                () -> IcebergPartitionUtils.normalizeTimePartitionName("0000-01-01", day, SCHEMA_D, DateType.DATETIME));
    }

    @Test
    public void testYearZeroPartitionRangePreservesLeapDay() {
        IcebergTable table = mock(IcebergTable.class);
        when(table.getNativeTable()).thenReturn(mockedNativeTableJ);
        when(table.getColumn("ts")).thenReturn(new Column("ts", DateType.DATETIME));
        Range<String> range = IcebergPartitionUtils.toPartitionRange(
                table, "ts", "0000-02-29", SPEC_E_4.fields().get(0), true);
        Assertions.assertEquals("0000-02-29 00:00:00", range.lowerEndpoint());
        Assertions.assertEquals("0000-03-01 00:00:00", range.upperEndpoint());

        when(table.getColumn("ts")).thenReturn(new Column("ts", DateType.DATE));
        range = IcebergPartitionUtils.toPartitionRange(table, "ts", "0000-02-29", SPEC_E_4.fields().get(0), true);
        Assertions.assertEquals("0000-02-29", range.lowerEndpoint());
        Assertions.assertEquals("0000-03-01", range.upperEndpoint());
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
