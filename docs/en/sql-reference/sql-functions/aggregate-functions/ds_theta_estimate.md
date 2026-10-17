---
displayed_sidebar: docs
---

# ds_theta_estimate

Unions serialized Theta sketches over input rows and returns the estimated distinct count as BIGINT, rounded to the nearest integer.

```sql
SELECT day, ds_theta_estimate(sketch) FROM daily_sketches GROUP BY day;
```

This fork preserves the aggregate function contract: one result is returned per group. It accepts the same framed and bare seed-9001 sketch formats as [ds_theta_combine](ds_theta_combine.md), including empty bytes and header-only empty states. NULL rows are ignored.

Use [ds_theta_combine](ds_theta_combine.md) to retain the union as a sketch, or [ds_theta_accumulate](ds_theta_accumulate.md) to build sketches from raw values.

Invalid, truncated, or wrong-seed states rejected during deserialization are ignored, preserving the existing `ds_theta_count_distinct_merge` behavior. This can undercount; callers must validate imported sketch bytes before loading them.
