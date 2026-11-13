---
displayed_sidebar: docs
---

# ds_theta_accumulate

将输入行累积为 Theta 草图，返回 VARBINARY。

```sql
SELECT ds_theta_accumulate(user_id) FROM events;
SELECT day, ds_theta_accumulate(user_id, 16) FROM events GROUP BY day;
```

可选的 `log_k` 必须是 [5, 26] 内的整数常量。默认值为 14，可通过不可动态修改的 BE/CN 配置 `theta_sketch_default_lg_k` 设置。所有节点应使用相同配置。哈希种子固定为 9001。NULL 输入行被忽略。

序列化格式为 `[0xFE][log_k]` 加 Apache DataSketches compact Theta 字节，保留跨节点及持久化状态的精度。原始输入仍使用 StarRocks 现有的预哈希方式，因此不等同于直接向 Apache DataSketches 更新原始值。

外部草图使用 [ds_theta_combine](ds_theta_combine.md) 导入；使用 [ds_theta_estimate](ds_theta_estimate.md) 合并并估算草图。
