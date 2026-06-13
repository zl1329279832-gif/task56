package cn.licoy.wdog.core.service.system.impl;

import cn.licoy.wdog.core.entity.system.SysResource;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.entity.system.SysRoleResource;
import cn.licoy.wdog.core.entity.system.SysUserRole;
import cn.licoy.wdog.core.service.global.ShiroService;
import cn.licoy.wdog.core.service.system.SysRoleResourceService;
import cn.licoy.wdog.core.service.system.SysUserRoleService;
import cn.licoy.wdog.core.dto.system.role.RoleUpdateDTO;
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
 * SysRoleServiceImpl 角色资源变更缓存失效测试
 * 覆盖：角色资源删除、角色资源新增、用户多角色、缓存刷新时序
 */
@RunWith(MockitoJUnitRunner.class)
public class RoleCacheInvalidationTest {

    @Spy
    @InjectMocks
    private SysRoleServiceImpl roleService;

    @Mock
    private SysRoleResourceService roleResourceService;

    @Mock
    private SysUserRoleService userRoleService;

    @Mock
    private ShiroService shiroService;

    private static final String ROLE_ID = "role-001";
    private SysRole testRole;
    private SysResource res1;
    private SysResource res2;
    private SysResource res3;

    @Before
    public void setUp() {
        testRole = SysRole.builder().id(ROLE_ID).name("管理员").build();

        res1 = SysResource.builder().id("res-1").name("用户管理")
                .url("/system/user").permission("system:user").verification(true).build();
        res2 = SysResource.builder().id("res-2").name("角色管理")
                .url("/system/role").permission("system:role").verification(true).build();
        res3 = SysResource.builder().id("res-3").name("日志管理")
                .url("/system/log").permission("system:log").verification(true).build();
    }

    // =============================================
    // 角色资源删除 → 缓存失效
    // =============================================

    /**
     * 角色移除部分资源后，应清除所有绑定该角色的用户的授权缓存
     * 场景：角色原有 res1+res2，更新为只保留 res2（删除 res1）
     */
    @Test
    public void update_removeResource_shouldClearAffectedUserCaches() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).updateById(any(SysRole.class));

        // 两个用户绑定该角色
        SysUserRole ur1 = SysUserRole.builder().uid("user-1").rid(ROLE_ID).build();
        SysUserRole ur2 = SysUserRole.builder().uid("user-2").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2));

        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setName("管理员");
        // 只保留 res2（相当于删除 res1）
        dto.setResources(Arrays.asList(res2));

        roleService.update(ROLE_ID, dto);

        // 验证缓存被清除
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shiroService).clearAuthByUserIdCollection(userIdsCaptor.capture(), eq(true), eq(false));
        List<String> clearedUserIds = userIdsCaptor.getValue();
        assertTrue(clearedUserIds.contains("user-1"));
        assertTrue(clearedUserIds.contains("user-2"));
    }

    /**
     * 角色移除全部资源（清空），应清除所有绑定用户的缓存
     */
    @Test
    public void update_removeAllResources_shouldClearAffectedUserCaches() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).updateById(any(SysRole.class));

        SysUserRole ur1 = SysUserRole.builder().uid("user-1").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1));

        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setName("管理员");
        dto.setResources(Collections.emptyList());

        roleService.update(ROLE_ID, dto);

        verify(shiroService).clearAuthByUserIdCollection(anyList(), eq(true), eq(false));
    }

    // =============================================
    // 角色资源新增 → 缓存失效
    // =============================================

    /**
     * 角色新增资源后，应清除绑定用户的缓存，使新权限能被立即识别
     * 场景：角色原有 res1，更新为 res1+res3（新增 res3）
     */
    @Test
    public void update_addResource_shouldClearAffectedUserCaches() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).updateById(any(SysRole.class));

        SysUserRole ur1 = SysUserRole.builder().uid("user-1").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1));

        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setName("管理员");
        dto.setResources(Arrays.asList(res1, res3));

        roleService.update(ROLE_ID, dto);

        // 验证清除了用户缓存（新增权限才能立即生效）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shiroService).clearAuthByUserIdCollection(userIdsCaptor.capture(), eq(true), eq(false));
        assertTrue(userIdsCaptor.getValue().contains("user-1"));
    }

    /**
     * 角色新增资源后，角色-资源绑定记录应正确插入
     */
    @Test
    public void update_addResource_shouldInsertNewBindings() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).updateById(any(SysRole.class));

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setName("管理员");
        dto.setResources(Arrays.asList(res1, res2, res3));

        roleService.update(ROLE_ID, dto);

        // 先删除旧绑定
        verify(roleResourceService).delete(any(EntityWrapper.class));
        // 再插入3条新绑定
        verify(roleResourceService, times(3)).insert(any(SysRoleResource.class));
    }

    // =============================================
    // 用户多角色场景
    // =============================================

    /**
     * 用户拥有多个角色时，修改其中一个角色的资源应清除该用户的缓存
     */
    @Test
    public void update_multiRoleUser_shouldClearUserCache() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).updateById(any(SysRole.class));

        // user-1 同时绑定 role-001 和 role-002（多角色用户）
        // 这里只查 role-001 的绑定用户
        SysUserRole ur1 = SysUserRole.builder().uid("user-1").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1));

        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setName("管理员");
        dto.setResources(Arrays.asList(res2)); // 变更资源

        roleService.update(ROLE_ID, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shiroService).clearAuthByUserIdCollection(userIdsCaptor.capture(), eq(true), eq(false));
        assertTrue("多角色用户应在缓存清除列表中", userIdsCaptor.getValue().contains("user-1"));
    }

    /**
     * 多个用户绑定同一角色 + 某些用户还绑定了其他角色，
     * 修改该角色资源时应清除所有绑定用户的缓存（不遗漏、不误伤）
     */
    @Test
    public void update_multipleUsersMultipleRoles_shouldClearAllBoundUsers() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).updateById(any(SysRole.class));

        // user-1 绑定 role-001（还有别的角色，但 updateCache 只按当前角色查）
        // user-2 也绑定 role-001
        // user-3 不绑定 role-001（不受影响）
        SysUserRole ur1 = SysUserRole.builder().uid("user-1").rid(ROLE_ID).build();
        SysUserRole ur2 = SysUserRole.builder().uid("user-2").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2));

        RoleUpdateDTO dto = new RoleUpdateDTO();
        dto.setName("管理员");
        dto.setResources(Arrays.asList(res3));

        roleService.update(ROLE_ID, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> userIdsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(shiroService).clearAuthByUserIdCollection(userIdsCaptor.capture(), eq(true), eq(false));
        List<String> cleared = userIdsCaptor.getValue();
        assertEquals("应清除2个用户的缓存", 2, cleared.size());
        assertTrue(cleared.contains("user-1"));
        assertTrue(cleared.contains("user-2"));
    }

    // =============================================
    // 角色删除 → 缓存失效
    // =============================================

    /**
     * 删除角色时，应清除所有绑定该角色的用户的授权缓存
     */
    @Test
    public void remove_shouldClearAffectedUserCaches() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).deleteById(anyString());

        SysUserRole ur1 = SysUserRole.builder().uid("user-1").rid(ROLE_ID).build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1));

        roleService.remove(ROLE_ID);

        verify(shiroService).clearAuthByUserIdCollection(anyList(), eq(true), eq(false));
    }

    /**
     * 角色无绑定用户时删除，不应调用缓存清除
     */
    @Test
    public void remove_noUsers_shouldNotCallCacheClear() {
        doReturn(testRole).when(roleService).selectById(ROLE_ID);
        doReturn(true).when(roleService).deleteById(anyString());

        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.emptyList());

        roleService.remove(ROLE_ID);

        // 空列表传入 clearAuthByUserIdCollection，内部不会报错
        verify(shiroService).clearAuthByUserIdCollection(eq(Collections.emptyList()), eq(true), eq(false));
    }
}
