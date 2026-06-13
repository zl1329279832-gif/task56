# 角色资源变更与 Shiro 缓存一致性运维手册

> 适用项目：watchdog-framework（Spring Boot 1.5 + Shiro 1.3 + Redis + JWT 无状态架构）
>
> 面向读者：运维工程师、二线技术支持
>
> 最后更新：2026-06-13

---

## 目录

1. [问题背景](#1-问题背景)
2. [系统架构速览](#2-系统架构速览)
3. [两种变更方式对比：直改数据库 vs. 调用 API](#3-两种变更方式对比直改数据库-vs-调用-api)
4. [apply-resource-change 完整时序图](#4-apply-resource-change-完整时序图)
5. [CacheRefreshResultVO 状态说明](#5-cacherefreshresultvo-状态说明)
6. [refresh-result/{roleId} 查询接口局限性](#6-refresh-resultroleid-查询接口局限性)
7. [JWT + MyRealm 授权缓存 key 策略](#7-jwt--myrealm-授权缓存-key-策略)
8. [Redis 缓存 key 速查](#8-redis-缓存-key-速查)
9. [应急处理与补救操作](#9-应急处理与补救操作)
10. [常见问题 FAQ](#10-常见问题-faq)

---

## 1. 问题背景

### 现象

运维人员在数据库中直接修改了 `sys_role_resource` 表（增删角色与资源的绑定关系），但线上部分用户的权限直到次日早晨仍未生效。

### 根因

本系统采用 **Shiro + Redis 授权缓存 + JWT 无状态会话** 架构。用户首次请求时，Shiro 从数据库加载该用户所有角色及其关联的资源/权限，并缓存到 Redis。后续请求直接读取缓存，**不再查询数据库**。

直接修改 `sys_role_resource` 表只改变了数据库中的数据，**不会触发以下两个关键操作**：

1. **Shiro Filter Chain 重载** — URL 与权限过滤规则的映射不会刷新
2. **用户授权缓存清除** — Redis 中已缓存的旧权限数据不会被清理

因此，已登录用户会一直使用旧缓存，直到缓存过期或手动清除。

> **结论：生产环境禁止直接修改 `sys_role_resource` 表。所有角色资源变更必须通过 `RoleController.applyResourceChange` 接口执行。**

---

## 2. 系统架构速览

```
┌──────────┐     JWT Token      ┌──────────────┐
│  客户端   │ ──────────────────▶│  JwtFilter   │
└──────────┘                    └──────┬───────┘
                                       │
                          ┌────────────▼────────────┐
                          │    MyRealm              │
                          │  ① 认证：验证 JWT 签名   │
                          │  ② 授权：查 Redis 缓存   │
                          │     缓存命中 → 直接用     │
                          │     缓存未命中 → 查 DB    │
                          └────────────┬────────────┘
                                       │
                 ┌─────────────────────┼─────────────────────┐
                 │                     │                     │
          ┌──────▼──────┐     ┌───────▼───────┐     ┌──────▼──────┐
          │  sys_user    │     │ sys_user_role  │     │ sys_resource│
          │  sys_role    │     │sys_role_resource│    │             │
          └─────────────┘     └───────────────┘     └─────────────┘

          ┌────────────────────────────────────────────────────────┐
          │  Redis                                                 │
          │  shiro:cache:...MyRealm.authorizationCache:{userId}    │
          │  → 缓存该用户所有角色 + 权限字符串                       │
          └────────────────────────────────────────────────────────┘
```

**关键特性：**

| 项目 | 说明 |
|------|------|
| 会话策略 | **无状态**，`AgileSubjectFactory` 禁用了 Session 创建 |
| 认证方式 | JWT（auth0），有效期 7 天，签名密钥为用户 MD5 密码哈希 |
| 授权缓存 | Redis（shiro-redis），key 为用户 ID（`uid`） |
| Filter Chain | 启动时从 `sys_resource` 表构建，运行期需手动触发 `reloadPerms()` |

---

## 3. 两种变更方式对比：直改数据库 vs. 调用 API

| 维度 | 直改数据库 | 调用 `apply-resource-change` API |
|------|-----------|----------------------------------|
| **操作方式** | 手动 `INSERT/DELETE/UPDATE sys_role_resource` | `POST /system/role/apply-resource-change` |
| **数据库变更** | 仅修改 `sys_role_resource` | 全量替换：先 `DELETE WHERE rid=?`，再逐条 `INSERT` |
| **事务保护** | 取决于运维手动控制 | `@Transactional`，原子操作 |
| **Shiro Filter Chain 重载** | **不会触发** | **自动触发** `reloadPerms()` |
| **受影响用户授权缓存清除** | **不会清除** | **自动清除**所有绑定该角色的用户缓存 |
| **变更预览** | 无 | 可先调 `preview-impact` 查看影响范围 |
| **变更结果可查** | 无 | 返回 `CacheRefreshResultVO`，可事后查询 |
| **在线用户权限生效时间** | **不生效**（直到缓存过期或手动清缓存） | **下次请求立即生效**（缓存已清除，重新从 DB 加载） |
| **对用户当前请求的影响** | 无感知，继续使用旧权限 | 当前请求可能因缓存清除而触发一次 DB 查询（轻微延迟） |
| **风险等级** | **高** — 数据与缓存不一致，权限延迟生效 | **低** — 数据与缓存保持一致 |

### 直改数据库的危害场景

```
时间线：
  T0  运维直接在 DB 中为角色 A 新增资源 X
  T1  用户 U（拥有角色 A）发起请求
  T2  Shiro 查 Redis 缓存 → 命中 → 缓存中无资源 X → 权限拒绝
  T3  用户 U 持续被拒绝，直到缓存过期或重启服务
```

---

## 4. apply-resource-change 完整时序图

以下为调用 `POST /system/role/apply-resource-change` 后的完整执行流程：

```
Client                   RoleController          RolePermissionChangeService       ShiroService              MyRealm                  Redis                   Database
  │                           │                           │                           │                        │                      │                        │
  │ POST /apply-resource-     │                           │                           │                        │                      │                        │
  │ change {roleId,resources} │                           │                           │                        │                      │                        │
  │──────────────────────────▶│                           │                           │                        │                      │                        │
  │                           │                           │                           │                        │                      │                        │
  │                           │ applyChange(roleId,       │                           │                        │                      │                        │
  │                           │   newResources)           │                           │                        │                      │                        │
  │                           │──────────────────────────▶│                           │                        │                      │                        │
  │                           │                           │                           │                        │                      │                        │
  │                           │                           │  ┌─── previewImpact() ───┐│                        │                      │                        │
  │                           │                           │  │                        ││                        │                      │                        │
  │                           │                           │  │ SELECT * FROM sys_role ││                        │                      │ SELECT sys_role        │
  │                           │                           │  │────────────────────────┼──────────────────────────────────────────────────────────────────────▶│
  │                           │                           │  │◀───────────────────────┼───────────────────────────────────────────────────────────────────────│
  │                           │                           │  │                        ││                        │                      │                        │
  │                           │                           │  │ SELECT sys_role_resource WHERE rid=?              │                      │ SELECT sys_role_resource│
  │                           │                           │  │────────────────────────┼──────────────────────────────────────────────────────────────────────▶│
  │                           │                           │  │◀───────────────────────┼───────────────────────────────────────────────────────────────────────│
  │                           │                           │  │                        ││                        │                      │                        │
  │                           │                           │  │ SELECT sys_resource WHERE id IN (...)             │                      │ SELECT sys_resource    │
  │                           │                           │  │────────────────────────┼──────────────────────────────────────────────────────────────────────▶│
  │                           │                           │  │◀───────────────────────┼───────────────────────────────────────────────────────────────────────│
  │                           │                           │  │                        ││                        │                      │                        │
  │                           │                           │  │ 计算差集：added / removed                       │                      │                        │
  │                           │                           │  │                        ││                        │                      │                        │
  │                           │                           │  │ SELECT sys_user_role WHERE rid=?                 │                      │ SELECT sys_user_role   │
  │                           │                           │  │────────────────────────┼──────────────────────────────────────────────────────────────────────▶│
  │                           │                           │  │◀───────────────────────┼───────────────────────────────────────────────────────────────────────│
  │                           │                           │  │                        ││                        │                      │                        │
  │                           │                           │  │ SELECT sys_user WHERE id IN (affected uids)      │                      │ SELECT sys_user        │
  │                           │                           │  │────────────────────────┼──────────────────────────────────────────────────────────────────────▶│
  │                           │                           │  │◀───────────────────────┼───────────────────────────────────────────────────────────────────────│
  │                           │                           │  └────────────────────────┘│                        │                      │                        │
  │                           │                           │                           │                        │                      │                        │
  │                           │                           │ 判断 hasChange             │                        │                      │                        │
  │                           │                           │ (added 或 removed 非空？)  │                        │                      │                        │
  │                           │                           │                           │                        │                      │                        │
  │                     ┌─────┴── 无变更 ──┐              │                           │                        │                      │                        │
  │                     │                  │              │                           │                        │                      │                        │
  │                     │ 返回 NO_IMPACT   │              │                           │                        │                      │                        │
  │                     │ (不做任何操作)    │              │                           │                        │                      │                        │
  │                     └──────────────────┘              │                           │                        │                      │                        │
  │                           │                           │                           │                        │                      │                        │
  │                     ┌─────┴── 有变更 ──────────────────────────────────────────────────────────────────────────────────────────────────────────────┐      │
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     │  ┌── @Transactional ──────┼─────┼───────────────────────────────────────────────────────────────────────────────────────────┐│      │
  │                     │  │                        │     │                           │                        │                      │                 ││      │
  │                     │  │ Step A: 替换 DB 绑定    │     │                           │                        │                      │                 ││      │
  │                     │  │                        │     │ DELETE FROM sys_role_resource WHERE rid=?           │                      │                 ││      │
  │                     │  │                        │     │───────────────────────────┼────────────────────────────────────────────────────────────────┼┼─────▶│
  │                     │  │                        │     │◀──────────────────────────┼────────────────────────────────────────────────────────────────┼┼──────│
  │                     │  │                        │     │                           │                        │                      │                 ││      │
  │                     │  │                        │     │ for each newResource:     │                        │                      │                 ││      │
  │                     │  │                        │     │   INSERT INTO sys_role_resource (id, rid, pid)     │                      │                 ││      │
  │                     │  │                        │     │───────────────────────────┼────────────────────────────────────────────────────────────────┼┼─────▶│
  │                     │  │                        │     │◀──────────────────────────┼────────────────────────────────────────────────────────────────┼┼──────│
  │                     │  │                        │     │                           │                        │                      │                 ││      │
  │                     │  └────────────────────────┼─────┼───────────────────────────────────────────────────────────────────────────────────────────┘│      │
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     │  Step B: 重载 Filter Chain│     │                           │                        │                      │                   │      │
  │                     │                           │     │ reloadPerms()             │                        │                      │                   │      │
  │                     │                           │     │──────────────────────────▶│                        │                      │                   │      │
  │                     │                           │     │                           │ 清空所有 FilterChain    │                      │                   │      │
  │                     │                           │     │                           │ 清空 filterChainDefinitionMap                 │                   │      │
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     │                           │     │                           │ SELECT * FROM sys_resource                    │  SELECT sys_resource│      │
  │                     │                           │     │                           │────────────────────────┼──────────────────────────────────────────────▶│
  │                     │                           │     │                           │◀───────────────────────┼───────────────────────────────────────────────│
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     │                           │     │                           │ 重建 filterChain：      │                      │                   │      │
  │                     │                           │     │                           │ url/** → perms[xxx:*]  │                      │                   │      │
  │                     │                           │     │                           │ url/** → anon          │                      │                   │      │
  │                     │                           │     │◀──────────────────────────│                        │                      │                   │      │
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     │  Step C: 清除用户授权缓存  │     │                           │                        │                      │                   │      │
  │                     │                           │     │ clearAuthByUserIdCollection(userIds, true, false)   │                      │                   │      │
  │                     │                           │     │──────────────────────────▶│                        │                      │                   │      │
  │                     │                           │     │                           │ clearAuthByUserIdCollection(userIds,...)       │                   │      │
  │                     │                           │     │                           │───────────────────────▶│                      │                   │      │
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     │                           │     │                           │                        │ for each userId:     │                   │      │
  │                     │                           │     │                           │                        │   cache.remove(uid)  │                   │      │
  │                     │                           │     │                           │                        │─────────────────────▶│                   │      │
  │                     │                           │     │                           │                        │                      │ DEL shiro:cache: │      │
  │                     │                           │     │                           │                        │                      │ ...authCache:{uid}│      │
  │                     │                           │     │                           │                        │◀─────────────────────│                   │      │
  │                     │                           │     │                           │◀───────────────────────│                      │                   │      │
  │                     │                           │     │◀──────────────────────────│                        │                      │                   │      │
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     │  Step D: 构建并缓存结果    │     │                           │                        │                      │                   │      │
  │                     │  CacheRefreshResultVO     │     │                           │                        │                      │                   │      │
  │                     │  status = "SUCCESS"       │     │                           │                        │                      │                   │      │
  │                     │                           │     │                           │                        │                      │                   │      │
  │                     └───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘      │
  │◀────────────────────│                           │                           │                        │                      │                        │
  │  {status:"SUCCESS", │                           │                           │                        │                      │                        │
  │   refreshedUserCount:N,                         │                           │                        │                      │                        │
  │   filterChainReloaded:true}                     │                           │                        │                      │                        │
  │                           │                           │                           │                        │                      │                        │
```

### 受影响用户下次请求时

```
Client                   JwtFilter               MyRealm                    Redis                    Database
  │ (携带旧 JWT)           │                        │                         │                        │
  │──────────────────────▶│                        │                         │                        │
  │                       │ executeLogin()         │                         │                        │
  │                       │ Subject.login(JwtToken)│                         │                        │
  │                       │───────────────────────▶│                         │                        │
  │                       │                        │ doGetAuthenticationInfo()                        │
  │                       │                        │ 验证 JWT 签名            │                        │
  │                       │                        │─────────────────────────────────────────────────▶│
  │                       │                        │◀──────────────────────────────────────────────────│
  │                       │                        │                         │                        │
  │                       │ isPermitted("xxx")     │                         │                        │
  │                       │───────────────────────▶│                         │                        │
  │                       │                        │ cache.get(userId)       │                        │
  │                       │                        │────────────────────────▶│                        │
  │                       │                        │◀─────── null (已清除) ──│                        │
  │                       │                        │                         │                        │
  │                       │                        │ doGetAuthorizationInfo()│                        │
  │                       │                        │ 重新查 DB 加载所有角色+权限                       │
  │                       │                        │─────────────────────────────────────────────────▶│
  │                       │                        │◀──────────────────────────────────────────────────│
  │                       │                        │                         │                        │
  │                       │                        │ cache.put(userId, info) │                        │
  │                       │                        │────────────────────────▶│                        │
  │                       │                        │                         │                        │
  │                       │◀─── 权限判定结果 ──────│                         │                        │
  │◀────── 响应 ──────────│                        │                         │                        │
```

---

## 5. CacheRefreshResultVO 状态说明

### 返回结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `roleId` | String | 变更的角色 ID |
| `roleName` | String | 角色名称 |
| `refreshedUserIds` | List\<String\> | 被清除缓存的用户 ID 列表 |
| `refreshedUserCount` | int | 被清除缓存的用户数量 |
| `filterChainReloaded` | boolean | Shiro Filter Chain 是否已重载 |
| `refreshTime` | Date | 刷新时间戳 |
| `status` | String | 状态码：`SUCCESS` 或 `NO_IMPACT` |

### status 值含义

#### `NO_IMPACT` — 无实际变更

**触发条件：** 提交的新资源集合与数据库中当前资源集合完全一致，没有新增也没有删除任何资源。

**系统行为：**
- 不执行任何数据库写操作
- 不重载 Filter Chain
- 不清除任何用户缓存
- `refreshedUserCount = 0`，`filterChainReloaded = false`

**运维意义：** 如果你预期做了变更但返回 `NO_IMPACT`，说明提交的资源列表与现有一致。需检查：
- 提交的角色 ID 是否正确
- 提交的资源列表是否确实包含了变更
- 是否有前端传参错误

#### `SUCCESS` — 变更成功

**触发条件：** 新资源集合与当前资源集合存在差异（有新增或移除的资源）。

**系统行为：**
- 全量替换 `sys_role_resource`（先 DELETE 后 INSERT）
- 重载 Shiro Filter Chain
- 清除所有绑定该角色的用户的 Redis 授权缓存
- 返回受影响的用户列表和数量

**运维意义：**
- `refreshedUserCount` 告诉你有多少在线用户的权限缓存被清除
- 这些用户的下一次请求会触发一次 DB 查询来重建缓存（可能略有延迟）
- 如果 `refreshedUserCount = 0` 但 `status = SUCCESS`，说明该角色当前没有绑定任何用户，Filter Chain 仍然会被重载

---

## 6. refresh-result/{roleId} 查询接口局限性

### 接口信息

```
POST /system/role/refresh-result/{roleId}
```

返回指定角色上一次 `applyChange` 操作的结果。

### 局限性

| 局限 | 说明 |
|------|------|
| **仅存于内存** | 结果存储在 `ConcurrentHashMap<String, CacheRefreshResultVO>` 中，进程级别。服务重启后所有记录丢失。 |
| **仅保留最近一次** | 每个 `roleId` 只保留最后一次 `applyChange` 的结果。多次变更会覆盖之前的记录。 |
| **无持久化** | 不写入数据库，不写入 Redis。 |
| **无跨实例同步** | 多实例部署时，只能查询到发起变更的那个实例上的结果。其他实例返回 `null`。 |
| **首次调用返回 null** | 如果服务启动后尚未对该角色执行过 `applyChange`，接口返回 `null`（不是报错）。 |
| **不反映缓存实际状态** | 返回的结果是变更时刻的快照，不代表当前 Redis 缓存的实际状态。如果用户在缓存清除后又发起了请求，缓存已经重建，但查询结果不会更新。 |

### 建议

- 此接口仅用于变更后的**即时确认**，不作为审计依据。
- 如需审计角色权限变更历史，应查看系统操作日志（`@SysLogs` 注解记录的日志）或数据库 binlog。
- 不要依赖此接口判断"用户当前权限是否已生效"。

---

## 7. JWT + MyRealm 授权缓存 key 策略

### 认证流程（每次请求）

```
请求 Authorization: Bearer <JWT字符串>
         │
         ▼
  JwtFilter.executeLogin()
         │
         ▼
  MyRealm.doGetAuthenticationInfo()
    ① 从 JWT 中解码 username
    ② SELECT id,username,status,password FROM sys_user WHERE username=?
    ③ 校验用户存在且 status=1（未锁定）
    ④ JwtUtil.verify(token, username, passwordHash) — 验证 JWT 签名
    ⑤ 将 user.id 写入 JwtToken.uid  ← 这是授权缓存 key 的来源
         │
         ▼
  Subject.login() 成功，Principal = JwtToken 对象
```

### 授权缓存 key 的生成

```java
// MyRealm.getAuthorizationCacheKey()
protected Object getAuthorizationCacheKey(PrincipalCollection principals) {
    JwtToken jwtToken = new JwtToken();
    BeanUtils.copyProperties(principals.getPrimaryPrincipal(), jwtToken);
    return jwtToken.getUid();  // ← 返回的是 user.id（数据库主键）
}
```

**关键设计：缓存 key = `sys_user.id`（用户 ID 字符串），而非 JWT 字符串。**

这意味着：

| 特性 | 影响 |
|------|------|
| 同一用户多个 JWT | 共享同一个缓存条目（因为 key 是 userId，不是 token） |
| 按 userId 清缓存 | 能正确清除该用户的所有授权缓存 |
| 用户修改密码后 | JWT 签名密钥变化（密钥 = MD5 密码哈希），旧 JWT 验证失败，但缓存 key 不变 |
| 用户被锁定后 | `doGetAuthenticationInfo` 阶段就会拒绝（status != 1），不会走到授权缓存 |

### 授权缓存生命周期

```
首次请求 ──▶ cache MISS ──▶ doGetAuthorizationInfo()
                                │
                                ├─ SELECT sys_user_role WHERE uid=?
                                ├─ SELECT sys_role WHERE id=?
                                ├─ SELECT sys_role_resource WHERE rid=?
                                ├─ SELECT sys_resource WHERE id IN (...)
                                │
                                └─▶ 构建 SimpleAuthorizationInfo
                                     (所有角色名 + 权限字符串)
                                        │
                                        ▼
                                  cache.put(userId, authzInfo)
                                        │
                                        ▼
后续请求 ──▶ cache HIT ──▶ 直接使用缓存的 AuthorizationInfo
                                        │
                                        ▼
              （直到 cache.remove(userId) 被调用或 Redis key 过期）
```

### 缓存清除触发点

以下操作会调用 `clearAuthByUserId` / `clearAuthByUserIdCollection`：

| 触发操作 | 触发位置 | 清除范围 |
|---------|---------|---------|
| 角色资源变更 (`applyChange`) | `RolePermissionChangeServiceImpl` | 该角色绑定的所有用户 |
| 角色删除 | `SysRoleServiceImpl.remove()` | 该角色绑定的所有用户 |
| 角色名称/资源更新 | `SysRoleServiceImpl.update()` | 该角色绑定的所有用户 |
| 用户锁定/解锁 | `SysUserServiceImpl.statusChange()` | 该用户 |
| 用户删除 | `SysUserServiceImpl.removeUser()` | 该用户 |
| 用户信息更新（含角色变更） | `SysUserServiceImpl.update()` | 该用户 |
| 用户密码重置 | `SysUserServiceImpl.resetPassword()` | 该用户 |

---

## 8. Redis 缓存 key 速查

### 授权缓存

```
key:    shiro:cache:cn.licoy.wdog.core.config.shiro.MyRealm.authorizationCache:{userId}
type:   Hash / Serialized Object
value:  SimpleAuthorizationInfo（包含角色名集合 + 权限字符串集合）
```

> 前缀 `shiro:cache:` 取决于 `RedisCacheManager` 配置。可通过 Redis CLI 搜索确认实际前缀：
> ```bash
> redis-cli KEYS "*authorizationCache*"
> ```

### 查看某用户当前缓存的权限

```bash
# 先找到用户的 ID
mysql -e "SELECT id,username FROM sys_user WHERE username='目标用户名';"

# 查看 Redis 中是否存在缓存
redis-cli GET "shiro:cache:cn.licoy.wdog.core.config.shiro.MyRealm.authorizationCache:{userId}"
```

### 手动清除某用户授权缓存

```bash
redis-cli DEL "shiro:cache:cn.licoy.wdog.core.config.shiro.MyRealm.authorizationCache:{userId}"
```

### 清除所有授权缓存（谨慎操作）

```bash
redis-cli KEYS "shiro:cache:*authorizationCache*" | xargs redis-cli DEL
```

---

## 9. 应急处理与补救操作

### 场景 A：已经直改了 `sys_role_resource`，如何补救

#### 方法一：调用 API 重新触发缓存刷新（推荐）

1. 查询目标角色当前的资源绑定（确认数据库已改好）：
   ```sql
   SELECT rr.rid, rr.pid, r.name, r.permission
   FROM sys_role_resource rr
   JOIN sys_resource r ON rr.pid = r.id
   WHERE rr.rid = '目标角色ID';
   ```

2. 构造请求体，将查到的资源 ID 列表提交给 `apply-resource-change`：
   ```bash
   curl -X POST http://<host>:<port>/system/role/apply-resource-change \
     -H "Authorization: Bearer <管理员JWT>" \
     -H "Content-Type: application/json" \
     -d '{
       "roleId": "目标角色ID",
       "resources": [
         {"id": "资源ID1"},
         {"id": "资源ID2"}
       ]
     }'
   ```

3. 确认返回 `status: "SUCCESS"`，受影响用户的缓存会被自动清除。

#### 方法二：手动清除 Redis 缓存

如果无法调用 API（如服务不可用），可直接操作 Redis：

1. 找到受此角色影响的所有用户：
   ```sql
   SELECT DISTINCT ur.uid, u.username
   FROM sys_user_role ur
   JOIN sys_user u ON ur.uid = u.id
   WHERE ur.rid = '目标角色ID';
   ```

2. 逐个清除缓存：
   ```bash
   for uid in uid1 uid2 uid3; do
     redis-cli DEL "shiro:cache:cn.licoy.wdog.core.config.shiro.MyRealm.authorizationCache:$uid"
   done
   ```

3. **注意：** 还需要重载 Filter Chain。如果只清缓存不重载 Filter Chain，URL 级别的权限过滤规则可能与数据库不一致。重载 Filter Chain 只能通过 API 或重启服务实现。

#### 方法三：重启服务（最后手段）

重启应用会导致：
- 所有内存状态丢失（包括 `refreshResultMap`）
- Shiro Filter Chain 在启动时从数据库重新构建
- Redis 中的授权缓存**不会**被自动清除

因此，重启后还需要清除 Redis 中的旧授权缓存（方法二步骤 2），否则用户仍然会使用旧缓存直到过期。

### 场景 B：紧急撤销某个资源的授权

1. 通过 API 操作（推荐）：
   - 找到持有该资源的所有角色
   - 对每个角色调用 `apply-resource-change`，在资源列表中排除要撤销的资源

2. 如果资源 URL 需要立即阻断所有访问：
   - 在 Nginx / 网关层临时拦截该 URL
   - 然后通过 API 逐步清理权限

### 场景 C：需要确认用户权限是否已生效

1. 检查 Redis 缓存是否存在：
   ```bash
   redis-cli EXISTS "shiro:cache:cn.licoy.wdog.core.config.shiro.MyRealm.authorizationCache:{userId}"
   ```
   - 返回 `0` → 缓存不存在 → 用户下次请求会从 DB 重新加载（权限将生效）
   - 返回 `1` → 缓存存在 → 可能仍是旧数据

2. 如果需要确保新权限生效，手动 DEL 该 key，用户的下一次请求会触发重新加载。

---

## 10. 常见问题 FAQ

### Q1：为什么不能像以前一样直改数据库？

**A：** 本系统使用 Redis 缓存用户的授权信息。Shiro 的 `doGetAuthorizationInfo` 只在缓存未命中时查询数据库。直改数据库不会清除缓存，导致用户继续使用旧的权限数据。

### Q2：用户的授权缓存多久过期？

**A：** 取决于 Redis 缓存管理器中配置的 TTL。如果没有配置过期时间（默认行为），缓存将一直存在直到被显式清除或 Redis 实例重启。**不要依赖 TTL 来自动同步权限变更。**

### Q3：`apply-resource-change` 是增量更新还是全量替换？

**A：** **全量替换。** 系统会先 `DELETE FROM sys_role_resource WHERE rid=?` 删除该角色的所有资源绑定，然后逐条 INSERT 新的绑定。因此请求体中的 `resources` 列表必须是**完整的目标资源列表**，而非增量差异。

### Q4：调用 API 时在线用户会被踢下线吗？

**A：** 不会。系统只是清除了 Redis 中的授权缓存，不会使 JWT 失效。用户的下一次请求会触发缓存重建（从 DB 重新加载权限），期间可能有几十毫秒的额外延迟，但不会被踢下线。

### Q5：多实例部署时 `apply-resource-change` 能清除所有实例的缓存吗？

**A：** 能。授权缓存存储在 Redis（共享），`cache.remove(userId)` 操作直接删除 Redis key，所有实例都能看到缓存已被清除。但 `refreshResultMap`（查询结果）是进程内存中的 `ConcurrentHashMap`，只能在做变更的那个实例上查到。

### Q6：`preview-impact` 会修改数据吗？

**A：** 不会。`preview-impact` 是只读操作，仅计算变更的影响范围（新增/移除的资源、受影响的用户），不修改数据库也不清除缓存。建议在正式变更前先调用此接口确认影响范围。

### Q7：Filter Chain 重载会影响所有在线用户吗？

**A：** 会。`reloadPerms()` 清空并重建 Shiro 的 URL 过滤链，影响所有后续请求的 URL 级别权限判定。这是一个全局操作，与用户缓存清除不同（缓存清除只影响特定用户）。重载过程中正在执行的请求不受影响（Java 引用切换，无锁）。

### Q8：我改了 `sys_resource` 表（如修改了 URL 或 permission），需要做什么？

**A：** 修改 `sys_resource` 表后需要：
1. 调用 `reloadPerms()` 重载 Filter Chain（目前只能通过触发任意角色的 `apply-resource-change` 间接实现，或重启服务）
2. 如果修改影响到了权限字符串（`permission` 字段），还需要清除相关用户的授权缓存

### Q9：JWT 有效期是多久？用户密码改了 JWT 还有效吗？

**A：** JWT 有效期 7 天。JWT 的签名密钥是用户的 MD5 密码哈希，因此修改密码后旧 JWT 签名验证失败，用户必须重新登录获取新 JWT。

---

> **总结：生产环境所有角色资源变更，必须走 `apply-resource-change` 接口。直改数据库 = 权限不同步 = 线上事故。**
