---
displayed_sidebar: docs
---

# ds_theta_estimate

合并输入行中的 Theta 草图，并将去重估算值四舍五入为 BIGINT。

```sql
SELECT day, ds_theta_estimate(sketch) FROM daily_sketches GROUP BY day;
```

本分支保留聚合函数语义，每组返回一个结果。支持与 [ds_theta_combine](ds_theta_combine.md) 相同的带头部和不带头部的种子 9001 草图，包括空字节及仅含头部的空状态。NULL 行被忽略。

使用 [ds_theta_combine](ds_theta_combine.md) 保留合并后的草图，或 [ds_theta_accumulate](ds_theta_accumulate.md) 从原始值创建草图。

反序列化时被拒绝的无效、截断或种子不匹配的状态会被忽略，与现有 `ds_theta_count_distinct_merge` 的行为一致。这可能导致低估，调用方必须在导入前验证草图字节。
