package cn.licoy.wdog.core.service.system.impl;

import cn.licoy.wdog.common.exception.RequestException;
import cn.licoy.wdog.core.entity.system.SysResource;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.entity.system.SysRoleResource;
import cn.licoy.wdog.core.entity.system.SysUser;
import cn.licoy.wdog.core.entity.system.SysUserRole;
import cn.licoy.wdog.core.service.global.ShiroService;
import cn.licoy.wdog.core.service.system.SysResourceService;
import cn.licoy.wdog.core.service.system.SysRoleResourceService;
import cn.licoy.wdog.core.service.system.SysRoleService;
import cn.licoy.wdog.core.service.system.SysUserRoleService;
import cn.licoy.wdog.core.service.system.SysUserService;
import cn.licoy.wdog.core.vo.system.CacheRefreshResultVO;
import cn.licoy.wdog.core.vo.system.RoleChangePreviewVO;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * RolePermissionChangeService 单元测试
 * 使用 Mockito 模拟所有依赖，不依赖 Spring 容器
 */
@RunWith(MockitoJUnitRunner.class)
public class RolePermissionChangeServiceTest {

    @InjectMocks
    private RolePermissionChangeServiceImpl service;

    @Mock
    private SysRoleService roleService;

    @Mock
    private SysRoleResourceService roleResourceService;

    @Mock
    private SysUserRoleService userRoleService;

    @Mock
    private SysResourceService resourceService;

    @Mock
    private SysUserService userService;

    @Mock
    private ShiroService shiroService;

    private static final String ROLE_ID = "role-001";
    private static final String ROLE_NAME = "管理员";

    private SysRole testRole;

    // 现有资源
    private SysResource res1;
    private SysResource res2;

    // 新资源
    private SysResource res3;

    @Before
    public void setUp() {
        testRole = SysRole.builder().id(ROLE_ID).name(ROLE_NAME).build();

        res1 = SysResource.builder().id("res-1").name("用户管理").type((short) 0)
                .url("/system/user").permission("system:user").verification(true).build();
        res2 = SysResource.builder().id("res-2").name("角色管理").type((short) 0)
                .url("/system/role").permission("system:role").verification(true).build();
        res3 = SysResource.builder().id("res-3").name("日志管理").type((short) 0)
                .url("/system/log").permission("system:log").verification(true).build();
    }

    // ========== previewImpact 测试 ==========

    @Test
    public void previewImpact_shouldDetectAddedAndRemovedResources() {
        // 场景：当前角色有 res1+res2，变更为 res2+res3
        // 预期：added=res3, removed=res1
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1, res2));

        // 新资源列表（前端传入的仅含id，service会去查完整信息）
        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-2").build(),
                SysResource.builder().id("res-3").build()
        );
        when(resourceService.selectBatchIds(Arrays.asList("res-2", "res-3")))
                .thenReturn(Arrays.asList(res2, res3));

        // 无绑定用户
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        RoleChangePreviewVO result = service.previewImpact(ROLE_ID, newResourcesInput);

        assertNotNull(result);
        assertEquals(ROLE_ID, result.getRoleId());
        assertEquals(ROLE_NAME, result.getRoleName());

        // 新增的资源应该是 res3
        assertEquals(1, result.getAddedResources().size());
        assertEquals("res-3", result.getAddedResources().get(0).getId());

        // 移除的资源应该是 res1
        assertEquals(1, result.getRemovedResources().size());
        assertEquals("res-1", result.getRemovedResources().get(0).getId());

        // 权限标识
        assertTrue(result.getAddedPermissions().contains("system:log"));
        assertTrue(result.getRemovedPermissions().contains("system:user"));

        // res2 的权限不应出现在差异中
        assertFalse(result.getAddedPermissions().contains("system:role"));
        assertFalse(result.getRemovedPermissions().contains("system:role"));
    }

    @Test
    public void previewImpact_shouldListAffectedUsers() {
        // 场景：角色有两个绑定用户，预览时应列出
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-2").build()
        );
        when(resourceService.selectBatchIds(Arrays.asList("res-2")))
                .thenReturn(Arrays.asList(res2));

        // 模拟两个用户绑定该角色
        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid(ROLE_ID).build();
        SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-2").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2));

        SysUser u1 = SysUser.builder().id("user-1").username("alice").build();
        SysUser u2 = SysUser.builder().id("user-2").username("bob").build();
        when(userService.selectBatchIds(Arrays.asList("user-1", "user-2")))
                .thenReturn(Arrays.asList(u1, u2));

        RoleChangePreviewVO result = service.previewImpact(ROLE_ID, newResourcesInput);

        assertEquals(2, result.getAffectedUserCount());
        assertEquals(2, result.getAffectedUsers().size());
        assertEquals("alice", result.getAffectedUsers().get(0).getUsername());
        assertEquals("bob", result.getAffectedUsers().get(1).getUsername());
    }

    @Test
    public void previewImpact_multiRoleUser_shouldNotDuplicate() {
        // 场景：同一用户通过多条 SysUserRole 记录绑定同一角色（理论上不应该但需要防御）
        // 去重逻辑应该保证用户不重复出现
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-2").build()
        );
        when(resourceService.selectBatchIds(Collections.singletonList("res-2")))
                .thenReturn(Arrays.asList(res2));

        // 同一用户有两条记录
        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid(ROLE_ID).build();
        SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-1").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2));

        SysUser u1 = SysUser.builder().id("user-1").username("alice").build();
        when(userService.selectBatchIds(Collections.singletonList("user-1")))
                .thenReturn(Arrays.asList(u1));

        RoleChangePreviewVO result = service.previewImpact(ROLE_ID, newResourcesInput);

        // 用户去重后应该只有1个
        assertEquals(1, result.getAffectedUserCount());
        assertEquals("alice", result.getAffectedUsers().get(0).getUsername());
    }

    @Test
    public void previewImpact_noChange_shouldReturnEmptyDiff() {
        // 场景：新旧资源完全相同，无影响变更
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1, res2));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-1").build(),
                SysResource.builder().id("res-2").build()
        );
        when(resourceService.selectBatchIds(Arrays.asList("res-1", "res-2")))
                .thenReturn(Arrays.asList(res1, res2));

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        RoleChangePreviewVO result = service.previewImpact(ROLE_ID, newResourcesInput);

        // 无新增、无移除
        assertTrue(result.getAddedResources().isEmpty());
        assertTrue(result.getRemovedResources().isEmpty());
        assertTrue(result.getAddedPermissions().isEmpty());
        assertTrue(result.getRemovedPermissions().isEmpty());
        assertEquals(0, result.getAffectedUserCount());
    }

    @Test(expected = RequestException.class)
    public void previewImpact_roleNotFound_shouldThrow() {
        // 场景：角色不存在
        when(roleService.selectById("nonexistent")).thenReturn(null);

        service.previewImpact("nonexistent", Collections.emptyList());
    }

    @Test
    public void previewImpact_currentResourcesNull_shouldTreatAsEmpty() {
        // 场景：角色当前无任何资源绑定，全部视为新增
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID)).thenReturn(null);

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-1").build()
        );
        when(resourceService.selectBatchIds(Collections.singletonList("res-1")))
                .thenReturn(Arrays.asList(res1));

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        RoleChangePreviewVO result = service.previewImpact(ROLE_ID, newResourcesInput);

        assertEquals(1, result.getAddedResources().size());
        assertTrue(result.getRemovedResources().isEmpty());
    }

    // ========== applyChange 测试 ==========

    @Test
    public void applyChange_shouldRefreshCacheAndReturnResult() {
        // 场景：有实际变更，应执行删旧增新、重载过滤链、清除缓存
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1, res2));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-2").build(),
                SysResource.builder().id("res-3").build()
        );
        when(resourceService.selectBatchIds(Arrays.asList("res-2", "res-3")))
                .thenReturn(Arrays.asList(res2, res3));

        // 模拟一个绑定用户
        SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1));

        CacheRefreshResultVO result = service.applyChange(ROLE_ID, newResourcesInput);

        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(ROLE_ID, result.getRoleId());
        assertEquals(ROLE_NAME, result.getRoleName());
        assertTrue(result.isFilterChainReloaded());
        assertEquals(1, result.getRefreshedUserCount());
        assertTrue(result.getRefreshedUserIds().contains("user-1"));
        assertNotNull(result.getRefreshTime());

        // 验证删旧增新操作被调用
        verify(roleResourceService).delete(any(EntityWrapper.class));
        // res2 和 res3 都应被插入
        ArgumentCaptor<SysRoleResource> captor = ArgumentCaptor.forClass(SysRoleResource.class);
        verify(roleResourceService, times(2)).insert(captor.capture());
        List<SysRoleResource> inserted = captor.getAllValues();
        assertEquals(2, inserted.size());

        // 验证 Shiro 过滤链重载
        verify(shiroService).reloadPerms();

        // 验证用户缓存清除
        verify(shiroService).clearAuthByUserIdCollection(
                eq(Collections.singletonList("user-1")), eq(true), eq(false));
    }

    @Test
    public void applyChange_noImpact_shouldReturnNoImpactStatus() {
        // 场景：新旧资源相同，应返回 NO_IMPACT 且不执行任何变更操作
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1, res2));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-1").build(),
                SysResource.builder().id("res-2").build()
        );
        when(resourceService.selectBatchIds(Arrays.asList("res-1", "res-2")))
                .thenReturn(Arrays.asList(res1, res2));

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        CacheRefreshResultVO result = service.applyChange(ROLE_ID, newResourcesInput);

        assertNotNull(result);
        assertEquals("NO_IMPACT", result.getStatus());
        assertEquals(0, result.getRefreshedUserCount());
        assertFalse(result.isFilterChainReloaded());

        // 不应执行任何变更操作
        verify(roleResourceService, never()).delete(any(EntityWrapper.class));
        verify(roleResourceService, never()).insert(any(SysRoleResource.class));
        verify(shiroService, never()).reloadPerms();
        verify(shiroService, never()).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
    }

    @Test
    public void applyChange_noBoundUsers_shouldStillReloadFilterChain() {
        // 场景：有资源变更但无绑定用户，仍应重载过滤链但不需要清除用户缓存
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-2").build()
        );
        when(resourceService.selectBatchIds(Collections.singletonList("res-2")))
                .thenReturn(Arrays.asList(res2));

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        CacheRefreshResultVO result = service.applyChange(ROLE_ID, newResourcesInput);

        assertEquals("SUCCESS", result.getStatus());
        assertEquals(0, result.getRefreshedUserCount());
        assertTrue(result.isFilterChainReloaded());

        // 应重载过滤链
        verify(shiroService).reloadPerms();
        // 但不应调用用户缓存清除
        verify(shiroService, never()).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
    }

    // ========== getLastRefreshResult 测试 ==========

    @Test
    public void getLastRefreshResult_afterApply_shouldReturnCached() {
        // 场景：先执行 applyChange，再查询结果
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-2").build()
        );
        when(resourceService.selectBatchIds(Collections.singletonList("res-2")))
                .thenReturn(Arrays.asList(res2));

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        // 先执行变更
        CacheRefreshResultVO applyResult = service.applyChange(ROLE_ID, newResourcesInput);

        // 再查询结果
        CacheRefreshResultVO queryResult = service.getLastRefreshResult(ROLE_ID);

        assertNotNull(queryResult);
        assertEquals(applyResult.getRoleId(), queryResult.getRoleId());
        assertEquals(applyResult.getStatus(), queryResult.getStatus());
        assertEquals(applyResult.getRefreshTime(), queryResult.getRefreshTime());
    }

    @Test
    public void getLastRefreshResult_noPriorChange_shouldReturnNull() {
        // 场景：从未执行过变更，查询应返回 null
        CacheRefreshResultVO result = service.getLastRefreshResult("never-changed-role");
        assertNull(result);
    }

    @Test
    public void getLastRefreshResult_afterNoImpactChange_shouldReturnNoImpactResult() {
        // 场景：执行无影响变更后查询结果
        when(roleService.selectById(ROLE_ID)).thenReturn(testRole);
        when(roleResourceService.findAllResourceByRoleId(ROLE_ID))
                .thenReturn(Arrays.asList(res1));

        List<SysResource> newResourcesInput = Arrays.asList(
                SysResource.builder().id("res-1").build()
        );
        when(resourceService.selectBatchIds(Collections.singletonList("res-1")))
                .thenReturn(Arrays.asList(res1));

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        // 执行无影响变更
        service.applyChange(ROLE_ID, newResourcesInput);

        // 查询结果
        CacheRefreshResultVO result = service.getLastRefreshResult(ROLE_ID);

        assertNotNull(result);
        assertEquals("NO_IMPACT", result.getStatus());
        assertEquals(0, result.getRefreshedUserCount());
    }
}
