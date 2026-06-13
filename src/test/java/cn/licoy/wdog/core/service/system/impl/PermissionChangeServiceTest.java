package cn.licoy.wdog.core.service.system.impl;

import cn.licoy.wdog.core.dto.system.role.PermissionChangeDTO;
import cn.licoy.wdog.core.entity.system.SysResource;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.entity.system.SysRoleResource;
import cn.licoy.wdog.core.entity.system.SysUser;
import cn.licoy.wdog.core.entity.system.SysUserRole;
import cn.licoy.wdog.core.mapper.system.SysRoleMapper;
import cn.licoy.wdog.core.service.global.ShiroService;
import cn.licoy.wdog.core.service.system.SysResourceService;
import cn.licoy.wdog.core.service.system.SysRoleResourceService;
import cn.licoy.wdog.core.service.system.SysUserRoleService;
import cn.licoy.wdog.core.service.system.SysUserService;
import cn.licoy.wdog.core.vo.system.PermissionChangePreviewVO;
import cn.licoy.wdog.core.vo.system.PermissionChangeResultVO;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 权限变更影响预览与缓存刷新 单元测试
 */
@RunWith(MockitoJUnitRunner.class)
public class PermissionChangeServiceTest {

    @InjectMocks
    private SysRoleServiceImpl sysRoleService;

    @Mock
    private SysRoleResourceService roleResourceService;

    @Mock
    private SysUserRoleService userRoleService;

    @Mock
    private SysResourceService resourceService;

    @Mock
    private ShiroService shiroService;

    @Mock
    private SysUserService userService;

    @Mock
    private SysRoleMapper baseMapper;

    private SysRole roleA;
    private SysResource res1;
    private SysResource res2;
    private SysResource res3;
    private SysUser user1;
    private SysUser user2;

    @Before
    public void setUp() {
        roleA = SysRole.builder().id("roleA").name("管理员").build();

        res1 = new SysResource();
        res1.setId("R1");
        res1.setName("用户管理");
        res1.setPermission("system:user:list");

        res2 = new SysResource();
        res2.setId("R2");
        res2.setName("角色管理");
        res2.setPermission("system:role:list");

        res3 = new SysResource();
        res3.setId("R3");
        res3.setName("日志管理");
        res3.setPermission("system:log:list");

        user1 = new SysUser();
        user1.setId("U1");
        user1.setUsername("admin");
        user1.setStatus(1);

        user2 = new SysUser();
        user2.setId("U2");
        user2.setUsername("editor");
        user2.setStatus(1);
    }

    /**
     * 测试场景：角色资源新增
     * 角色当前拥有 [R1,R2]，提议变更为 [R1,R2,R3]
     * 预期：addedResources=[R3], removedResources=[], 受影响用户列出
     */
    @Test
    public void testPreviewResourceAdd() {
        when(baseMapper.selectById("roleA")).thenReturn(roleA);
        when(roleResourceService.findAllResourceByRoleId("roleA"))
                .thenReturn(Arrays.asList(res1, res2));
        when(resourceService.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(res3));

        SysUserRole ur1 = SysUserRole.builder().uid("U1").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.singletonList(ur1));
        when(userService.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(user1));

        PermissionChangeDTO dto = new PermissionChangeDTO();
        dto.setRoleId("roleA");
        dto.setResourceIds(Arrays.asList("R1", "R2", "R3"));

        PermissionChangePreviewVO preview = sysRoleService.previewPermissionChange(dto);

        assertEquals("roleA", preview.getRoleId());
        assertEquals("管理员", preview.getRoleName());
        assertEquals(1, preview.getAddedResources().size());
        assertEquals("R3", preview.getAddedResources().get(0).getId());
        assertTrue(preview.getRemovedResources().isEmpty());
        assertEquals(1, preview.getAffectedUsers().size());
        assertEquals("admin", preview.getAffectedUsers().get(0).getUsername());
    }

    /**
     * 测试场景：角色资源删除
     * 角色当前拥有 [R1,R2,R3]，提议变更为 [R1]
     * 预期：addedResources=[], removedResources=[R2,R3]
     */
    @Test
    public void testPreviewResourceRemove() {
        when(baseMapper.selectById("roleA")).thenReturn(roleA);
        when(roleResourceService.findAllResourceByRoleId("roleA"))
                .thenReturn(Arrays.asList(res1, res2, res3));

        SysUserRole ur1 = SysUserRole.builder().uid("U1").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.singletonList(ur1));
        when(userService.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(user1));

        PermissionChangeDTO dto = new PermissionChangeDTO();
        dto.setRoleId("roleA");
        dto.setResourceIds(Collections.singletonList("R1"));

        PermissionChangePreviewVO preview = sysRoleService.previewPermissionChange(dto);

        assertTrue(preview.getAddedResources().isEmpty());
        assertEquals(2, preview.getRemovedResources().size());
        assertTrue(preview.getRemovedResources().stream()
                .anyMatch(r -> "R2".equals(r.getId())));
        assertTrue(preview.getRemovedResources().stream()
                .anyMatch(r -> "R3".equals(r.getId())));
    }

    /**
     * 测试场景：应用变更后刷新权限缓存
     * 存在差异时，验证 shiroService.clearAuthByUserIdCollection 被正确调用
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testApplyChangeRefreshesCache() {
        when(baseMapper.selectById("roleA")).thenReturn(roleA);
        when(roleResourceService.findAllResourceByRoleId("roleA"))
                .thenReturn(Arrays.asList(res1, res2));
        when(resourceService.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(res3));
        when(roleResourceService.delete(any(EntityWrapper.class))).thenReturn(true);
        when(roleResourceService.insert(any(SysRoleResource.class))).thenReturn(true);

        SysUserRole ur1 = SysUserRole.builder().uid("U1").rid("roleA").build();
        SysUserRole ur2 = SysUserRole.builder().uid("U2").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Arrays.asList(ur1, ur2));
        when(userService.selectBatchIds(anyList()))
                .thenReturn(Arrays.asList(user1, user2));

        PermissionChangeDTO dto = new PermissionChangeDTO();
        dto.setRoleId("roleA");
        dto.setResourceIds(Arrays.asList("R1", "R2", "R3"));

        PermissionChangeResultVO result = sysRoleService.applyPermissionChange(dto);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getRefreshedCount());
        assertNotNull(result.getChangeId());
        assertEquals(2, result.getAffectedUserIds().size());

        // 验证缓存清除被调用，且包含正确的用户ID
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(shiroService).clearAuthByUserIdCollection(captor.capture(), eq(true), eq(false));
        List<String> clearedIds = captor.getValue();
        assertTrue(clearedIds.contains("U1"));
        assertTrue(clearedIds.contains("U2"));

        // 验证旧关系被删除，新关系被插入
        verify(roleResourceService).delete(any(EntityWrapper.class));
        verify(roleResourceService, times(3)).insert(any(SysRoleResource.class));

        // 验证可以通过 changeId 查询结果
        PermissionChangeResultVO queried = sysRoleService.getPermissionChangeResult(result.getChangeId());
        assertEquals(result.getChangeId(), queried.getChangeId());
    }

    /**
     * 测试场景：无影响变更（资源未改变）
     * 角色当前拥有 [R1,R2]，提议变更也是 [R1,R2]
     * 预期：refreshedCount=0，不调用缓存清除
     */
    @Test
    public void testApplyNoImpactChange() {
        when(baseMapper.selectById("roleA")).thenReturn(roleA);
        when(roleResourceService.findAllResourceByRoleId("roleA"))
                .thenReturn(Arrays.asList(res1, res2));

        SysUserRole ur1 = SysUserRole.builder().uid("U1").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.singletonList(ur1));
        when(userService.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(user1));

        PermissionChangeDTO dto = new PermissionChangeDTO();
        dto.setRoleId("roleA");
        dto.setResourceIds(Arrays.asList("R1", "R2"));

        PermissionChangeResultVO result = sysRoleService.applyPermissionChange(dto);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getRefreshedCount());

        // 不应调用缓存清除
        verify(shiroService, never()).clearAuthByUserIdCollection(anyList(), anyBoolean(), anyBoolean());
        // 不应修改角色-资源关系
        verify(roleResourceService, never()).delete(any(EntityWrapper.class));
        verify(roleResourceService, never()).insert(any(SysRoleResource.class));
    }

    /**
     * 测试场景：用户多角色
     * 用户同时拥有角色A和角色B，修改角色A的资源
     * 验证：用户出现在受影响列表中，预览正确限定在角色A的变更范围
     */
    @Test
    public void testPreviewMultiRoleUser() {
        SysRole roleB = SysRole.builder().id("roleB").name("编辑员").build();

        when(baseMapper.selectById("roleA")).thenReturn(roleA);
        when(roleResourceService.findAllResourceByRoleId("roleA"))
                .thenReturn(Collections.singletonList(res1));
        when(resourceService.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(res2));

        // user1 同时拥有 roleA 和 roleB
        SysUserRole ur1 = SysUserRole.builder().uid("U1").rid("roleA").build();
        when(userRoleService.selectList(any(EntityWrapper.class)))
                .thenReturn(Collections.singletonList(ur1));
        when(userService.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(user1));

        PermissionChangeDTO dto = new PermissionChangeDTO();
        dto.setRoleId("roleA");
        dto.setResourceIds(Arrays.asList("R1", "R2"));

        PermissionChangePreviewVO preview = sysRoleService.previewPermissionChange(dto);

        // 用户在受影响列表中
        assertEquals(1, preview.getAffectedUsers().size());
        assertEquals("U1", preview.getAffectedUsers().get(0).getId());

        // 变更范围仅限角色A：新增R2
        assertEquals(1, preview.getAddedResources().size());
        assertEquals("R2", preview.getAddedResources().get(0).getId());
        assertTrue(preview.getRemovedResources().isEmpty());

        // 只查询了 roleA 的资源，没有涉及 roleB
        verify(roleResourceService).findAllResourceByRoleId("roleA");
        verify(roleResourceService, never()).findAllResourceByRoleId("roleB");
    }

}
