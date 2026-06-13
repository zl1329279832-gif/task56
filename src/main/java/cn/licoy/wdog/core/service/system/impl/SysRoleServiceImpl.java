package cn.licoy.wdog.core.service.system.impl;

import cn.licoy.wdog.common.exception.RequestException;
import cn.licoy.wdog.common.service.BaseService;
import cn.licoy.wdog.core.dto.system.role.FindRoleDTO;
import cn.licoy.wdog.core.dto.system.role.PermissionChangeDTO;
import cn.licoy.wdog.core.dto.system.role.RoleAddDTO;
import cn.licoy.wdog.core.dto.system.role.RoleUpdateDTO;
import cn.licoy.wdog.core.entity.system.*;
import cn.licoy.wdog.core.mapper.system.SysRoleMapper;
import cn.licoy.wdog.core.service.global.ShiroService;
import cn.licoy.wdog.core.service.system.SysRoleResourceService;
import cn.licoy.wdog.core.service.system.SysRoleService;
import cn.licoy.wdog.core.service.system.SysResourceService;
import cn.licoy.wdog.core.service.system.SysUserRoleService;
import cn.licoy.wdog.core.service.system.SysUserService;
import cn.licoy.wdog.core.vo.system.PermissionChangePreviewVO;
import cn.licoy.wdog.core.vo.system.PermissionChangeResultVO;
import cn.licoy.wdog.core.vo.system.SysUserVO;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.plugins.Page;
import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Transactional
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper,SysRole> implements SysRoleService{

    @Autowired
    private SysRoleResourceService roleResourceService;

    @Autowired
    private SysUserRoleService userRoleService;

    @Autowired
    private ShiroService shiroService;

    @Autowired
    @Lazy
    private SysUserService userService;

    @Autowired
    private SysResourceService resourceService;

    private final ConcurrentHashMap<String, PermissionChangeResultVO> changeResults = new ConcurrentHashMap<>();

    @Override
    public List<SysRole> findAllRoleByUserId(String uid,Boolean hasResource) {
        List<SysUserRole> userRoles = userRoleService.selectList(new EntityWrapper<SysUserRole>().eq("uid", uid));
        List<SysRole> roles = new ArrayList<>();
        userRoles.forEach(v->{
            SysRole role = this.selectById(v.getRid());
            if(role!=null){
                if(hasResource){
                    List<SysResource> permissions = roleResourceService.findAllResourceByRoleId(role.getId());
                    role.setResources(permissions);
                }
            }
            roles.add(role);
        });
        return roles;
    }

    @Override
    public Page<SysRole> list(FindRoleDTO findRoleDTO) {
        EntityWrapper<SysRole> wrapper = new EntityWrapper<>();
        wrapper.orderBy("id",findRoleDTO.getAsc());
        Page<SysRole> rolePage = this.selectPage(new Page<>(findRoleDTO.getPage(),
                findRoleDTO.getPageSize()), wrapper);
        if(findRoleDTO.getHasResource()){
            if(rolePage.getRecords()!=null){
                rolePage.getRecords().forEach(v->
                        v.setResources(roleResourceService.findAllResourceByRoleId(v.getId())));
            }
        }
        return rolePage;
    }

    @Override
    public void remove(String rid) {
        SysRole role = this.selectById(rid);
        if(role==null) throw RequestException.fail("角色不存在！");
        try {
            this.deleteById(rid);
            this.updateCache(role,true,false);
        }catch (DataIntegrityViolationException e){
            throw RequestException.fail(
                    String.format("请先解除角色为 %s 角色的全部用户！",role.getName()),e);
        }catch (Exception e){
            throw RequestException.fail("角色删除失败！",e);
        }
    }

    @Override
    public void update(String rid, RoleUpdateDTO roleUpdateDTO) {
        SysRole role = this.selectById(rid);
        if(role==null) throw RequestException.fail("角色不存在！");
        BeanUtils.copyProperties(roleUpdateDTO,role);
        try {
            this.updateById(role);
            roleResourceService.delete(new EntityWrapper<SysRoleResource>()
                    .eq("rid",rid));
            for (SysResource sysResource : roleUpdateDTO.getResources()) {
                roleResourceService.insert(SysRoleResource.builder()
                        .pid(sysResource.getId())
                        .rid(role.getId())
                        .build());
            }
            this.updateCache(role,true,false);
        }catch (Exception e){
            throw RequestException.fail("角色更新失败！",e);
        }

    }

    @Override
    public void add(RoleAddDTO addDTO) {
        SysRole role = this.selectOne(new EntityWrapper<SysRole>().eq("name",addDTO.getName()));
        if(role!=null){
            throw RequestException.fail(
                    String.format("已经存在名称为 %s 的角色",addDTO.getName()));
        }
        role = new SysRole();
        BeanUtils.copyProperties(addDTO,role);
        try {
            this.insert(role);
            for (SysResource sysResource : addDTO.getResources()) {
                roleResourceService.insert(SysRoleResource.builder()
                        .pid(sysResource.getId())
                        .rid(role.getId())
                        .build());
            }
        }catch (Exception e){
            throw RequestException.fail("添加失败",e);
        }
    }

    @Override
    public void updateCache(SysRole role,Boolean author, Boolean out) {
        List<SysUserRole> sysUserRoles = userRoleService.selectList(new EntityWrapper<SysUserRole>()
                .eq("rid", role.getId())
                .groupBy("uid"));
        List<String> userIdList = new ArrayList<>();
        if(sysUserRoles!=null && sysUserRoles.size()>0){
            sysUserRoles.forEach(v-> userIdList.add(v.getUid()));
        }
        shiroService.clearAuthByUserIdCollection(userIdList,author,out);
    }

    @Override
    public PermissionChangePreviewVO previewPermissionChange(PermissionChangeDTO dto) {
        SysRole role = this.selectById(dto.getRoleId());
        if (role == null) {
            throw RequestException.fail("角色不存在！");
        }

        // 当前角色拥有的资源
        List<SysResource> currentResources = roleResourceService.findAllResourceByRoleId(dto.getRoleId());
        Set<String> currentIds = currentResources.stream()
                .map(SysResource::getId).collect(Collectors.toSet());

        // 提议的资源集合
        Set<String> proposedIds = new HashSet<>(dto.getResourceIds());

        // 计算新增资源
        Set<String> addedIds = new HashSet<>(proposedIds);
        addedIds.removeAll(currentIds);
        List<SysResource> addedResources = new ArrayList<>();
        if (!addedIds.isEmpty()) {
            addedResources = resourceService.selectBatchIds(new ArrayList<>(addedIds));
        }

        // 计算移除资源
        List<SysResource> removedResources = currentResources.stream()
                .filter(r -> !proposedIds.contains(r.getId()))
                .collect(Collectors.toList());

        // 查找受影响的用户
        List<SysUserRole> userRoles = userRoleService.selectList(
                new EntityWrapper<SysUserRole>().eq("rid", dto.getRoleId()));
        List<SysUserVO> affectedUsers = new ArrayList<>();
        if (userRoles != null && !userRoles.isEmpty()) {
            List<String> userIds = userRoles.stream()
                    .map(SysUserRole::getUid).distinct().collect(Collectors.toList());
            List<SysUser> users = userService.selectBatchIds(userIds);
            if (users != null) {
                users.forEach(u -> {
                    SysUserVO vo = new SysUserVO();
                    BeanUtils.copyProperties(u, vo);
                    affectedUsers.add(vo);
                });
            }
        }

        return PermissionChangePreviewVO.builder()
                .roleId(role.getId())
                .roleName(role.getName())
                .affectedUsers(affectedUsers)
                .addedResources(addedResources)
                .removedResources(removedResources)
                .build();
    }

    @Override
    public PermissionChangeResultVO applyPermissionChange(PermissionChangeDTO dto) {
        PermissionChangePreviewVO preview = previewPermissionChange(dto);

        List<String> affectedUserIds = preview.getAffectedUsers().stream()
                .map(SysUserVO::getId).collect(Collectors.toList());

        boolean hasDiff = !preview.getAddedResources().isEmpty()
                || !preview.getRemovedResources().isEmpty();

        if (hasDiff) {
            // 删除旧的角色-资源关系
            roleResourceService.delete(new EntityWrapper<SysRoleResource>()
                    .eq("rid", dto.getRoleId()));

            // 插入新的角色-资源关系
            for (String resourceId : dto.getResourceIds()) {
                roleResourceService.insert(SysRoleResource.builder()
                        .pid(resourceId)
                        .rid(dto.getRoleId())
                        .build());
            }

            // 清除受影响用户的权限缓存
            if (!affectedUserIds.isEmpty()) {
                shiroService.clearAuthByUserIdCollection(affectedUserIds, true, false);
            }
        }

        String changeId = UUID.randomUUID().toString();
        PermissionChangeResultVO result = PermissionChangeResultVO.builder()
                .changeId(changeId)
                .roleId(preview.getRoleId())
                .roleName(preview.getRoleName())
                .timestamp(new Date())
                .affectedUserIds(affectedUserIds)
                .refreshedCount(hasDiff ? affectedUserIds.size() : 0)
                .success(true)
                .build();

        changeResults.put(changeId, result);
        return result;
    }

    @Override
    public PermissionChangeResultVO getPermissionChangeResult(String changeId) {
        PermissionChangeResultVO result = changeResults.get(changeId);
        if (result == null) {
            throw RequestException.fail("变更记录不存在！");
        }
        return result;
    }
}
