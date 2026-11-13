# StarRocks 4.1 uses the upstream Ubuntu 22.04 toolchain on both native CPUs.
FROM starrocks/dev-env-ubuntu:4.1.7@sha256:335d88e99a01f509e23efbab55b25e08930693c77bf081fefb86f353e475436a AS source
WORKDIR /workspace
COPY . .
ARG SOURCE_SHA
ENV STARROCKS_VERSION=4.1.7-theta \
    STARROCKS_COMMIT_HASH=${SOURCE_SHA} \
    BUILD_TYPE=Release \
    CUSTOM_MVN="mvn --batch-mode --no-transfer-progress"
RUN test "$(git rev-parse HEAD)" = "${SOURCE_SHA}"

FROM source AS fe-tests
ENV FE_TESTS=com.starrocks.sql.analyzer.AnalyzeThetaFunctionTest,com.starrocks.sql.analyzer.MaterializedViewAnalyzerTest,com.starrocks.connector.iceberg.IcebergPartitionUtilsTest,com.starrocks.sql.common.ListPartitionDifferTest,com.starrocks.scheduler.mv.MVRefreshProcessorFactoryTest,com.starrocks.scheduler.MVTaskRunProcessorTest
RUN ./run-fe-ut.sh -j 2 --test "$FE_TESTS" && \
    python3 docker/dockerfiles/theta/check-tests.py fe/fe-core/target/surefire-reports "$FE_TESTS"

FROM scratch AS fe-test-reports
COPY --from=fe-tests /workspace/fe/fe-core/target/surefire-reports/ /

FROM source AS be-tests
ENV GTEST_OUTPUT=xml:/workspace/be-test-reports/
RUN ./run-be-ut.sh -j 3 --build-target ds_theta_test --module ds_theta_test \
    --without-debug-symbol-split --without-java-ext --gtest_filter 'DataSketchsThetaTest.*' && \
    python3 docker/dockerfiles/theta/check-tests.py be-test-reports DataSketchsThetaTest && \
    rm -rf be/ut_build_Release be/output

FROM scratch AS be-test-reports
COPY --from=be-tests /workspace/be-test-reports/ /

FROM source AS fe-build
RUN ./build.sh --fe --with-maven-batch-mode ON -j 2

FROM source AS be-build
RUN ./build.sh --be --enable-shared-data --with-maven-batch-mode ON -j 3 && \
    rm -f output/be/lib/starrocks_be.debuginfo && rm -rf be/build_Release be/output

# Preserve upstream runtime dependencies, profiling tools and Kubernetes scripts.
FROM starrocks/fe-ubuntu:4.1.7@sha256:f6dd1491f0dd9bdcaeb29fac53c49727b20603d708530f0e5156cfd501425462 AS fe
ARG SOURCE_SHA
LABEL org.opencontainers.image.source="https://github.com/mfroembgen/starrocks" \
      org.opencontainers.image.revision=${SOURCE_SHA} \
      org.opencontainers.image.version="4.1.7-theta" \
      org.starrocks.upstream.revision="b81ff9cc465a5f2e5e53104d87a59b349808abd9"
RUN rm -rf /opt/starrocks/fe/bin /opt/starrocks/fe/conf /opt/starrocks/fe/lib \
    /opt/starrocks/fe/webroot /opt/starrocks/fe/spark-dpp /opt/starrocks/fe/hive-udf
COPY --from=fe-build --chown=starrocks:starrocks /workspace/output/fe /opt/starrocks/fe
RUN javap -constants -classpath '/opt/starrocks/fe/lib/*' com.starrocks.common.Version | grep -F "${SOURCE_SHA}"

FROM starrocks/be-ubuntu:4.1.7@sha256:39f7320127c9924abb1f1ce0ba0583ab11989cbe5bd1bb6274ada6f89ba5740d AS be
ARG SOURCE_SHA
LABEL org.opencontainers.image.source="https://github.com/mfroembgen/starrocks" \
      org.opencontainers.image.revision=${SOURCE_SHA} \
      org.opencontainers.image.version="4.1.7-theta" \
      org.starrocks.upstream.revision="b81ff9cc465a5f2e5e53104d87a59b349808abd9"
RUN rm -rf /opt/starrocks/be/bin /opt/starrocks/be/conf /opt/starrocks/be/lib /opt/starrocks/be/www
COPY --from=be-build --chown=starrocks:starrocks /workspace/output/be /opt/starrocks/be
RUN /opt/starrocks/be/bin/show_be_version.sh | grep -F "${SOURCE_SHA}" && \
    test "$(readlink /opt/starrocks/cn)" = be && \
    test -x /opt/starrocks/cn/bin/start_cn.sh && test -x /opt/starrocks/cn_entrypoint.sh
