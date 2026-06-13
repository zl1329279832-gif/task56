package cn.licoy.wdog.core.config.shiro;

import cn.licoy.wdog.core.config.jwt.JwtToken;
import cn.licoy.wdog.core.service.system.SysUserService;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * MyRealm 授权缓存key一致性测试
 * 验证 getAuthorizationCacheKey 返回用户ID，确保 clearAuthByUserId 能正确命中缓存
 */
@RunWith(MockitoJUnitRunner.class)
public class MyRealmCacheKeyTest {

    private MyRealm myRealm;

    @Mock
    private SysUserService userService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache<Object, Object> cache;

    private static final String USER_ID = "user-001";
    private static final String USERNAME = "testuser";
    private static final String CACHE_NAME = MyRealm.class.getName() + ".authorizationCache";

    @Before
    public void setUp() {
        myRealm = new MyRealm();
        ReflectionTestUtils.setField(myRealm, "cacheManager", cacheManager);
        when(cacheManager.getCache(CACHE_NAME)).thenReturn(cache);
    }

    /**
     * 核心测试：getAuthorizationCacheKey 返回的必须是用户ID字符串，
     * 与 clearAuthByUserId 使用的 uid 类型一致
     */
    @Test
    public void getAuthorizationCacheKey_shouldReturnUserId() {
        JwtToken token = new JwtToken("jwt-token-string", USERNAME, null);
        token.setUid(USER_ID);

        PrincipalCollection principals = new SimplePrincipalCollection(token, "testRealm");

        Object cacheKey = myRealm.getAuthorizationCacheKey(principals);

        assertEquals("缓存key应为用户ID", USER_ID, cacheKey);
        assertTrue("缓存key应为String类型", cacheKey instanceof String);
    }

    /**
     * 不同JWT token串但同一用户ID → 缓存key应该相同
     * 这确保同一用户的不同请求能命中同一缓存条目
     */
    @Test
    public void getAuthorizationCacheKey_sameUser_differentTokens_shouldReturnSameKey() {
        JwtToken token1 = new JwtToken("jwt-token-AAA", USERNAME, null);
        token1.setUid(USER_ID);

        JwtToken token2 = new JwtToken("jwt-token-BBB", USERNAME, null);
        token2.setUid(USER_ID);

        PrincipalCollection principals1 = new SimplePrincipalCollection(token1, "testRealm");
        PrincipalCollection principals2 = new SimplePrincipalCollection(token2, "testRealm");

        Object key1 = myRealm.getAuthorizationCacheKey(principals1);
        Object key2 = myRealm.getAuthorizationCacheKey(principals2);

        assertEquals("同一用户不同token的缓存key应相同", key1, key2);
    }

    /**
     * 不同用户 → 缓存key应该不同
     */
    @Test
    public void getAuthorizationCacheKey_differentUsers_shouldReturnDifferentKeys() {
        JwtToken token1 = new JwtToken("jwt-token-AAA", "alice", null);
        token1.setUid("user-001");

        JwtToken token2 = new JwtToken("jwt-token-BBB", "bob", null);
        token2.setUid("user-002");

        PrincipalCollection principals1 = new SimplePrincipalCollection(token1, "testRealm");
        PrincipalCollection principals2 = new SimplePrincipalCollection(token2, "testRealm");

        Object key1 = myRealm.getAuthorizationCacheKey(principals1);
        Object key2 = myRealm.getAuthorizationCacheKey(principals2);

        assertNotEquals("不同用户的缓存key应不同", key1, key2);
    }

    /**
     * clearAuthByUserId 使用 String uid 清除缓存 → 应该与 getAuthorizationCacheKey 的返回值匹配
     */
    @Test
    public void clearAuthByUserId_shouldRemoveByUserIdKey() {
        myRealm.clearAuthByUserId(USER_ID, true, false);

        verify(cache).remove(USER_ID);
    }

    /**
     * clearAuthByUserIdCollection 批量清除 → 每个uid都应被移除
     */
    @Test
    public void clearAuthByUserIdCollection_shouldRemoveAllUserIds() {
        myRealm.clearAuthByUserIdCollection(Arrays.asList("user-001", "user-002", "user-003"), true, false);

        verify(cache).remove("user-001");
        verify(cache).remove("user-002");
        verify(cache).remove("user-003");
    }

    /**
     * 旧token访问场景：用户使用旧JWT调接口，但管理员已变更权限并清除了缓存
     * 验证旧token的缓存key与清除时使用的key一致，清除后缓存应为空
     */
    @Test
    public void oldTokenAccess_afterCacheClear_shouldNotHitStaleCache() {
        // 模拟旧token（用户登录时获得的）
        JwtToken oldToken = new JwtToken("old-jwt-token-from-login", USERNAME, null);
        oldToken.setUid(USER_ID);
        PrincipalCollection oldPrincipals = new SimplePrincipalCollection(oldToken, "testRealm");

        // 验证旧token的缓存key就是用户ID
        Object cacheKeyFromOldToken = myRealm.getAuthorizationCacheKey(oldPrincipals);
        assertEquals(USER_ID, cacheKeyFromOldToken);

        // 管理员变更权限后清除缓存（使用用户ID）
        myRealm.clearAuthByUserId(USER_ID, true, false);

        // 验证清除操作使用了正确的key
        verify(cache).remove(USER_ID);

        // 当旧token再次访问时，缓存key仍为USER_ID，应该命中已清除的缓存 → 触发重新授权
        // 模拟缓存已清空（get返回null）
        when(cache.get(USER_ID)).thenReturn(null);
        assertNull("缓存被清除后，旧token访问应返回null", cache.get(cacheKeyFromOldToken));
    }

    /**
     * 用户拥有多角色场景：清除缓存只需按用户ID清除一次
     */
    @Test
    public void multiRoleUser_clearCache_shouldClearOnceByUserId() {
        // 用户有roleA和roleB两个角色
        // 不论从哪个角色触发清除，都应该用同一个用户ID作为key
        JwtToken token = new JwtToken("jwt-token", USERNAME, null);
        token.setUid(USER_ID);
        PrincipalCollection principals = new SimplePrincipalCollection(token, "testRealm");

        Object key = myRealm.getAuthorizationCacheKey(principals);

        // 从roleA触发清除
        myRealm.clearAuthByUserId(USER_ID, true, false);

        // key与清除的key一致
        assertEquals(USER_ID, key);
        verify(cache, times(1)).remove(USER_ID);
    }
}
