package cn.licoy.wdog.core.service.system.impl;

import cn.licoy.wdog.common.exception.RequestException;
import cn.licoy.wdog.core.dto.system.resource.ResourceDTO;
import cn.licoy.wdog.core.entity.system.SysResource;
import cn.licoy.wdog.core.entity.system.SysRoleResource;
import cn.licoy.wdog.core.entity.system.SysUserRole;
import cn.licoy.wdog.core.mapper.system.SysRolePermissionMapper;
import cn.licoy.wdog.core.service.global.ShiroService;
import cn.licoy.wdog.core.service.system.SysUserRoleService;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SysResourceServiceImpl 缓存失效测试
 * 验证资源增删改操作后正确清除受影响用户的授权缓存
 */
@RunWith(MockitoJUnitRunner.class)
public class ResourceCacheInvalidationTest {

    @Spy
    @InjectMocks
    private SysResourceServiceImpl resourceService;

    @Mock
    private ShiroService shiroService;

    @Mock
    private SysRolePermissionMapper rolePermissionMapper;

    @Mock
    private SysUserRoleService userRoleService;

    private SysResource res1;

    @Before
    public void setUp() {
        res1 = SysResource.builder()
                .id("res-1").name("用户管理").type((short) 0)
                .url("/system/user").permission("system:user")
                .verification(true).build();
    }

    // =============================================
    // 资源删除 → 缓存失效
    // =============================================

    /**
     * 删除资源时，应清除所有通过角色引用该资源的用户的授权缓存
     */
    @Test
    public void remove_shouldClearAffectedUserCaches() {
        doReturn(res1).when(resourceService).selectOne(any(EntityWrapper.class));
        doReturn(true).when(resourceService).deleteById(anyString());

        // 资源被 roleA 和 roleB 引用
        SysRoleResource rr1 = SysRoleResource.builder().id("rr-1").rid("roleA").pid("res-1").build();
        SysRoleResource rr2 = SysRoleResource.builder().id("rr-2").rid("roleB").pid("res-1").build();
        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(rr1, rr2));

        // roleA 绑定 user1, roleB 绑定 user2
        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("roleA").build();
        SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-2").rid("roleB").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2));

        resourceService.remove("res-1");

        // 验证清除了受影响用户的缓存
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shiroService).clearAuthByUserIdCollection(userIdsCaptor.capture(), eq(true), eq(false));
        List<String> clearedUserIds = userIdsCaptor.getValue();
        assertTrue("应清除user-1的缓存", clearedUserIds.contains("user-1"));
        assertTrue("应清除user-2的缓存", clearedUserIds.contains("user-2"));
    }

    /**
     * 删除资源时，应同时清理角色-资源关联记录（防止orphan）
     */
    @Test
    public void remove_shouldCleanUpOrphanedRoleResourceBindings() {
        doReturn(res1).when(resourceService).selectOne(any(EntityWrapper.class));
        doReturn(true).when(resourceService).deleteById(anyString());

        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        resourceService.remove("res-1");

        // 验证删除了角色-资源关联记录
        verify(rolePermissionMapper).delete(any(EntityWrapper.class));
    }

    /**
     * 删除资源时，如果该资源没有任何角色引用，不应调用缓存清除
     */
    @Test
    public void remove_noRoleBindings_shouldNotCallCacheClear() {
        doReturn(res1).when(resourceService).selectOne(any(EntityWrapper.class));
        doReturn(true).when(resourceService).deleteById(anyString());

        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        resourceService.remove("res-1");

        verify(shiroService, never()).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
        // 仍应重载过滤链
        verify(shiroService).reloadPerms();
    }

    // =============================================
    // 资源新增 → 过滤链刷新
    // =============================================

    /**
     * 新增资源时，应重载过滤链（使新URL受保护），但不需要清缓存（无角色引用）
     */
    @Test
    public void add_shouldReloadPermsButNotClearCache() {
        doReturn(true).when(resourceService).insert(any(SysResource.class));

        ResourceDTO dto = new ResourceDTO();
        dto.setName("新资源");
        dto.setUrl("/new/resource");
        dto.setPermission("new:resource");
        dto.setVerification(true);

        resourceService.add(dto);

        verify(shiroService).reloadPerms();
        verify(shiroService, never()).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
    }

    // =============================================
    // 资源更新 → 缓存失效
    // =============================================

    /**
     * 更新资源（如修改permission字段）后，应清除受影响用户的缓存
     */
    @Test
    public void update_shouldClearAffectedUserCaches() {
        doReturn(res1).when(resourceService).selectById("res-1");
        doReturn(true).when(resourceService).updateById(any(SysResource.class));

        // 资源被 roleA 引用
        SysRoleResource rr1 = SysRoleResource.builder().id("rr-1").rid("roleA").pid("res-1").build();
        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(rr1));

        // roleA 绑定 user1 和 user2
        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("roleA").build();
        SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-2").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2));

        ResourceDTO dto = new ResourceDTO();
        dto.setName("用户管理改名");
        dto.setUrl("/system/user");
        dto.setPermission("system:user:updated");
        dto.setVerification(true);

        resourceService.update("res-1", dto);

        verify(shiroService).reloadPerms();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shiroService).clearAuthByUserIdCollection(userIdsCaptor.capture(), eq(true), eq(false));
        assertEquals(2, userIdsCaptor.getValue().size());
    }

    /**
     * 更新资源，但该资源无角色引用时，不调用缓存清除
     */
    @Test
    public void update_noRoleBindings_shouldNotCallCacheClear() {
        doReturn(res1).when(resourceService).selectById("res-1");
        doReturn(true).when(resourceService).updateById(any(SysResource.class));

        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        ResourceDTO dto = new ResourceDTO();
        dto.setName("用户管理");
        dto.setUrl("/system/user");
        dto.setPermission("system:user");
        dto.setVerification(true);

        resourceService.update("res-1", dto);

        verify(shiroService).reloadPerms();
        verify(shiroService, never()).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
    }

    // =============================================
    // 用户多角色场景
    // =============================================

    /**
     * 同一用户通过多个角色引用同一资源时，缓存清除应去重
     */
    @Test
    public void remove_multiRoleUser_shouldDeduplicateUserIds() {
        doReturn(res1).when(resourceService).selectOne(any(EntityWrapper.class));
        doReturn(true).when(resourceService).deleteById(anyString());

        // 资源被 roleA 和 roleB 引用
        SysRoleResource rr1 = SysRoleResource.builder().id("rr-1").rid("roleA").pid("res-1").build();
        SysRoleResource rr2 = SysRoleResource.builder().id("rr-2").rid("roleB").pid("res-1").build();
        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(rr1, rr2));

        // 同一用户 user-1 同时绑定 roleA 和 roleB
        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("roleA").build();
        SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-1").rid("roleB").build();
        SysUserRole ur3 = SysUserRole.builder().id("ur-3").uid("user-2").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2, ur3));

        resourceService.remove("res-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shiroService).clearAuthByUserIdCollection(userIdsCaptor.capture(), eq(true), eq(false));
        List<String> clearedUserIds = userIdsCaptor.getValue();
        // user-1 出现两次但应去重
        assertEquals("去重后应只有2个用户", 2, clearedUserIds.size());
        assertTrue(clearedUserIds.contains("user-1"));
        assertTrue(clearedUserIds.contains("user-2"));
    }

    // =============================================
    // 旧 token 访问接口 → 缓存已清除后应触发重新授权
    // =============================================

    /**
     * 场景：管理员删除资源后，旧token再次调接口时缓存已清，应从DB重新加载权限
     * 此测试验证 remove 操作在删除DB记录之前先清除缓存，保证时序正确
     */
    @Test
    public void remove_shouldClearCacheBeforeDeletingDbRecords() {
        doReturn(res1).when(resourceService).selectOne(any(EntityWrapper.class));
        doReturn(true).when(resourceService).deleteById(anyString());

        SysRoleResource rr1 = SysRoleResource.builder().id("rr-1").rid("roleA").pid("res-1").build();
        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(rr1));

        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1));

        resourceService.remove("res-1");

        // 验证调用顺序：先清缓存 → 再删关联 → 再删资源 → 最后刷过滤链
        org.mockito.InOrder inOrder = inOrder(shiroService, rolePermissionMapper, resourceService);
        inOrder.verify(shiroService).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
        inOrder.verify(rolePermissionMapper).delete(any(EntityWrapper.class));
        inOrder.verify(resourceService).deleteById("res-1");
        inOrder.verify(shiroService).reloadPerms();
    }

    // =============================================
    // 菜单树刷新场景
    // =============================================

    /**
     * 场景：删除资源后过滤链应被重载，使URL不再受保护或对应新的权限
     * 菜单树从DB查询，过滤链从 reloadPerms 刷新
     */
    @Test
    public void remove_shouldReloadFilterChainForMenuRefresh() {
        doReturn(res1).when(resourceService).selectOne(any(EntityWrapper.class));
        doReturn(true).when(resourceService).deleteById(anyString());

        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        resourceService.remove("res-1");

        verify(shiroService).reloadPerms();
    }

    /**
     * 场景：更新资源URL或权限后，过滤链应被重载，菜单树重新查询DB即可获得最新数据
     */
    @Test
    public void update_shouldReloadFilterChainAndClearCacheForMenuRefresh() {
        doReturn(res1).when(resourceService).selectById("res-1");
        doReturn(true).when(resourceService).updateById(any(SysResource.class));

        SysRoleResource rr1 = SysRoleResource.builder().id("rr-1").rid("roleA").pid("res-1").build();
        when(rolePermissionMapper.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(rr1));

        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1));

        ResourceDTO dto = new ResourceDTO();
        dto.setName("用户管理v2");
        dto.setUrl("/system/user/v2");
        dto.setPermission("system:userv2");
        dto.setVerification(true);

        resourceService.update("res-1", dto);

        // 过滤链重载 + 用户缓存清除 → 菜单树和接口权限同步刷新
        verify(shiroService).reloadPerms();
        verify(shiroService).clearAuthByUserIdCollection(anyList(), eq(true), eq(false));
    }
}
