package cn.licoy.wdog.core.config.shiro;

import cn.licoy.wdog.core.config.jwt.JwtToken;
import cn.licoy.wdog.core.entity.system.SysResource;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.entity.system.SysUser;
import cn.licoy.wdog.core.service.system.SysUserService;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * MyRealm 缓存 key 策略与权限判定测试
 * 此测试类与 MyRealm 在同一包下，可访问 protected 方法
 */
@RunWith(MockitoJUnitRunner.class)
public class MyRealmCacheKeyTest {

    @InjectMocks
    private MyRealm myRealm;

    @Mock
    private SysUserService userService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache<Object, Object> authzCache;

    @Before
    public void setUp() throws Exception {
        when(cacheManager.getCache(MyRealm.class.getName() + ".authorizationCache"))
                .thenReturn(authzCache);
        // MyRealm 的 cacheManager 字段遮蔽了 CachingRealm 的同名父类字段，
        // @InjectMocks 可能无法正确注入，因此使用反射显式设置
        java.lang.reflect.Field field = MyRealm.class.getDeclaredField("cacheManager");
        field.setAccessible(true);
        field.set(myRealm, cacheManager);
    }

    /**
     * 核心验证：getAuthorizationCacheKey 应返回 username 而非 JwtToken 对象
     */
    @Test
    public void getAuthorizationCacheKey_shouldReturnUsername() {
        JwtToken token = new JwtToken("some-jwt-token", "alice", null);
        SimplePrincipalCollection principals = new SimplePrincipalCollection();
        principals.add(token, "testRealm");

        Object cacheKey = myRealm.getAuthorizationCacheKey(principals);

        assertEquals("缓存 key 应为 username", "alice", cacheKey);
        assertNotEquals("缓存 key 不应为 JwtToken 对象", token, cacheKey);
    }

    /**
     * 核心验证：clearAuthByUserId 应使用 username 调用 cache.remove
     */
    @Test
    public void clearAuthByUserId_shouldRemoveByUsername() {
        myRealm.clearAuthByUserId("alice", true, false);

        verify(authzCache).remove("alice");
    }

    /**
     * 核心验证：clearAuthByUserIdCollection 应使用 username 列表逐个清除
     */
    @Test
    public void clearAuthByUserIdCollection_shouldRemoveByUsernames() {
        myRealm.clearAuthByUserIdCollection(Arrays.asList("alice", "bob"), true, false);

        verify(authzCache).remove("alice");
        verify(authzCache).remove("bob");
        verify(authzCache, times(2)).remove(any());
    }

    /**
     * 场景4：旧 token 访问已移除资源的权限验证
     * 缓存被清除后，doGetAuthorizationInfo 从数据库重新加载，不含被删权限
     */
    @Test
    public void doGetAuthorizationInfo_afterResourceRemoval_shouldNotContainRemovedPermission() {
        // 模拟用户 alice 当前只有 system:role 权限（system:user 已被管理员移除）
        SysRole role = SysRole.builder().id("role-1").name("管理员").build();
        SysResource remainingRes = SysResource.builder().id("res-2")
                .permission("system:role").verification(true).build();
        role.setResources(Collections.singletonList(remainingRes));

        SysUser user = SysUser.builder().id("u1").username("alice").status(1).build();
        user.setRoles(Collections.singletonList(role));

        when(userService.findUserByName("alice", true)).thenReturn(user);

        JwtToken token = new JwtToken("old-jwt-token", "alice", null);
        SimplePrincipalCollection principals = new SimplePrincipalCollection();
        principals.add(token, "testRealm");

        AuthorizationInfo info = myRealm.doGetAuthorizationInfo(principals);

        assertTrue("应包含剩余权限 system:role",
                info.getStringPermissions().contains("system:role"));
        assertFalse("不应包含已移除的权限 system:user",
                info.getStringPermissions().contains("system:user"));
    }

    /**
     * 场景3：多角色用户的权限应为所有角色资源的并集
     */
    @Test
    public void doGetAuthorizationInfo_multiRoleUser_shouldAggregatePermissions() {
        // 角色A：system:user
        SysRole roleA = SysRole.builder().id("role-a").name("用户管理员").build();
        SysResource resUser = SysResource.builder().id("res-1")
                .permission("system:user").verification(true).build();
        roleA.setResources(Collections.singletonList(resUser));

        // 角色B：system:role + system:log
        SysRole roleB = SysRole.builder().id("role-b").name("运维管理员").build();
        SysResource resRole = SysResource.builder().id("res-2")
                .permission("system:role").verification(true).build();
        SysResource resLog = SysResource.builder().id("res-3")
                .permission("system:log").verification(true).build();
        roleB.setResources(Arrays.asList(resRole, resLog));

        SysUser user = SysUser.builder().id("u1").username("alice").status(1).build();
        user.setRoles(Arrays.asList(roleA, roleB));

        when(userService.findUserByName("alice", true)).thenReturn(user);

        JwtToken token = new JwtToken("jwt-token", "alice", null);
        SimplePrincipalCollection principals = new SimplePrincipalCollection();
        principals.add(token, "testRealm");

        AuthorizationInfo info = myRealm.doGetAuthorizationInfo(principals);

        // 权限应为三个的并集
        Collection<String> perms = info.getStringPermissions();
        assertEquals("多角色权限应为并集", 3, perms.size());
        assertTrue(perms.contains("system:user"));
        assertTrue(perms.contains("system:role"));
        assertTrue(perms.contains("system:log"));

        // 角色名也应包含两个
        Collection<String> roles = info.getRoles();
        assertEquals(2, roles.size());
        assertTrue(roles.contains("用户管理员"));
        assertTrue(roles.contains("运维管理员"));
    }

    /**
     * 场景5：菜单树刷新后权限查询应反映最新资源
     * 模拟资源被删除后，doGetAuthorizationInfo 返回的资源列表不包含已删资源
     */
    @Test
    public void doGetAuthorizationInfo_afterMenuTreeRefresh_shouldReflectLatestResources() {
        // 管理员删除了 res-3，用户 alice 当前角色只剩 res-1 和 res-2
        SysRole role = SysRole.builder().id("role-1").name("管理员").build();
        SysResource res1 = SysResource.builder().id("res-1")
                .permission("system:user").verification(true).build();
        SysResource res2 = SysResource.builder().id("res-2")
                .permission("system:role").verification(true).build();
        role.setResources(Arrays.asList(res1, res2));

        SysUser user = SysUser.builder().id("u1").username("alice").status(1).build();
        user.setRoles(Collections.singletonList(role));
        when(userService.findUserByName("alice", true)).thenReturn(user);

        JwtToken token = new JwtToken("jwt-token", "alice", null);
        SimplePrincipalCollection principals = new SimplePrincipalCollection();
        principals.add(token, "testRealm");

        AuthorizationInfo info = myRealm.doGetAuthorizationInfo(principals);

        // 只应有 2 个权限（res-3 已被删除）
        assertEquals(2, info.getStringPermissions().size());
        assertFalse("已删除的 system:log 权限不应存在",
                info.getStringPermissions().contains("system:log"));
    }

    /**
     * 验证：缓存 key 一致性 — 同一个 username 的两次请求应使用相同的缓存 key
     */
    @Test
    public void getAuthorizationCacheKey_sameUsernameDifferentTokens_shouldReturnSameKey() {
        JwtToken token1 = new JwtToken("old-token-abc", "alice", null);
        JwtToken token2 = new JwtToken("new-token-xyz", "alice", null);

        SimplePrincipalCollection principals1 = new SimplePrincipalCollection();
        principals1.add(token1, "testRealm");
        SimplePrincipalCollection principals2 = new SimplePrincipalCollection();
        principals2.add(token2, "testRealm");

        Object key1 = myRealm.getAuthorizationCacheKey(principals1);
        Object key2 = myRealm.getAuthorizationCacheKey(principals2);

        assertEquals("相同 username 的不同 token 应产生相同缓存 key", key1, key2);
    }
}
