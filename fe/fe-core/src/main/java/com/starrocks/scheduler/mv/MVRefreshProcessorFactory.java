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
// limitations under the License

package com.starrocks.scheduler.mv;

import com.google.common.base.Strings;
import com.starrocks.catalog.BaseTableInfo;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.common.tvr.TvrVersionRange;
import com.starrocks.metric.IMaterializedViewMetricsEntity;
import com.starrocks.scheduler.MvTaskRunContext;
import com.starrocks.scheduler.mv.hybrid.MVHybridRefreshProcessor;
import com.starrocks.scheduler.mv.ivm.MVIVMRefreshProcessor;
import com.starrocks.scheduler.mv.pct.MVPCTRefreshProcessor;
import com.starrocks.sql.analyzer.AnalyzerUtils;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.common.DmlException;
import com.starrocks.sql.parser.SqlParser;

import java.util.Map;

public class MVRefreshProcessorFactory {
    public static final MVRefreshProcessorFactory INSTANCE = new MVRefreshProcessorFactory();

    public MVRefreshProcessor newProcessor(Database db, MaterializedView mv,
                                           MvTaskRunContext mvContext,
                                           IMaterializedViewMetricsEntity mvEntity) {
        // Older MVs may retain the temporal clause only in the original definition, not the executable SQL.
        String definition = mv.getOriginalViewDefineSql();
        if (Strings.isNullOrEmpty(definition)) {
            definition = mv.getMVQueryDefinedSql();
        }
        if (!Strings.isNullOrEmpty(definition)) {
            StatementBase query = SqlParser.parseSingleStatement(definition,
                    mvContext.getCtx().getSessionVariable().getSqlMode());
            try {
                AnalyzerUtils.prohibitTimeTravelQuery(query, "refresh materialized view");
            } catch (SemanticException e) {
                throw new DmlException("Materialized view %s cannot be refreshed because time-travel definitions " +
                        "are unsupported. Recreate it from a supported definition.", e, mv.getName());
            }
        }
        MaterializedView.RefreshMode refreshMode = mv.getCurrentRefreshMode();
        switch (refreshMode) {
            case INCREMENTAL:
                // Route through the hybrid processor when any base table lacks a TVR baseline so
                // PCT can rebuild it. Otherwise run pure IVM with strict semantics (no fallback).
                if (needsPctBaselineRebuild(mv)) {
                    return new MVHybridRefreshProcessor(db, mv, mvContext, mvEntity, refreshMode);
                }
                return new MVIVMRefreshProcessor(db, mv, mvContext, mvEntity, refreshMode);
            case AUTO:
                return new MVHybridRefreshProcessor(db, mv, mvContext, mvEntity, refreshMode);
            default:
                return new MVPCTRefreshProcessor(db, mv, mvContext, mvEntity, refreshMode);
        }
    }

    private static boolean needsPctBaselineRebuild(MaterializedView mv) {
        // True for the genuine first refresh and also after MVMetaVersionRepairer rewrites a
        // BaseTableInfo key without updating baseTableInfoTvrVersionRangeMap, which would
        // otherwise leave pure IVM throwing "No checkpoint found" with no fallback.
        Map<BaseTableInfo, TvrVersionRange> tvrMap = mv.getRefreshScheme().getAsyncRefreshContext()
                .getBaseTableInfoTvrVersionRangeMap();
        for (BaseTableInfo info : mv.getBaseTableInfos()) {
            if (!tvrMap.containsKey(info)) {
                return true;
            }
        }
        return false;
    }
}
