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

package com.starrocks.scheduler.mv;

import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.metric.IMaterializedViewMetricsEntity;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.scheduler.MvTaskRunContext;
import com.starrocks.sql.common.DmlException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MVRefreshProcessorFactoryTest {
    @Test
    void testLegacyTimeTravelDefinitionRejectedBeforeRefresh() {
        MaterializedView mv = mock(MaterializedView.class);
        when(mv.getName()).thenReturn("legacy_snapshot_mv");
        when(mv.getOriginalViewDefineSql()).thenReturn(
                "select id from iceberg_catalog.example.events for version as of 123");
        when(mv.getMVQueryDefinedSql()).thenReturn("select id from iceberg_catalog.example.events");
        assertRejectedBeforeRefresh(mv);
    }

    @Test
    void testTimeTravelExecutableDefinitionRejectedWhenOriginalIsMissing() {
        MaterializedView mv = mock(MaterializedView.class);
        when(mv.getName()).thenReturn("legacy_snapshot_mv");
        when(mv.getMVQueryDefinedSql()).thenReturn(
                "select id from iceberg_catalog.example.events for version as of 123");
        assertRejectedBeforeRefresh(mv);
    }

    private void assertRejectedBeforeRefresh(MaterializedView mv) {
        Database db = mock(Database.class);
        IMaterializedViewMetricsEntity metrics = mock(IMaterializedViewMetricsEntity.class);
        MvTaskRunContext context = mock(MvTaskRunContext.class);
        ConnectContext connection = mock(ConnectContext.class);
        when(context.getCtx()).thenReturn(connection);
        when(connection.getSessionVariable()).thenReturn(new SessionVariable());

        DmlException error = Assertions.assertThrows(DmlException.class,
                () -> MVRefreshProcessorFactory.INSTANCE.newProcessor(db, mv, context, metrics));
        Assertions.assertTrue(error.getMessage().contains("time-travel definitions are unsupported"));
        Assertions.assertTrue(error.getMessage().contains("Recreate"));
        Assertions.assertNotNull(error.getCause());
        verify(mv, never()).getCurrentRefreshMode();
        verifyNoInteractions(db, metrics);
    }
}
