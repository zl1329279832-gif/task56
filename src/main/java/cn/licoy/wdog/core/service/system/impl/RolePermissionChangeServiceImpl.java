package cn.licoy.wdog.core.service.system.impl;

import cn.licoy.wdog.common.exception.RequestException;
import cn.licoy.wdog.core.entity.system.SysResource;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.entity.system.SysRoleResource;
import cn.licoy.wdog.core.entity.system.SysUser;
import cn.licoy.wdog.core.entity.system.SysUserRole;
import cn.licoy.wdog.core.service.global.ShiroService;
import cn.licoy.wdog.core.service.system.RolePermissionChangeService;
import cn.licoy.wdog.core.service.system.SysResourceService;
import cn.licoy.wdog.core.service.system.SysRoleResourceService;
import cn.licoy.wdog.core.service.system.SysRoleService;
import cn.licoy.wdog.core.service.system.SysUserRoleService;
import cn.licoy.wdog.core.service.system.SysUserService;
import cn.licoy.wdog.core.vo.system.CacheRefreshResultVO;
import cn.licoy.wdog.core.vo.system.RoleChangePreviewVO;
import cn.licoy.wdog.core.vo.system.UserBriefVO;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 角色权限变更服务实现
 */
@Service
@Transactional
public class RolePermissionChangeServiceImpl implements RolePermissionChangeService {

    @Autowired
    private SysRoleService roleService;

    @Autowired
    private SysRoleResourceService roleResourceService;

    @Autowired
    private SysUserRoleService userRoleService;

    @Autowired
    private SysResourceService resourceService;

    @Autowired
    private SysUserService userService;

    @Autowired
    private ShiroService shiroService;

    /**
     * 角色最近一次缓存刷新结果（内存缓存，进程重启后丢失）
     */
    private final Map<String, CacheRefreshResultVO> refreshResultMap = new ConcurrentHashMap<>();

    @Override
    public RoleChangePreviewVO previewImpact(String roleId, List<SysResource> newResources) {
        // 1. 校验角色
        SysRole role = roleService.selectById(roleId);
        if (role == null) {
            throw RequestException.fail("角色不存在！");
        }

        // 2. 获取当前角色已绑定的资源
        List<SysResource> currentResources = roleResourceService.findAllResourceByRoleId(roleId);
        if (currentResources == null) {
            currentResources = Collections.emptyList();
        }

        // 3. 解析新资源完整信息
        List<SysResource> resolvedNewResources = resolveResources(newResources);

        // 4. 计算资源差异（基于资源ID的集合差集）
        Set<String> currentIds = currentResources.stream()
                .map(SysResource::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> newIds = resolvedNewResources.stream()
                .map(SysResource::getId).collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> addedIds = new HashSet<>(newIds);
        addedIds.removeAll(currentIds);

        Set<String> removedIds = new HashSet<>(currentIds);
        removedIds.removeAll(newIds);

        List<SysResource> addedResources = resolvedNewResources.stream()
                .filter(r -> addedIds.contains(r.getId()))
                .collect(Collectors.toList());

        List<SysResource> removedResources = currentResources.stream()
                .filter(r -> removedIds.contains(r.getId()))
                .collect(Collectors.toList());

        // 5. 提取权限标识差异
        List<String> addedPermissions = extractPermissions(addedResources);
        List<String> removedPermissions = extractPermissions(removedResources);

        // 6. 查找受影响用户（绑定该角色的所有用户）
        List<UserBriefVO> affectedUsers = findAffectedUsers(roleId);

        return RoleChangePreviewVO.builder()
                .roleId(roleId)
                .roleName(role.getName())
                .affectedUsers(affectedUsers)
                .addedResources(addedResources)
                .removedResources(removedResources)
                .addedPermissions(addedPermissions)
                .removedPermissions(removedPermissions)
                .affectedUserCount(affectedUsers.size())
                .build();
    }

    @Override
    public CacheRefreshResultVO applyChange(String roleId, List<SysResource> newResources) {
        // 1. 先预览影响
        RoleChangePreviewVO preview = previewImpact(roleId, newResources);

        // 2. 校验角色
        SysRole role = roleService.selectById(roleId);

        // 3. 判断是否有实际变更
        boolean hasChange = !preview.getAddedResources().isEmpty()
                || !preview.getRemovedResources().isEmpty();

        if (!hasChange) {
            // 无影响变更
            CacheRefreshResultVO result = CacheRefreshResultVO.builder()
                    .roleId(roleId)
                    .roleName(role.getName())
                    .refreshedUserIds(Collections.emptyList())
                    .refreshedUserCount(0)
                    .filterChainReloaded(false)
                    .refreshTime(new Date())
                    .status("NO_IMPACT")
                    .build();
            refreshResultMap.put(roleId, result);
            return result;
        }

        // 4. 执行资源绑定变更
        roleResourceService.delete(new EntityWrapper<SysRoleResource>().eq("rid", roleId));
        for (SysResource resource : newResources) {
            roleResourceService.insert(SysRoleResource.builder()
                    .pid(resource.getId())
                    .rid(roleId)
                    .build());
        }

        // 5. 重载 Shiro 过滤链
        shiroService.reloadPerms();

        // 6. 清除受影响用户的权限缓存
        List<String> userIds = findAffectedUserIds(roleId);
        if (!userIds.isEmpty()) {
            shiroService.clearAuthByUserIdCollection(userIds, true, false);
        }

        // 7. 记录并返回刷新结果
        CacheRefreshResultVO result = CacheRefreshResultVO.builder()
                .roleId(roleId)
                .roleName(role.getName())
                .refreshedUserIds(userIds)
                .refreshedUserCount(userIds.size())
                .filterChainReloaded(true)
                .refreshTime(new Date())
                .status("SUCCESS")
                .build();
        refreshResultMap.put(roleId, result);
        return result;
    }

    @Override
    public CacheRefreshResultVO getLastRefreshResult(String roleId) {
        return refreshResultMap.get(roleId);
    }

    /**
     * 解析资源列表：根据传入的资源ID批量查询完整资源信息
     */
    private List<SysResource> resolveResources(List<SysResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = resources.stream()
                .map(SysResource::getId)
                .filter(id -> !StringUtils.isEmpty(id))
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysResource> resolved = resourceService.selectBatchIds(ids);
        return resolved != null ? resolved : Collections.emptyList();
    }

    /**
     * 从资源列表中提取非空的权限标识
     */
    private List<String> extractPermissions(List<SysResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return Collections.emptyList();
        }
        return resources.stream()
                .map(SysResource::getPermission)
                .filter(p -> !StringUtils.isEmpty(p) && !"".equals(p.trim()))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 查找绑定指定角色的所有用户（去重），返回用户摘要列表
     */
    private List<UserBriefVO> findAffectedUsers(String roleId) {
        List<String> userIds = findAffectedUserIds(roleId);
        if (userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysUser> users = userService.selectBatchIds(userIds);
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }
        return users.stream()
                .map(u -> UserBriefVO.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 查找绑定指定角色的所有用户ID（去重）
     */
    private List<String> findAffectedUserIds(String roleId) {
        List<SysUserRole> userRoles = userRoleService.selectList(
                new EntityWrapper<SysUserRole>().eq("rid", roleId));
        if (userRoles == null || userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        return userRoles.stream()
                .map(SysUserRole::getUid)
                .distinct()
                .collect(Collectors.toList());
    }
}
