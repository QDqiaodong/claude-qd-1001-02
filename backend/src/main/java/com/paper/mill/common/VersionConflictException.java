package com.paper.mill.common;

import java.util.Map;

/**
 * 乐观锁版本冲突：把数据库中的当前版本和当前订单带回给失败方。
 */
public class VersionConflictException extends RuntimeException {

    private final Long expectedVersion;
    private final Long currentVersion;
    private final transient Map<String, Object> current;

    public VersionConflictException(Long expectedVersion, Long currentVersion, Map<String, Object> current) {
        super("订单已被其他操作员修改，请刷新后基于当前版本 "
                + (currentVersion == null ? 0 : currentVersion) + " 重试");
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
        this.current = current;
    }

    public Long getExpectedVersion() {
        return expectedVersion;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }

    public Map<String, Object> getCurrent() {
        return current;
    }
}
