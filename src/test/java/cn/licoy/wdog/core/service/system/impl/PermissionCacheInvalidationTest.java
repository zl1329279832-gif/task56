package cn.licoy.wdog.core.service.system.impl;

import cn.licoy.wdog.core.dto.system.resource.ResourceDTO;
import cn.licoy.wdog.core.dto.system.role.RoleUpdateDTO;
import cn.licoy.wdog.core.entity.system.*;
import cn.licoy.wdog.core.service.global.ShiroService;
import cn.licoy.wdog.core.service.system.*;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 权限缓存失效与判定完整测试
 * 覆盖：角色资源删除、角色资源新增、用户多角色、旧 token 访问接口、菜单树刷新
 *
 * MyRealm 的缓存 key 与权限判定测试见 cn.licoy.wdog.core.config.shiro.MyRealmCacheKeyTest
 */
public class PermissionCacheInvalidationTest {

    // =====================================================================
    // SysRoleServiceImpl 测试：角色资源变更后缓存正确失效
    // =====================================================================

    @RunWith(MockitoJUnitRunner.class)
    public static class SysRoleServiceCacheTest {

        @Spy
        @InjectMocks
        private SysRoleServiceImpl roleService;

        @Mock
        private SysRoleResourceService roleResourceService;

        @Mock
        private SysUserRoleService userRoleService;

        @Mock
        private SysUserService userService;

        @Mock
        private ShiroService shiroService;

        private SysRole testRole;
        private SysResource res1;
        private SysResource res2;
        private SysResource res3;

        @Before
        public void setUp() {
            testRole = SysRole.builder().id("role-1").name("管理员").build();
            res1 = SysResource.builder().id("res-1").name("用户管理")
                    .url("/system/user").permission("system:user").verification(true).build();
            res2 = SysResource.builder().id("res-2").name("角色管理")
                    .url("/system/role").permission("system:role").verification(true).build();
            res3 = SysResource.builder().id("res-3").name("日志管理")
                    .url("/system/log").permission("system:log").verification(true).build();

            // Stub ServiceImpl inherited methods
            doReturn(testRole).when(roleService).selectById("role-1");
            doReturn(true).when(roleService).updateById(any(SysRole.class));
            doReturn(true).when(roleService).deleteById(anyString());
        }

        /**
         * 场景1：管理员从角色中移除资源后，受影响用户的授权缓存应以 username 为 key 被清除
         */
        @Test
        public void update_removeResource_shouldClearCacheByUsername() {
            SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("role-1").build();
            SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-2").rid("role-1").build();
            when(userRoleService.selectList(any(EntityWrapper.class)))
                    .thenReturn(Arrays.asList(ur1, ur2));

            SysUser u1 = SysUser.builder().id("user-1").username("alice").build();
            SysUser u2 = SysUser.builder().id("user-2").username("bob").build();
            when(userService.selectBatchIds(anyList()))
                    .thenReturn(Arrays.asList(u1, u2));

            RoleUpdateDTO dto = new RoleUpdateDTO();
            dto.setName("管理员");
            dto.setResources(Collections.singletonList(res2));

            roleService.update("role-1", dto);

            // 验证 reloadPerms 被调用（过滤链重载）
            verify(shiroService).reloadPerms();

            // 验证缓存清除使用 username 而非 userId
            ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
            verify(shiroService).clearAuthByUserIdCollection(captor.capture(), eq(true), eq(false));
            List<String> clearedNames = captor.getValue();
            assertTrue("应包含 alice", clearedNames.contains("alice"));
            assertTrue("应包含 bob", clearedNames.contains("bob"));
            assertFalse("不应包含 userId 'user-1'", clearedNames.contains("user-1"));
            assertFalse("不应包含 userId 'user-2'", clearedNames.contains("user-2"));
        }

        /**
         * 场景2：管理员向角色新增资源后，过滤链应重载且用户缓存应清除
         */
        @Test
        public void update_addResource_shouldReloadPermsAndClearCache() {
            SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("role-1").build();
            when(userRoleService.selectList(any(EntityWrapper.class)))
                    .thenReturn(Collections.singletonList(ur1));
            SysUser u1 = SysUser.builder().id("user-1").username("alice").build();
            when(userService.selectBatchIds(anyList()))
                    .thenReturn(Collections.singletonList(u1));

            RoleUpdateDTO dto = new RoleUpdateDTO();
            dto.setName("管理员");
            dto.setResources(Arrays.asList(res1, res2, res3));

            roleService.update("role-1", dto);

            verify(shiroService).reloadPerms();
            verify(shiroService).clearAuthByUserIdCollection(
                    eq(Collections.singletonList("alice")), eq(true), eq(false));
        }

        /**
         * 场景：删除角色也应重载过滤链并清除缓存
         */
        @Test
        public void remove_roleShouldReloadPermsAndClearCache() {
            SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("role-1").build();
            when(userRoleService.selectList(any(EntityWrapper.class)))
                    .thenReturn(Collections.singletonList(ur1));
            SysUser u1 = SysUser.builder().id("user-1").username("charlie").build();
            when(userService.selectBatchIds(anyList()))
                    .thenReturn(Collections.singletonList(u1));

            roleService.remove("role-1");

            verify(shiroService).reloadPerms();
            verify(shiroService).clearAuthByUserIdCollection(
                    eq(Collections.singletonList("charlie")), eq(true), eq(false));
        }

        /**
         * 边界：角色无绑定用户时不应尝试清除缓存
         */
        @Test
        public void updateCache_noBoundUsers_shouldNotClearCache() {
            when(userRoleService.selectList(any(EntityWrapper.class)))
                    .thenReturn(Collections.emptyList());

            roleService.updateCache(testRole, true, false);

            verify(shiroService, never()).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
        }
    }

    // =====================================================================
    // SysResourceServiceImpl 测试：资源 CRUD 后全量清除用户缓存
    // =====================================================================

    @RunWith(MockitoJUnitRunner.class)
    public static class SysResourceServiceCacheTest {

        @Spy
        @InjectMocks
        private SysResourceServiceImpl resourceService;

        @Mock
        private ShiroService shiroService;

        @Mock
        private SysUserService userService;

        @Before
        public void setUp() {
            List<SysUser> allUsers = Arrays.asList(
                    SysUser.builder().id("u1").username("alice").build(),
                    SysUser.builder().id("u2").username("bob").build(),
                    SysUser.builder().id("u3").username("charlie").build()
            );
            when(userService.selectList(isNull())).thenReturn(allUsers);

            doReturn(true).when(resourceService).insert(any(SysResource.class));
            doReturn(true).when(resourceService).updateById(any(SysResource.class));
            doReturn(true).when(resourceService).deleteById(anyString());
        }

        /**
         * 场景6a：新增资源后，所有用户的授权缓存应被清除
         */
        @Test
        public void add_resourceShouldClearAllUserCache() {
            ResourceDTO dto = new ResourceDTO();
            dto.setName("新模块");
            dto.setUrl("/new-module");
            dto.setPermission("new:module");
            dto.setVerification(true);

            resourceService.add(dto);

            verify(shiroService).reloadPerms();
            ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
            verify(shiroService).clearAuthByUserIdCollection(captor.capture(), eq(true), eq(false));
            List<String> cleared = captor.getValue();
            assertEquals(3, cleared.size());
            assertTrue(cleared.containsAll(Arrays.asList("alice", "bob", "charlie")));
        }

        /**
         * 场景6b：更新资源后，所有用户的授权缓存应被清除
         */
        @Test
        public void update_resourceShouldClearAllUserCache() {
            SysResource existing = SysResource.builder().id("res-1").name("旧名")
                    .url("/old").permission("old:perm").verification(true).build();
            doReturn(existing).when(resourceService).selectById("res-1");

            ResourceDTO dto = new ResourceDTO();
            dto.setName("新名");
            dto.setUrl("/new");
            dto.setPermission("new:perm");
            dto.setVerification(true);

            resourceService.update("res-1", dto);

            verify(shiroService).reloadPerms();
            verify(shiroService).clearAuthByUserIdCollection(anyList(), eq(true), eq(false));
        }

        /**
         * 场景6c：删除资源后，所有用户的授权缓存应被清除
         */
        @Test
        public void remove_resourceShouldClearAllUserCache() {
            SysResource existing = SysResource.builder().id("res-1").build();
            doReturn(existing).when(resourceService).selectOne(any(EntityWrapper.class));

            resourceService.remove("res-1");

            verify(shiroService).reloadPerms();
            verify(shiroService).clearAuthByUserIdCollection(anyList(), eq(true), eq(false));
        }
    }

    // =====================================================================
    // RolePermissionChangeServiceImpl 测试：username 缓存清除
    // =====================================================================

    @RunWith(MockitoJUnitRunner.class)
    public static class RolePermissionChangeCacheTest {

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

        private SysResource res1;
        private SysResource res2;
        private SysResource res3;

        @Before
        public void setUp() {
            res1 = SysResource.builder().id("res-1").name("用户管理")
                    .url("/system/user").permission("system:user").verification(true).build();
            res2 = SysResource.builder().id("res-2").name("角色管理")
                    .url("/system/role").permission("system:role").verification(true).build();
            res3 = SysResource.builder().id("res-3").name("日志管理")
                    .url("/system/log").permission("system:log").verification(true).build();
        }

        /**
         * applyChange 应使用 username（非 userId）清除缓存
         */
        @Test
        public void applyChange_shouldClearCacheByUsernames() {
            SysRole role = SysRole.builder().id("role-1").name("管理员").build();
            when(roleService.selectById("role-1")).thenReturn(role);
            when(roleResourceService.findAllResourceByRoleId("role-1"))
                    .thenReturn(Arrays.asList(res1, res2));

            List<SysResource> newResources = Arrays.asList(
                    SysResource.builder().id("res-2").build(),
                    SysResource.builder().id("res-3").build()
            );
            when(resourceService.selectBatchIds(anyList()))
                    .thenReturn(Arrays.asList(res2, res3));

            SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("role-1").build();
            SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-2").rid("role-1").build();
            when(userRoleService.selectList(any(EntityWrapper.class)))
                    .thenReturn(Arrays.asList(ur1, ur2));

            SysUser u1 = SysUser.builder().id("user-1").username("alice").build();
            SysUser u2 = SysUser.builder().id("user-2").username("bob").build();
            when(userService.selectBatchIds(anyList()))
                    .thenReturn(Arrays.asList(u1, u2));

            service.applyChange("role-1", newResources);

            ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
            verify(shiroService).clearAuthByUserIdCollection(captor.capture(), eq(true), eq(false));
            List<String> clearedNames = captor.getValue();
            assertTrue("应包含 alice", clearedNames.contains("alice"));
            assertTrue("应包含 bob", clearedNames.contains("bob"));
            assertFalse("不应包含 userId", clearedNames.contains("user-1"));
        }

        /**
         * 多角色用户：同一用户绑定多个角色时，applyChange 应去重
         */
        @Test
        public void applyChange_multiRoleUser_shouldNotDuplicateClearCalls() {
            SysRole role = SysRole.builder().id("role-1").name("管理员").build();
            when(roleService.selectById("role-1")).thenReturn(role);
            when(roleResourceService.findAllResourceByRoleId("role-1"))
                    .thenReturn(Arrays.asList(res1));

            List<SysResource> newResources = Arrays.asList(
                    SysResource.builder().id("res-2").build()
            );
            when(resourceService.selectBatchIds(anyList()))
                    .thenReturn(Arrays.asList(res2));

            // alice 通过两条记录绑定此角色
            SysUserRole ur1 = SysUserRole.builder().id("ur-1").uid("user-1").rid("role-1").build();
            SysUserRole ur2 = SysUserRole.builder().id("ur-2").uid("user-1").rid("role-1").build();
            when(userRoleService.selectList(any(EntityWrapper.class)))
                    .thenReturn(Arrays.asList(ur1, ur2));

            SysUser u1 = SysUser.builder().id("user-1").username("alice").build();
            when(userService.selectBatchIds(anyList()))
                    .thenReturn(Collections.singletonList(u1));

            service.applyChange("role-1", newResources);

            ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
            verify(shiroService).clearAuthByUserIdCollection(captor.capture(), eq(true), eq(false));
            List<String> clearedNames = captor.getValue();
            assertTrue("应包含 alice", clearedNames.contains("alice"));
        }
    }
}
