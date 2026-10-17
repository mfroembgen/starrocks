---
displayed_sidebar: docs
---

# ds_theta_accumulate

Builds a serialized Theta sketch over input rows and returns VARBINARY.

```sql
SELECT ds_theta_accumulate(user_id) FROM events;
SELECT day, ds_theta_accumulate(user_id, 16) FROM events GROUP BY day;
```

The optional `log_k` must be a constant integer in [5, 26]. The default is 14, controlled by the immutable BE/CN setting `theta_sketch_default_lg_k`. All workers must use the same setting. Seed 9001 is fixed.

The output starts with `[0xFE][log_k]`, followed by an Apache DataSketches compact Theta sketch. The header preserves precision across shuffle and stored aggregate states. NULL input rows are ignored.

Raw values retain StarRocks' existing prehash behavior. Building a sketch from raw values is therefore different from directly updating an Apache DataSketches sketch with those values. Import externally built sketches with [ds_theta_combine](ds_theta_combine.md) instead.

Use [ds_theta_estimate](ds_theta_estimate.md) to union and estimate stored sketches, or [ds_theta_count_distinct](ds_theta_count_distinct.md) to return a count directly.
