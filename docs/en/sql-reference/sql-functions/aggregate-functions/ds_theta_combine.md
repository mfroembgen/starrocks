---
displayed_sidebar: docs
---

# ds_theta_combine

Unions serialized Theta sketches over input rows and returns one VARBINARY sketch per group.

```sql
SELECT day, ds_theta_combine(sketch) FROM daily_sketches GROUP BY day;
```

Accepts both StarRocks `[0xFE][log_k]` framing and bare Apache DataSketches compact Theta bytes with seed 9001. The output uses the StarRocks framing. Empty bytes and header-only states represent an empty sketch; NULL rows are ignored.

Invalid, truncated, or wrong-seed states rejected during deserialization are ignored, preserving the existing `ds_theta_count_distinct_merge` behavior. This can undercount; callers must validate imported sketch bytes before loading them.

All BE/CN workers participating in a query must support this fork's framed format. Stock StarRocks 4.1.6 cannot read `[0xFE][log_k]` partial or persisted states and silently ignores them, so mixed stock/fork workers can undercount. A rollback to stock requires converting or rematerializing framed stored states before they are read by stock workers.

Bare sketches use `theta_sketch_default_lg_k` (default 14) for the union. Framed sketches carry their precision; mixed framed precisions promote the union to the largest observed `log_k`. Promotion cannot recover hashes discarded by lower-precision inputs or earlier unions. Use one precision throughout a distributed workflow.

Use [ds_theta_accumulate](ds_theta_accumulate.md) to build sketches from raw values and [ds_theta_estimate](ds_theta_estimate.md) to return the rounded count of a union.
