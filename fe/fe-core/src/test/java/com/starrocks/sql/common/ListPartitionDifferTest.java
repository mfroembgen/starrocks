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

package com.starrocks.sql.common;

import com.starrocks.catalog.Column;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.mv.MVTimelinessArbiter.QueryRewriteParams;
import com.starrocks.common.tvr.TvrVersionRange;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.iceberg.IcebergPartitionUtils;
import com.starrocks.type.DateType;
import mockit.Mock;
import mockit.MockUp;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListPartitionDifferTest {
    @Test
    void testInvalidPartitionFailsRefreshButKeepsRewriteAndTransientFallbacks() {
        Schema schema = new Schema(Types.NestedField.optional(1, "event_time", Types.TimestampType.withoutZone()));
        PartitionSpec spec = PartitionSpec.builderFor(schema).day("event_time").build();
        IcebergPartitionUtils.InvalidPartitionValueException invalidPartition = Assertions.assertThrows(
                IcebergPartitionUtils.InvalidPartitionValueException.class,
                () -> IcebergPartitionUtils.normalizeTimePartitionName(
                        "12000-05-25", spec.fields().get(0), schema, DateType.DATETIME));
        AtomicReference<RuntimeException> failure = new AtomicReference<>(invalidPartition);
        new MockUp<PartitionUtil>() {
            @Mock
            public PCellSortedSet getPartitionCells(Table table, List<Column> columns, TvrVersionRange range) {
                throw failure.get();
            }
        };

        MaterializedView mv = mock(MaterializedView.class);
        Table table = mock(Table.class);
        when(table.getUUID()).thenReturn("synthetic-table");
        when(mv.getRefBaseTablePartitionColumns()).thenReturn(
                Map.of(table, List.of(new Column("event_time", DateType.DATETIME))));
        ListPartitionDiffer refreshDiffer = new ListPartitionDiffer(mv, QueryRewriteParams.ofRefresh());
        ListPartitionDiffer rewriteDiffer = new ListPartitionDiffer(mv, QueryRewriteParams.ofQueryRewrite(null));

        Assertions.assertSame(invalidPartition, Assertions.assertThrows(
                IcebergPartitionUtils.InvalidPartitionValueException.class, refreshDiffer::syncBaseTablePartitionInfos));
        Assertions.assertNull(rewriteDiffer.syncBaseTablePartitionInfos());

        failure.set(new StarRocksConnectorException("Synthetic metastore unavailable"));
        Assertions.assertNull(refreshDiffer.syncBaseTablePartitionInfos());
        Assertions.assertNull(rewriteDiffer.syncBaseTablePartitionInfos());
    }
}
