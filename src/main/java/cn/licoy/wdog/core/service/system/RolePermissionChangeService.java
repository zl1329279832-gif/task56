package cn.licoy.wdog.core.service.system;

import cn.licoy.wdog.core.entity.system.SysResource;
import cn.licoy.wdog.core.vo.system.CacheRefreshResultVO;
import cn.licoy.wdog.core.vo.system.RoleChangePreviewVO;

import java.util.List;

/**
 * 角色权限变更服务
 * 提供变更影响预览、应用变更并刷新缓存、查询刷新结果的能力
 */
public interface RolePermissionChangeService {

    /**
     * 预览角色资源变更的影响范围
     *
     * @param roleId       角色ID
     * @param newResources 新的资源列表（仅包含id即可）
     * @return 变更影响预览结果
     */
    RoleChangePreviewVO previewImpact(String roleId, List<SysResource> newResources);

    /**
     * 应用角色资源变更并刷新权限缓存
     *
     * @param roleId       角色ID
     * @param newResources 新的资源列表（仅包含id即可）
     * @return 缓存刷新结果
     */
    CacheRefreshResultVO applyChange(String roleId, List<SysResource> newResources);

    /**
     * 查询指定角色最近一次的缓存刷新结果
     *
     * @param roleId 角色ID
     * @return 刷新结果，若无记录则返回null
     */
    CacheRefreshResultVO getLastRefreshResult(String roleId);
}
