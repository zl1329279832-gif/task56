# 角色资源变更与 Shiro 缓存一致性运维手册

> 面向运维及二线支持人员，说明角色-资源绑定变更的正确操作流程，以及直改数据库导致权限不生效的原因与应急处理方式。

---

## 一、背景

系统基于 **JWT + Apache Shiro + Redis** 实现无状态鉴权，Session 已全局禁用。用户每次请求通过 `Authorization` 头携带 JWT，Shiro 根据 Redis 中缓存的授权信息判断权限。

角色与资源的绑定关系存储在 `sys_role_resource` 表。当该表数据发生变更时，必须同时完成以下两步，权限才能对在线用户即时生效：

1. **重载 Shiro 过滤链**（URL ↔ 权限的映射关系）
2. **清除受影响用户的 Redis 授权缓存**（强制下次请求重新从数据库加载权限）

README 中提到的「权限数据同步更新」即指通过应用层 API 完成上述两步的能力。

---

## 二、两种变更方式对比：直改数据库 vs. API 接口

### 2.1 直接修改数据库（**不推荐**）

运维通过 SQL 直接操作 `sys_role_resource` 表（INSERT / DELETE / UPDATE）。

**影响：**

| 层面 | 状态 | 说明 |
|------|------|------|
| 数据库 | 已变更 | `sys_role_resource` 行已更新 |
| Shiro 过滤链 | **未重载** | `DefaultFilterChainManager` 中仍为旧的 URL→权限映射 |
| Redis 授权缓存 | **未清除** | 受影响用户的缓存 key 仍存在，Shiro 直接返回旧权限 |
| 在线用户感知 | **无变化** | 用户持有有效 JWT（有效期约 3.5 天），请求命中 Redis 缓存中的旧授权信息，权限不会刷新 |
| 新登录用户 | 部分生效 | 新登录时会创建新的缓存条目（从数据库加载），资源绑定会体现；但若过滤链未重载，URL 级别的拦截规则仍可能不正确 |

**这就是「运维直改数据库后线上用户权限到早上还没刷新」的根本原因。**

### 2.2 通过 API 接口变更（**推荐**）

调用 `POST /system/role/apply-resource-change`，传入角色 ID 和新的资源列表。

**影响：**

| 层面 | 状态 | 说明 |
|------|------|------|
| 数据库 | 已变更 | 事务内先删后插 `sys_role_resource`，保证一致性 |
| Shiro 过滤链 | **已重载** | `reloadPerms()` 清空旧链并从数据库重建 |
| Redis 授权缓存 | **已清除** | 该角色下所有用户的缓存条目被逐一移除 |
| 在线用户感知 | **即时生效** | 下一次请求缓存未命中，Shiro 重新查库加载最新权限 |
| 审计追踪 | 有记录 | `@SysLogs` 注解记录操作日志，`CacheRefreshResultVO` 返回刷新详情 |

### 2.3 对比总结

```
直改数据库：  DB 变更 ──→ ❌ 过滤链未重载 ──→ ❌ 缓存未清除 ──→ 用户权限不刷新
API 接口：   DB 变更 ──→ ✅ 过滤链重载   ──→ ✅ 缓存清除   ──→ 用户权限即时生效
```

---

## 三、标准操作流程时序图

以下为通过 API 执行角色资源变更的完整时序，包含预览、落库、缓存刷新三个阶段。

```
┌──────────┐    ┌────────────────┐    ┌──────────────────────────────┐    ┌─────────────────┐    ┌───────────┐    ┌───────┐
│  运维/前端 │    │ RoleController │    │ RolePermissionChangeService  │    │ ShiroServiceImpl │    │  MyRealm  │    │ Redis │
└────┬─────┘    └───────┬────────┘    └──────────────┬───────────────┘    └────────┬────────┘    └─────┬─────┘    └───┬───┘
     │                  │                            │                            │                    │              │
     │  ── 阶段一：预览影响 ─────────────────────────────────────────────────────────────────────────────────────────────
     │                  │                            │                            │                    │              │
     │ POST /preview-impact                          │                            │                    │              │
     │ {roleId, resources}                           │                            │                    │              │
     │─────────────────>│                            │                            │                    │              │
     │                  │  previewImpact(roleId,     │                            │                    │              │
     │                  │    newResources)            │                            │                    │              │
     │                  │───────────────────────────>│                            │                    │              │
     │                  │                            │                            │                    │              │
     │                  │                            │ 1. 查 sys_role 验证角色存在  │                    │              │
     │                  │                            │ 2. 查 sys_role_resource     │                    │              │
     │                  │                            │    获取当前资源列表          │                    │              │
     │                  │                            │ 3. 计算资源集合差异          │                    │              │
     │                  │                            │    (added / removed)        │                    │              │
     │                  │                            │ 4. 查 sys_user_role         │                    │              │
     │                  │                            │    找出受影响用户            │                    │              │
     │                  │                            │                            │                    │              │
     │                  │     RoleChangePreviewVO     │                            │                    │              │
     │                  │<───────────────────────────│                            │                    │              │
     │  返回预览结果      │                            │                            │                    │              │
     │  (影响用户列表、    │                            │                            │                    │              │
     │   增/删资源、      │                            │                            │                    │              │
     │   增/删权限标识)    │                            │                            │                    │              │
     │<─────────────────│                            │                            │                    │              │
     │                  │                            │                            │                    │              │
     │  ── 阶段二：确认执行 ─────────────────────────────────────────────────────────────────────────────────────────────
     │                  │                            │                            │                    │              │
     │ POST /apply-resource-change                   │                            │                    │              │
     │ {roleId, resources}                           │                            │                    │              │
     │─────────────────>│                            │                            │                    │              │
     │                  │  applyChange(roleId,       │                            │                    │              │
     │                  │    newResources)            │                            │                    │              │
     │                  │───────────────────────────>│                            │                    │              │
     │                  │                            │                            │                    │              │
     │                  │                            │ ┌─ @Transactional ───────┐ │                    │              │
     │                  │                            │ │ 5. DELETE FROM          │ │                    │              │
     │                  │                            │ │   sys_role_resource     │ │                    │              │
     │                  │                            │ │   WHERE rid = roleId    │ │                    │              │
     │                  │                            │ │                         │ │                    │              │
     │                  │                            │ │ 6. INSERT INTO          │ │                    │              │
     │                  │                            │ │   sys_role_resource     │ │                    │              │
     │                  │                            │ │   (id, rid, pid)        │ │                    │              │
     │                  │                            │ │   逐条插入新资源绑定     │ │                    │              │
     │                  │                            │ └────────────────────────┘ │                    │              │
     │                  │                            │                            │                    │              │
     │                  │                            │ 7. reloadPerms()           │                    │              │
     │                  │                            │───────────────────────────>│                    │              │
     │                  │                            │                            │ 清空旧过滤链         │              │
     │                  │                            │                            │ 从数据库重建          │              │
     │                  │                            │                            │ URL→权限映射         │              │
     │                  │                            │<───────────────────────────│                    │              │
     │                  │                            │                            │                    │              │
     │                  │                            │ 8. 查 sys_user_role        │                    │              │
     │                  │                            │    收集该角色绑定的全部      │                    │              │
     │                  │                            │    用户 ID (去重)           │                    │              │
     │                  │                            │                            │                    │              │
     │                  │                            │ 9. clearAuthByUser-        │                    │              │
     │                  │                            │    IdCollection(userIds)   │                    │              │
     │                  │                            │───────────────────────────>│                    │              │
     │                  │                            │                            │ clearAuthByUser-    │              │
     │                  │                            │                            │ IdCollection()      │              │
     │                  │                            │                            │────────────────────>│              │
     │                  │                            │                            │                    │ 遍历 userIds  │
     │                  │                            │                            │                    │ 逐个 REMOVE   │
     │                  │                            │                            │                    │ 授权缓存 key   │
     │                  │                            │                            │                    │─────────────>│
     │                  │                            │                            │                    │   (per uid)  │
     │                  │                            │                            │                    │<─────────────│
     │                  │                            │                            │<────────────────────│              │
     │                  │                            │<───────────────────────────│                    │              │
     │                  │                            │                            │                    │              │
     │                  │                            │ 10. 构建 CacheRefreshResultVO                   │              │
     │                  │                            │     status = "SUCCESS"     │                    │              │
     │                  │                            │     存入内存 Map            │                    │              │
     │                  │                            │                            │                    │              │
     │                  │   CacheRefreshResultVO      │                            │                    │              │
     │                  │<───────────────────────────│                            │                    │              │
     │  返回刷新结果      │                            │                            │                    │              │
     │<─────────────────│                            │                            │                    │              │
     │                  │                            │                            │                    │              │
     │  ── 阶段三：结果查询（可选） ──────────────────────────────────────────────────────────────────────────────────────
     │                  │                            │                            │                    │              │
     │ POST /refresh-result/{roleId}                 │                            │                    │              │
     │─────────────────>│                            │                            │                    │              │
     │                  │ getLastRefreshResult(roleId)│                            │                    │              │
     │                  │───────────────────────────>│                            │                    │              │
     │                  │                            │ 从内存 Map 读取             │                    │              │
     │                  │   CacheRefreshResultVO      │                            │                    │              │
     │                  │<───────────────────────────│                            │                    │              │
     │<─────────────────│                            │                            │                    │              │
     │                  │                            │                            │                    │              │
```

---

## 四、CacheRefreshResultVO 字段说明

调用 `apply-resource-change` 和 `refresh-result/{roleId}` 接口均返回此对象。

### 4.1 字段列表

| 字段 | 类型 | 说明 |
|------|------|------|
| `roleId` | String | 变更的角色 ID |
| `roleName` | String | 角色名称 |
| `refreshedUserIds` | List\<String\> | 本次刷新涉及的用户 ID 列表 |
| `refreshedUserCount` | int | 刷新用户数量 |
| `filterChainReloaded` | boolean | Shiro 过滤链是否已重载 |
| `refreshTime` | Date | 刷新完成的时间戳 |
| `status` | String | 状态码，取值见下表 |

### 4.2 status 取值

| 状态值 | 含义 | 触发条件 | 系统行为 |
|--------|------|----------|----------|
| `SUCCESS` | 变更已生效 | 新旧资源列表存在差异（有增加或移除的资源） | 执行 DB 写入 → 重载过滤链 → 清除用户缓存 |
| `NO_IMPACT` | 无影响变更 | 新旧资源列表完全一致，无增无减 | 不写 DB、不重载过滤链、不清缓存 |

**运维判读要点：**

- 收到 `SUCCESS` → 确认 `refreshedUserCount` 与预期受影响用户数一致
- 收到 `NO_IMPACT` → 提交的资源列表与当前数据库一致，请检查是否传入了正确的资源 ID
- `filterChainReloaded = true` → URL 拦截规则已更新；若为 `false`（仅在 `NO_IMPACT` 时），说明 URL 映射未变

### 4.3 refresh-result/{roleId} 查询接口的局限性

**接口：** `POST /system/role/refresh-result/{roleId}`

该接口返回指定角色最近一次通过 `apply-resource-change` 接口触发的缓存刷新结果。需注意以下局限：

| 局限 | 说明 | 影响 |
|------|------|------|
| **内存存储** | 结果保存在 JVM 内存的 `ConcurrentHashMap` 中，而非持久化存储 | 应用重启后所有历史结果丢失，查询返回 `null` |
| **仅保留最近一次** | 每个 roleId 仅保存最后一次刷新结果，前次结果被覆盖 | 无法追溯历史变更记录 |
| **不覆盖旧路径** | 通过旧接口（如角色编辑页的保存按钮，走 `SysRoleServiceImpl.update`）触发的缓存清除不会记录到此接口 | 查询结果可能不反映全部缓存操作 |
| **不覆盖直改 DB** | 直接修改数据库不会产生任何结果记录 | 查询返回 `null` 不代表数据库未被改动 |
| **多实例问题** | 多实例部署时，结果仅存在于处理请求的那个实例 | 查询请求被负载均衡到其他实例时查不到结果 |

**建议：** 将此接口作为变更后的即时确认手段，不要作为审计依据。正式审计请查看 `@SysLogs` 记录的操作日志。

---

## 五、JWT + MyRealm 授权缓存 key 策略

### 5.1 整体架构

```
                      ┌─────────────────────────────────────────────┐
  HTTP 请求            │              Shiro 鉴权流程                  │
  Authorization: xxx   │                                             │
 ─────────────────────>│  JwtFilter                                  │
                      │    │                                         │
                      │    ├─ 提取 JWT → 构建 JwtToken               │
                      │    ├─ subject.login(token)                   │
                      │    │   └─ MyRealm.doGetAuthenticationInfo()  │
                      │    │       └─ 验证 JWT 签名，设置 uid         │
                      │    │                                         │
                      │    └─ subject.isPermitted(permission)        │
                      │        └─ 查询授权缓存                       │
                      │            │                                 │
                      │     ┌──────┴──────┐                          │
                      │     │ 缓存命中？   │                          │
                      │     └──────┬──────┘                          │
                      │       是 ↙    ↘ 否                           │
                      │   返回缓存    MyRealm.doGetAuthorizationInfo()│
                      │   中的权限    → 查库加载角色 + 资源权限        │
                      │              → 写入 Redis 缓存               │
                      │              → 返回权限                      │
                      └─────────────────────────────────────────────┘
```

### 5.2 缓存 key 的设计（关键）

Shiro 默认使用 `PrincipalCollection` 对象作为授权缓存的 key。由于本系统的 Principal 是 `JwtToken`，而同一用户每次登录产生不同的 JWT 字符串，**如果使用默认策略，同一用户的每次请求都会生成不同的缓存 key，导致缓存永远不命中**。

系统在 `MyRealm` 中覆写了 `getAuthorizationCacheKey()` 方法：

```
覆写逻辑：
  PrincipalCollection → 提取 JwtToken → 返回 jwtToken.getUid()（用户 ID 字符串）
```

**效果：**

- 同一用户无论携带哪个 JWT token，授权缓存 key 均为其用户 ID
- 不同用户的缓存 key 天然隔离
- `clearAuthByUserIdCollection(userIds)` 按用户 ID 逐个从 Redis 中移除缓存条目，与写入时的 key 完全一致

### 5.3 Redis 中的缓存结构

| 项目 | 值 |
|------|------|
| 缓存名称 | `cn.licoy.wdog.core.config.shiro.MyRealm.authorizationCache` |
| 缓存 key | 用户 ID 字符串（如 `"1"` `"1061977836183388161"`） |
| 缓存 value | `SimpleAuthorizationInfo`（含角色名集合 + 权限标识集合） |
| 存储后端 | Redis（由 `crazycake/shiro-redis` 的 `RedisCacheManager` 管理） |
| 过期策略 | 跟随 `RedisCacheManager` 默认配置 |

### 5.4 JWT 有效期与缓存的关系

| 参数 | 值 | 说明 |
|------|------|------|
| JWT 有效期 | 约 3.5 天（`7 × 12 × 3600 × 1000` ms） | 用户可在此期间内无需重新登录 |
| 授权缓存生命周期 | 跟随 Redis TTL 配置 | 缓存存在期间不会重新查库 |

**关键推论：** 如果只修改了数据库而没有清除 Redis 缓存，用户的旧权限会一直生效直到：
- Redis 缓存自然过期，**或**
- 用户的 JWT 过期后重新登录（触发新的 `doGetAuthorizationInfo`），**或**
- 运维手动清除 Redis 中对应的缓存 key

---

## 六、应急操作指南

### 场景一：已经直改了数据库，用户权限未刷新

**根因：** 过滤链未重载 + Redis 授权缓存未清除。

**处理步骤：**

1. **确认变更内容**
   - 确认哪些角色的 `sys_role_resource` 被修改
   - 查询受影响的用户：
     ```sql
     SELECT DISTINCT uid FROM sys_user_role WHERE rid = '<角色ID>';
     ```

2. **通过 API 触发缓存刷新**（推荐）
   - 调用 `POST /system/role/apply-resource-change`，传入该角色的当前完整资源列表
   - 因为数据库已经改好，传入当前数据库中的资源列表会得到 `NO_IMPACT`
   - **正确做法：** 传入任意不同的资源列表触发一次 `SUCCESS` 刷新，然后再传入正确的资源列表再触发一次
   - 或者直接在前端角色管理页面重新保存一次该角色的资源配置

3. **直接清理 Redis（备选）**
   - 如果无法使用 API，可直接操作 Redis：
     ```bash
     # 连接 Redis
     redis-cli -h <host> -p 6379 -a <password>

     # 查看受影响用户的缓存 key
     # 缓存 key 格式为用户 ID，存储在 Shiro 的授权缓存命名空间下
     # 删除指定用户的授权缓存
     # 具体 key 格式取决于 shiro-redis 版本，可先用 KEYS 命令确认
     KEYS *authorizationCache*

     # 如果确认影响范围，可清除整个授权缓存（影响所有用户，下次请求重新加载）
     # ⚠️ 注意：这会导致所有在线用户下次请求时多一次数据库查询
     ```

4. **验证**
   - 让受影响用户刷新页面，确认权限已更新
   - 调用 `POST /system/role/refresh-result/<角色ID>` 确认刷新状态（仅 API 触发时有效）

### 场景二：需要紧急回收某角色的某项权限

1. 调用 `POST /system/role/preview-impact`，传入去掉目标资源后的资源列表，确认影响范围
2. 确认无误后调用 `POST /system/role/apply-resource-change` 执行变更
3. 检查返回的 `CacheRefreshResultVO`：
   - `status = "SUCCESS"` 且 `refreshedUserCount` 符合预期 → 完成
   - `status = "NO_IMPACT"` → 资源列表未发生变化，检查传参

### 场景三：多实例部署下的过滤链不一致

`reloadPerms()` 仅在处理请求的实例上重载过滤链。多实例部署时：

- Redis 授权缓存的清除是全局生效的（所有实例共享同一 Redis）
- **但过滤链重载仅对当前实例生效**，其他实例的 `DefaultFilterChainManager` 仍持有旧的 URL 映射

**处理：** 对每个实例分别触发一次 API 调用，或执行滚动重启。

---

## 七、标准操作规范

### 7.1 变更前

- **必须** 先调用 `preview-impact` 接口确认影响范围
- **必须** 确认受影响用户列表和增减的权限标识
- **禁止** 在未经预览的情况下直接执行 `apply-resource-change`
- **禁止** 直接修改 `sys_role_resource` 表

### 7.2 变更时

- 通过 `POST /system/role/apply-resource-change` 执行变更
- 记录返回的 `CacheRefreshResultVO`（截图或保存 JSON）
- 确认 `status` 为 `SUCCESS` 且 `refreshedUserCount` 符合预期

### 7.3 变更后

- 通知受影响用户刷新页面
- 如有需要，通过 `refresh-result/{roleId}` 复查结果
- 将变更内容、影响范围、操作时间记录到运维工单

---

## 八、核心数据表关系参考

```
sys_user               sys_user_role             sys_role
┌──────────┐          ┌──────────────┐          ┌──────────┐
│ id (PK)  │◄─CASCADE─│ uid (FK)     │          │ id (PK)  │
│ username │          │ rid (FK)     │─CASCADE──►│ name     │
│ status   │          │ id (PK)      │          └──────────┘
└──────────┘          └──────────────┘               │
                                                     │
                                               ┌─────┘
                                               │
                      sys_role_resource          │         sys_resource
                      ┌──────────────┐          │         ┌──────────────┐
                      │ id (PK)      │          │         │ id (PK)      │
                      │ rid (FK)     │─CASCADE──┘         │ name         │
                      │ pid (FK)     │───────────────────►│ url          │
                      └──────────────┘                    │ permission   │
                                                          │ verification │
                                                          └──────────────┘
```

---

## 九、常见问题 FAQ

**Q: 直改数据库后重启应用能解决权限不刷新的问题吗？**
A: 可以。应用启动时会从数据库重新构建 Shiro 过滤链，且 Redis 授权缓存在首次请求时会重新加载。但生产环境重启代价较高，优先使用 API 方式。

**Q: 调用 apply-resource-change 后，用户需要重新登录吗？**
A: 不需要。缓存清除后，用户下一次请求自动触发重新鉴权，现有 JWT 仍然有效。

**Q: 一个用户绑定了多个角色，改了其中一个角色的资源，缓存会正确清除吗？**
A: 会。缓存 key 是用户 ID 而非角色 ID，清除该用户的缓存条目后，下次请求会重新加载该用户全部角色的全部权限。

**Q: refresh-result 接口查不到结果（返回 null）怎么办？**
A: 可能原因：①该角色从未通过 `apply-resource-change` 接口执行过变更；②应用重启后内存数据丢失；③多实例部署下请求路由到了其他实例。此接口仅作为即时确认手段，不作为审计依据。

**Q: NO_IMPACT 是否表示操作失败？**
A: 不是。`NO_IMPACT` 表示提交的新资源列表与数据库中当前绑定完全一致，系统判定无需执行任何变更。请检查传入的资源 ID 列表是否正确。
