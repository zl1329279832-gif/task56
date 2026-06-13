package cn.licoy.wdog.core.vo.system;

import cn.licoy.wdog.core.entity.system.SysResource;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 角色权限变更影响预览结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ApiModel(value = "角色权限变更影响预览")
public class RoleChangePreviewVO implements Serializable {

    @ApiModelProperty(value = "角色ID")
    private String roleId;

    @ApiModelProperty(value = "角色名称")
    private String roleName;

    @ApiModelProperty(value = "受影响的用户列表")
    private List<UserBriefVO> affectedUsers;

    @ApiModelProperty(value = "新增的资源")
    private List<SysResource> addedResources;

    @ApiModelProperty(value = "移除的资源")
    private List<SysResource> removedResources;

    @ApiModelProperty(value = "新增的权限标识")
    private List<String> addedPermissions;

    @ApiModelProperty(value = "移除的权限标识")
    private List<String> removedPermissions;

    @ApiModelProperty(value = "受影响用户数量")
    private int affectedUserCount;

    private static final long serialVersionUID = 1L;
}
