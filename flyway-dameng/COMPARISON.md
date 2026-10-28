# 与 flyway-core-dm:7.1.1 的对比

对比时间：2026-10-28。参考的是本机 Maven 缓存的 `org.flywaydb:flyway-core-dm:7.1.1` 源码包，其 SHA-256 为 `08bfbdb9ec41ecb9af498a82b300722d9ad74cd252678a455e73905d1bf562db`。它是包含整个 Flyway core 的改造包，并非适配当前 8.5.11 的独立扩展。

| 项目 | 7.1.1 参考实现 | 当前独立模块及处理结论 |
| --- | --- | --- |
| 加载方式 | 在 core 的 DatabaseTypeRegister 中直接添加 DamengDatabaseType | 通过 8.5.11 的 SPI 注册独立模块，core 及其它数据库实现无需修改 |
| URL / 产品名 | `jdbc:dm`、产品名转小写后以 `dm` 开头 | 明确识别 `jdbc:dm:` 和 `DM DBMS`，使用 Locale.ROOT，避免匹配其它类型 |
| 历史表类型 | VARCHAR2、NUMBER(1) | 使用达梦原生 VARCHAR、BIT；成功/失败记录和 repair 已实测 |
| 表空间 | 根据 tablespace 拼接 Oracle 风格 TABLESPACE 子句 | 对比时发现当前模块原先遗漏该配置，现已补为原生 STORAGE(ON ...)；同时用于历史表及索引，并验证 MAIN 表空间 |
| 当前 schema | SYS_CONTEXT 查询，ALTER SESSION SET CURRENT_SCHEMA 切换 | 同样用 SYS_CONTEXT 查询，使用 SET SCHEMA 切换；自动创建、切换、恢复已实测 |
| schema 存在性 | 查询 ALL_OBJECTS 中类型为 SCH 的对象 | 查询 SYS.SYSOBJECTS 中 TYPE$='SCH' 的对象；当前实例上两种查询均能正确识别 iap |
| 空 schema 判断 | 只检查 ALL_TABLES | 检查 ALL_OBJECTS，避免把仅有视图或序列的 schema 当成空 schema；增加仅有视图的真实库用例 |
| schema 创建 | CREATE SCHEMA | 显式授权给当前登录用户；不会通过建用户创建 schema |
| schema 删除 | DROP SCHEMA，未处理当前 schema 正在使用的情况 | 先切换回登录用户 schema，再 DROP SCHEMA CASCADE；实测直接删除当前 schema 会报“当前对象被占用” |
| clean | doClean() 为空，不删除对象 | 实现常用表、视图、触发器、过程、函数、包、同义词、序列、类型清理；表、视图、过程、序列及带外键表清理有集成覆盖 |
| 表是否存在 | JDBC metadata getTables | 使用 ALL_TABLES 按 owner 和 table_name 查询 |
| 迁移互斥 | LOCK TABLE ... IN EXCLUSIVE MODE | 相同锁语句；两个并发迁移器实测只执行一次待执行迁移 |
| SQL 解析 | 直接继承通用 Parser，没有过程块规则 | 复用当前 core 的 OracleParser；同一 BEGIN/END 示例，旧解析器错误拆成 4 条，新解析器得到 2 条完整 SQL；过程、匿名块、字符串分号及 IDENTITY_INSERT 已验证 |
| SQL 执行器 | OracleSqlScriptExecutor | 通用执行器；参考包及当前 core 的 OracleSqlScriptExecutor 都只有构造器转发，没有额外执行逻辑，未发现行为遗漏 |
| 空迁移描述 | 固定返回 false，注释沿用 Oracle 空串转 NULL 的假设 | 当前默认 COMPATIBLE_MODE=0 下空串不等于 NULL，保持原生行为；空 baseline 描述、空迁移描述、validate、repair 已验证。Oracle 兼容模式的空串行为未在此实例验证 |
| 当前用户 | SELECT USER FROM DUAL | 使用 JDBC metadata 的登录用户名；schema 授权及 installed_by 已验证 |
| 版本范围 | ensureSupported 为空 | 当前模块声明 DM8，检查最低 8.0 |
| 会话标签 | 设置 v$session.osuser、v$session.program | 可选诊断标签，当前未添加；不影响数据库识别和迁移执行 |

当前实现的 JDBC 版本识别仍依赖驱动正确返回 metadata。已经发现的 8.2.2.70 驱动异常发生在 core 选择数据库实现之前，这个对比没有改变该结论；真实库验证使用 metadata 正常的 8.1.2.192。

本次对比只补齐经过验证的差异，没有用旧版本替换当前实现。测试使用随机临时 schema，不清理应用 schema。
