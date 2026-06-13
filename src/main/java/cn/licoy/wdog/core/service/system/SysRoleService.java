package cn.licoy.wdog.core.service.system;

import cn.licoy.wdog.common.service.BaseService;
import cn.licoy.wdog.core.dto.system.role.FindRoleDTO;
import cn.licoy.wdog.core.dto.system.role.PermissionChangeDTO;
import cn.licoy.wdog.core.dto.system.role.RoleAddDTO;
import cn.licoy.wdog.core.dto.system.role.RoleUpdateDTO;
import cn.licoy.wdog.core.entity.system.SysRole;
import cn.licoy.wdog.core.entity.system.SysUser;
import cn.licoy.wdog.core.vo.system.PermissionChangePreviewVO;
import cn.licoy.wdog.core.vo.system.PermissionChangeResultVO;
import com.baomidou.mybatisplus.plugins.Page;
import com.baomidou.mybatisplus.service.IService;

import java.util.List;

public interface SysRoleService extends IService<SysRole>,
        BaseService<SysRole,RoleAddDTO,RoleUpdateDTO,String,FindRoleDTO> {

    /**
     * 获取指定ID用户的所有角色（并附带查询所有的角色的权限）
     * @param uid 用户ID
     * @return 角色集合
     */
    List<SysRole> findAllRoleByUserId(String uid,Boolean hasResource);

    /**
     * 更新缓存
     * @param role 角色
     * @param author 是否清空授权信息
     * @param out 是否清空session
     */
    void updateCache(SysRole role,Boolean author, Boolean out);

    /**
     * 预览权限变更影响
     * @param dto 权限变更请求
     * @return 影响预览
     */
    PermissionChangePreviewVO previewPermissionChange(PermissionChangeDTO dto);

    /**
     * 应用权限变更并刷新缓存
     * @param dto 权限变更请求
     * @return 变更结果
     */
    PermissionChangeResultVO applyPermissionChange(PermissionChangeDTO dto);

    /**
     * 查询权限变更刷新结果
     * @param changeId 变更ID
     * @return 变更结果
     */
    PermissionChangeResultVO getPermissionChangeResult(String changeId);
}
