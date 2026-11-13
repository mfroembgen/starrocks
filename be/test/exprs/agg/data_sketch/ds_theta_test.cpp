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
#include "exprs/agg/data_sketch/ds_theta.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <memory>

#include "column/array_column.h"
#include "column/binary_column.h"
#include "column/column_builder.h"
#include "column/const_column.h"
#include "column/fixed_length_column.h"
#include "column/nullable_column.h"
#include "column/vectorized_fwd.h"
#include "exprs/agg/aggregate_factory.h"
#include "exprs/agg/aggregate_state_allocator.h"
#include "exprs/agg/base_aggregate_test.h"
#include "runtime/mem_pool.h"
#include "runtime/time_types.h"
#include "types/bitmap_value.h"
#include "util/slice.h"
#include "util/thrift_util.h"
#include "util/unaligned_access.h"

namespace starrocks {

class DataSketchsThetaTest : public testing::Test {
public:
    DataSketchsThetaTest() = default;

    void SetUp() override {
        utils = new FunctionUtils();
        ctx = utils->get_fn_ctx();
        _allocator = std::make_unique<CountingAllocatorWithHook>();
        tls_agg_state_allocator = _allocator.get();
    }
    void TearDown() override {
        delete utils;
        tls_agg_state_allocator = nullptr;
        _allocator.reset();
    }

private:
    FunctionUtils* utils{};
    FunctionContext* ctx{};
    std::unique_ptr<CountingAllocatorWithHook> _allocator;
};

TEST_F(DataSketchsThetaTest, TestSerializeDeserialize1) {
    int64_t memory_usage = 0;
    DataSketchesTheta theta(Slice(), &memory_usage);
    for (int i = 0; i < 100; i++) {
        theta.update(i);
    }
    uint8_t dst[1024];
    size_t size = theta.serialize(dst);
    ASSERT_EQ(size, theta.serialize_size());
    ASSERT_EQ(theta.estimate_cardinality(), 100);

    DataSketchesTheta theta2(Slice(dst, size), &memory_usage);
    ASSERT_EQ(theta2.serialize_size(), size);
    ASSERT_EQ(theta2.estimate_cardinality(), 100);
}

TEST_F(DataSketchsThetaTest, TestSerializeDeserialize2) {
    int64_t memory_usage = 0;
    DataSketchesTheta theta1(&memory_usage);
    DataSketchesTheta theta2(&memory_usage);
    for (int i = 0; i < 100; i++) {
        theta1.update(i);
        theta2.update(i);
    }
    DataSketchesTheta theta3(&memory_usage);
    theta3.merge(theta1);
    theta3.merge(theta2);

    uint8_t dst[1024];
    size_t size = theta3.serialize(dst);
    ASSERT_EQ(size, theta3.serialize_size());
    ASSERT_EQ(theta3.estimate_cardinality(), 100);

    {
        DataSketchesTheta theta4(&memory_usage);
        theta4.deserialize(Slice(dst, size));
        ASSERT_EQ(theta4.serialize_size(), size);
        ASSERT_EQ(theta4.estimate_cardinality(), 100);
    }
    {
        DataSketchesTheta theta4(Slice(dst, size), &memory_usage);
        ASSERT_EQ(theta4.serialize_size(), size);
        ASSERT_EQ(theta4.estimate_cardinality(), 100);
    }
}

TEST_F(DataSketchsThetaTest, HeaderRoundTripPreservesLgK) {
    int64_t memory_usage = 0;
    constexpr uint8_t kCustomLgK = 16;
    DataSketchesTheta theta(kCustomLgK, &memory_usage);
    for (int i = 0; i < 500; ++i) {
        theta.update(i);
    }

    uint8_t dst[8192];
    size_t size = theta.serialize(dst);
    ASSERT_GE(size, DataSketchesTheta::kHeaderSize);
    EXPECT_EQ(dst[0], DataSketchesTheta::kMagicByte);
    EXPECT_EQ(dst[1], kCustomLgK);

    DataSketchesTheta restored(&memory_usage);
    ASSERT_TRUE(restored.deserialize(Slice(dst, size)));
    EXPECT_EQ(restored.get_lg_config_k(), kCustomLgK);
    EXPECT_EQ(restored.estimate_cardinality(), 500);
}

TEST_F(DataSketchsThetaTest, LegacyBytesWithoutHeaderDeserialize) {
    int64_t memory_usage = 0;
    DataSketchesTheta source(&memory_usage);
    for (int i = 0; i < 100; ++i) {
        source.update(i);
    }
    uint8_t framed[1024];
    size_t framed_size = source.serialize(framed);
    ASSERT_GT(framed_size, DataSketchesTheta::kHeaderSize);

    const uint8_t* legacy = framed + DataSketchesTheta::kHeaderSize;
    size_t legacy_size = framed_size - DataSketchesTheta::kHeaderSize;
    ASSERT_NE(legacy[0], DataSketchesTheta::kMagicByte);

    DataSketchesTheta restored(&memory_usage);
    ASSERT_TRUE(restored.deserialize(Slice(legacy, legacy_size)));
    EXPECT_EQ(restored.estimate_cardinality(), 100);
    EXPECT_EQ(restored.get_lg_config_k(), default_theta_lg_k());
}

TEST_F(DataSketchsThetaTest, DeserializeAdoptsIncomingLgK) {
    int64_t memory_usage = 0;
    constexpr uint8_t kIncomingLgK = 16;
    DataSketchesTheta incoming(kIncomingLgK, &memory_usage);
    for (int i = 0; i < 100; ++i) {
        incoming.update(i);
    }

    uint8_t buf[2048];
    size_t size = incoming.serialize(buf);

    DataSketchesTheta parsed(Slice(buf, size), &memory_usage);
    EXPECT_EQ(parsed.get_lg_config_k(), kIncomingLgK);

    DataSketchesTheta state(parsed.get_lg_config_k(), &memory_usage);
    state.merge(parsed);
    EXPECT_EQ(state.get_lg_config_k(), kIncomingLgK);
    EXPECT_EQ(state.estimate_cardinality(), 100);
}

TEST_F(DataSketchsThetaTest, OutOfRangeLgKInHeaderFallsBackToDefault) {
    int64_t memory_usage = 0;
    DataSketchesTheta source(&memory_usage);
    for (int i = 0; i < 50; ++i) {
        source.update(i);
    }
    uint8_t framed[1024];
    size_t size = source.serialize(framed);

    framed[1] = 0xFF;

    DataSketchesTheta restored(&memory_usage);
    ASSERT_TRUE(restored.deserialize(Slice(framed, size)));
    EXPECT_EQ(restored.get_lg_config_k(), default_theta_lg_k());
    EXPECT_EQ(restored.estimate_cardinality(), 50);
}

TEST_F(DataSketchsThetaTest, EmptyStateSerializeRoundTrip) {
    int64_t memory_usage = 0;
    DataSketchesTheta empty(&memory_usage);
    EXPECT_EQ(empty.estimate_cardinality(), 0);

    uint8_t dst[16];
    size_t size = empty.serialize(dst);
    EXPECT_EQ(size, DataSketchesTheta::kHeaderSize);
    EXPECT_EQ(dst[0], DataSketchesTheta::kMagicByte);

    DataSketchesTheta restored(&memory_usage);
    ASSERT_TRUE(restored.deserialize(Slice(dst, size)));
    EXPECT_EQ(restored.estimate_cardinality(), 0);
}

TEST_F(DataSketchsThetaTest, EstimateCardinalityRoundsRatherThanTruncates) {
    constexpr uint8_t kLgK = 12;
    bool saw_a_fraction_that_rounds_up = false;

    for (int distinct_count : {10000, 25000, 50000, 100000, 250000}) {
        int64_t memory_usage = 0;
        DataSketchesTheta theta(kLgK, &memory_usage);
        for (int i = 0; i < distinct_count; ++i) {
            theta.update(i);
        }

        const double raw = theta.get_sketch_update()->get_estimate();
        ASSERT_NE(raw, std::trunc(raw)) << "estimation mode should not produce an integral estimate";
        EXPECT_EQ(theta.estimate_cardinality(), std::llround(raw)) << "at " << distinct_count;

        if (raw - std::floor(raw) >= 0.5) {
            saw_a_fraction_that_rounds_up = true;
            EXPECT_NE(theta.estimate_cardinality(), static_cast<int64_t>(raw)) << "at " << distinct_count;
        }
    }

    ASSERT_TRUE(saw_a_fraction_that_rounds_up)
            << "no sampled cardinality produced a fraction >= 0.5, so nothing here distinguishes "
               "rounding from truncation; pick different cardinalities";
}

TEST_F(DataSketchsThetaTest, DefaultLgKResolvesInsideTheAcceptedRange) {
    const uint8_t resolved = default_theta_lg_k();
    EXPECT_GE(resolved, datasketches::theta_constants::MIN_LG_K);
    EXPECT_LE(resolved, datasketches::theta_constants::MAX_LG_K);

    EXPECT_EQ(resolved, DEFAULT_THETA_LOG_K);

    int64_t memory_usage = 0;
    DataSketchesTheta theta(&memory_usage);
    EXPECT_EQ(theta.get_lg_config_k(), resolved);
}

TEST_F(DataSketchsThetaTest, MergeReachesTheSameLgKWhicheverOrderSketchesArrive) {
    int64_t memory_usage = 0;
    constexpr uint8_t kCoarseLgK = 12;
    constexpr uint8_t kFineLgK = 16;

    uint8_t coarse[8192];
    uint8_t fine[8192];
    size_t coarse_size = 0;
    size_t fine_size = 0;
    {
        DataSketchesTheta coarse_sketch(kCoarseLgK, &memory_usage);
        DataSketchesTheta fine_sketch(kFineLgK, &memory_usage);
        for (int i = 0; i < 300; ++i) {
            coarse_sketch.update(i);
            fine_sketch.update(i + 150);
        }
        coarse_size = coarse_sketch.serialize(coarse);
        fine_size = fine_sketch.serialize(fine);
    }
    ASSERT_EQ(coarse[1], kCoarseLgK);
    ASSERT_EQ(fine[1], kFineLgK);

    auto merged_lg_k = [](const uint8_t* first, size_t first_size, const uint8_t* second,
                          size_t second_size) -> uint8_t {
        std::vector<TypeDescriptor> arg_types = {TypeDescriptor::from_logical_type(TYPE_VARCHAR)};
        auto return_type = TypeDescriptor::from_logical_type(TYPE_BIGINT);
        std::unique_ptr<FunctionContext> ctx(
                FunctionContext::create_test_context(std::move(arg_types), return_type));

        const AggregateFunction* func =
                get_aggregate_function("ds_theta_count_distinct", TYPE_VARCHAR, TYPE_BIGINT, false);
        auto state = ManagedAggrState::create(ctx.get(), func);

        auto input = BinaryColumn::create();
        input->append(Slice(first, first_size));
        input->append(Slice(second, second_size));

        func->merge(ctx.get(), input.get(), state->state(), 0);
        func->merge(ctx.get(), input.get(), state->state(), 1);

        MutableColumnPtr out = BinaryColumn::create();
        func->serialize_to_column(ctx.get(), state->state(), out.get());

        Slice serialized = down_cast<BinaryColumn*>(out.get())->get_slice(0);
        EXPECT_GE(serialized.size, DataSketchesTheta::kHeaderSize);
        EXPECT_EQ(static_cast<uint8_t>(serialized.data[0]), DataSketchesTheta::kMagicByte);

        return static_cast<uint8_t>(serialized.data[1]);
    };

    EXPECT_EQ(merged_lg_k(fine, fine_size, coarse, coarse_size), kFineLgK);
    EXPECT_EQ(merged_lg_k(coarse, coarse_size, fine, fine_size), kFineLgK);
}

TEST_F(DataSketchsThetaTest, EstimateCardinalityRoundsOnTheUnionPath) {
    constexpr uint8_t kLgK = 12;
    int64_t memory_usage = 0;
    DataSketchesTheta left(kLgK, &memory_usage);
    DataSketchesTheta right(kLgK, &memory_usage);
    for (int i = 0; i < 60000; ++i) {
        left.update(i);
        right.update(i + 30000);
    }

    DataSketchesTheta merged(kLgK, &memory_usage);
    merged.merge(left);
    merged.merge(right);

    const double raw = merged.get_sketch_union()->get_result().get_estimate();
    ASSERT_NE(raw, std::trunc(raw));
    EXPECT_EQ(merged.estimate_cardinality(), std::llround(raw));
}

TEST_F(DataSketchsThetaTest, LargeExternalSketchesSurviveShuffleAtLgK14) {
    using Sketch = datasketches::update_theta_sketch;
    auto reference = datasketches::theta_union::builder().set_lg_k(14).set_seed(9001).build();
    int64_t memory_usage = 0;
    DataSketchesTheta merged(&memory_usage);
    ASSERT_EQ(merged.get_lg_config_k(), 14);

    for (int shard = 0; shard < 3; ++shard) {
        auto source = Sketch::builder().set_lg_k(14).set_seed(9001).build();
        for (int i = shard; i < 250000; i += 3) {
            source.update(std::string("user-") + std::to_string(i));
        }
        reference.update(source);
        auto bare = source.compact().serialize();
        DataSketchesTheta imported(Slice(bare.data(), bare.size()), &memory_usage);
        std::vector<uint8_t> framed(imported.serialize_size());
        ASSERT_EQ(imported.serialize(framed.data()), framed.size());
        ASSERT_GT(framed.size(), 65536);
        ASSERT_EQ(framed[0], 0xFE);
        ASSERT_EQ(framed[1], 0x0E);
        DataSketchesTheta shuffled(Slice(framed.data(), framed.size()), &memory_usage);
        merged.merge(shuffled);
    }

    std::vector<uint8_t> actual(merged.serialize_size());
    merged.serialize(actual.data());
    auto expected = reference.get_result().serialize();
    ASSERT_EQ(actual.size(), expected.size() + DataSketchesTheta::kHeaderSize);
    EXPECT_TRUE(std::equal(expected.begin(), expected.end(), actual.begin() + DataSketchesTheta::kHeaderSize));
    EXPECT_EQ(merged.estimate_cardinality(), std::llround(reference.get_result().get_estimate()));
}

TEST_F(DataSketchsThetaTest, ConstantArgumentsSerializeEveryRowAtRequestedPrecision) {
    for (LogicalType input_type : {TYPE_SMALLINT, TYPE_VARCHAR}) {
        std::vector<TypeDescriptor> arg_types = {TypeDescriptor::from_logical_type(input_type),
                                               TypeDescriptor::from_logical_type(TYPE_INT)};
        auto return_type = TypeDescriptor::from_logical_type(TYPE_BIGINT);
        std::unique_ptr<FunctionContext> context(
                FunctionContext::create_test_context(std::move(arg_types), return_type));
        const AggregateFunction* func =
                get_aggregate_function("ds_theta_count_distinct", input_type, TYPE_BIGINT, false);
        ASSERT_NE(func, nullptr);

        MutableColumnPtr value;
        if (input_type == TYPE_SMALLINT) {
            auto numeric = Int16Column::create();
            numeric->append(1);
            value = std::move(numeric);
        } else {
            auto text = BinaryColumn::create();
            text->append(Slice("constant"));
            value = std::move(text);
        }
        auto precision = Int32Column::create();
        precision->append(16);
        Columns input = {ConstColumn::create(std::move(value), 4096), ConstColumn::create(std::move(precision), 4096)};
        MutableColumnPtr serialized = BinaryColumn::create();
        func->convert_to_serialize_format(context.get(), input, 4096, serialized);
        ASSERT_EQ(serialized->size(), 4096);

        auto state = ManagedAggrState::create(context.get(), func);
        for (size_t row = 0; row < serialized->size(); ++row) {
            auto slice = down_cast<BinaryColumn*>(serialized.get())->get_slice(row);
            ASSERT_EQ(static_cast<uint8_t>(slice.data[1]), 16);
            func->merge(context.get(), serialized.get(), state->state(), row);
        }
        auto result = Int64Column::create();
        func->finalize_to_column(context.get(), state->state(), result.get());
        EXPECT_EQ(result->get_data()[0], 1);
    }
}

TEST_F(DataSketchsThetaTest, NullAndEmptyAggregatesReturnZero) {
    std::vector<TypeDescriptor> arg_types = {TypeDescriptor::from_logical_type(TYPE_VARCHAR)};
    auto return_type = TypeDescriptor::from_logical_type(TYPE_BIGINT);
    std::unique_ptr<FunctionContext> context(
            FunctionContext::create_test_context(std::move(arg_types), return_type));
    const AggregateFunction* func = get_aggregate_function("ds_theta_count_distinct", TYPE_VARCHAR, TYPE_BIGINT, true);
    auto state = ManagedAggrState::create(context.get(), func);
    auto result = Int64Column::create();
    func->finalize_to_column(context.get(), state->state(), result.get());
    EXPECT_EQ(result->get_data()[0], 0);

    auto nulls = NullableColumn::create(BinaryColumn::create(), NullColumn::create());
    nulls->append_nulls(8);
    const Column* input = nulls.get();
    func->update_batch_single_state(context.get(), nulls->size(), &input, state->state());
    func->finalize_to_column(context.get(), state->state(), result.get());
    EXPECT_EQ(result->get_data()[1], 0);
}

TEST_F(DataSketchsThetaTest, MoveRetainsSketchState) {
    int64_t memory_usage = 0;
    DataSketchesTheta source(16, &memory_usage);
    source.update(1);
    DataSketchesTheta moved(std::move(source));
    EXPECT_EQ(moved.get_lg_config_k(), 16);
    EXPECT_EQ(moved.estimate_cardinality(), 1);
}

TEST_F(DataSketchsThetaTest, RejectsTruncatedAndWrongSeedSketches) {
    int64_t memory_usage = 0;
    DataSketchesTheta imported(&memory_usage);
    for (size_t size = 1; size < 24; ++size) {
        std::vector<uint8_t> truncated(size, 0);
        truncated[0] = 3;
        EXPECT_FALSE(imported.deserialize(Slice(truncated.data(), truncated.size())));
    }
    auto wrong_seed = datasketches::update_theta_sketch::builder().set_seed(42).build();
    wrong_seed.update(1);
    auto bytes = wrong_seed.compact().serialize();
    EXPECT_FALSE(imported.deserialize(Slice(bytes.data(), bytes.size())));
    EXPECT_EQ(imported.estimate_cardinality(), 0);
}

TEST_F(DataSketchsThetaTest, MergeIgnoresMalformedRowsAndAccountsOnlyPersistentMemory) {
    int64_t memory_usage = 0;
    DataSketchesTheta source(&memory_usage);
    for (uint64_t value = 0; value < 100; ++value) {
        source.update(value);
    }
    std::vector<uint8_t> bytes(source.serialize_size());
    source.serialize(bytes.data());
    auto input = BinaryColumn::create();
    input->append(Slice(bytes.data(), bytes.size()));
    input->append(Slice("bad"));
    input->append(Slice("\xFE", 1));
    auto wrong_seed = datasketches::update_theta_sketch::builder().set_seed(42).build();
    wrong_seed.update(1);
    auto wrong_seed_bytes = wrong_seed.compact().serialize();
    input->append(Slice(wrong_seed_bytes.data(), wrong_seed_bytes.size()));

    std::vector<TypeDescriptor> arg_types = {TypeDescriptor::from_logical_type(TYPE_VARCHAR)};
    auto return_type = TypeDescriptor::from_logical_type(TYPE_BIGINT);
    std::unique_ptr<FunctionContext> context(
            FunctionContext::create_test_context(std::move(arg_types), return_type));
    const AggregateFunction* func = get_aggregate_function("ds_theta_count_distinct", TYPE_VARCHAR, TYPE_BIGINT, false);
    auto state = ManagedAggrState::create(context.get(), func);
    func->merge(context.get(), input.get(), state->state(), 0);
    const int64_t retained_memory = context->mem_usage();
    ASSERT_GT(retained_memory, 0);
    for (int repeat = 0; repeat < 100; ++repeat) {
        func->merge(context.get(), input.get(), state->state(), 0);
    }
    for (size_t row = 1; row < input->size(); ++row) {
        func->merge(context.get(), input.get(), state->state(), row);
    }
    EXPECT_EQ(context->mem_usage(), retained_memory);
    auto result = Int64Column::create();
    func->finalize_to_column(context.get(), state->state(), result.get());
    EXPECT_EQ(result->get_data()[0], 100);
    EXPECT_FALSE(context->has_error());
}

} // namespace starrocks
