---
displayed_sidebar: docs
---

# ds_theta_combine

合并输入行中的 Theta 草图，每组返回一个 VARBINARY 草图。

```sql
SELECT day, ds_theta_combine(sketch) FROM daily_sketches GROUP BY day;
```

支持 StarRocks `[0xFE][log_k]` 格式和种子为 9001 的 Apache DataSketches compact Theta 原始字节。返回值使用 StarRocks 格式。空字节和仅含头部的状态表示空草图；NULL 行被忽略。

反序列化时被拒绝的无效、截断或种子不匹配的状态会被忽略，与现有 `ds_theta_count_distinct_merge` 的行为一致。这可能导致低估，调用方必须在导入前验证草图字节。

参与同一查询的所有 BE/CN 节点必须支持本分支的带头部格式。原版 StarRocks 4.1.6 无法读取 `[0xFE][log_k]` 中间状态或持久化状态，并会静默忽略，因此混用原版与本分支节点可能导致低估。回滚至原版前，必须转换或重新生成带头部的持久化状态，才能由原版节点读取。

无头部的草图采用 `theta_sketch_default_lg_k`（默认 14）进行合并。带头部的草图保留精度，混合精度时提升至已观察到的最大 `log_k`。提升不能恢复低精度输入或之前的合并已丢弃的哈希值，分布式流程应统一精度。

使用 [ds_theta_accumulate](ds_theta_accumulate.md) 从原始值创建草图，或 [ds_theta_estimate](ds_theta_estimate.md) 返回合并后的整数估算值。
