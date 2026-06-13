package cn.licoy.wdog.core.vo.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 权限变更刷新结果VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionChangeResultVO {

    private String changeId;

    private String roleId;

    private String roleName;

    private Date timestamp;

    private List<String> affectedUserIds;

    private int refreshedCount;

    private boolean success;

}
