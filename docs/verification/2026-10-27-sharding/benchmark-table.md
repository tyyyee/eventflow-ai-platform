| 合成订单数 | 负载 | 布局 | 操作/秒（三轮中位数） | P95 ms（三轮中位数） |
|---|---|---|---:|---:|
| 100,000 | normal | single | 15063.2 | 0.738 |
| 100,000 | normal | same-instance-32 | 16378.1 | 0.625 |
| 100,000 | normal | two-instance-32 | 7752.5 | 1.383 |
| 100,000 | mixed-10pct-heavy | single | 8083.0 | 2.125 |
| 100,000 | mixed-10pct-heavy | same-instance-32 | 2346.9 | 21.084 |
| 100,000 | mixed-10pct-heavy | two-instance-32 | 5065.7 | 3.503 |
| 1,000,000 | normal | single | 3546.1 | 3.417 |
| 1,000,000 | normal | same-instance-32 | 2035.4 | 5.911 |
| 1,000,000 | normal | two-instance-32 | 6551.3 | 1.615 |
| 1,000,000 | mixed-10pct-heavy | single | 1920.1 | 19.275 |
| 1,000,000 | mixed-10pct-heavy | same-instance-32 | 1468.9 | 24.411 |
| 1,000,000 | mixed-10pct-heavy | two-instance-32 | 1954.6 | 18.524 |
