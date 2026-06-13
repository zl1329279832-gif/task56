package cn.licoy.wdog.core.vo.system;

import cn.licoy.wdog.core.entity.system.SysResource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 权限变更影响预览VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionChangePreviewVO {

    private String roleId;

    private String roleName;

    private List<SysUserVO> affectedUsers;

    private List<SysResource> addedResources;

    private List<SysResource> removedResources;

}
