package com.paper.mill.common;

/**
 * 并发冲突：两名操作员基于同一版本同时修改同一条业务数据时，只有一方成功；
 * 后提交的一方拿到本异常（HTTP 409），消息里带服务端当前版本与具体冲突原因。
 *
 * <p>与 {@link BizException} 区分开：业务规则不满足是「你这次操作不合法」（400，原数据不变）；
 * 版本冲突是「你编辑时依据的数据已被别人改过」（409，必须按当前版本重新确认后再提交）。</p>
 */
public class ConflictException extends RuntimeException {

    /** 冲突资源的服务端当前版本，前端据此回填并提示用户重新确认 */
    private final Long currentVersion;

    public ConflictException(String message, Long currentVersion) {
        super(message);
        this.currentVersion = currentVersion;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }
}
